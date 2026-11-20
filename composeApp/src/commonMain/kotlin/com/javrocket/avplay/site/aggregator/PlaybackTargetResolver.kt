package com.javrocket.avplay.site.aggregator

import com.javrocket.avplay.core.dumpTextFile
import com.javrocket.avplay.net.HtmlParsing
import com.javrocket.avplay.net.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聚合解析器：从若干入口页面出发，逐跳找出真正能播的地址。
 *
 * 这是反编译 `PlaybackTargetResolver` 的等价实现：
 * - **单条链用 BFS**（广度优先），因为常见结构是"详情页 → 一堆播放页 → 其中某一个能给真链"，
 *   广度优先能尽快试到并列候选，而不是在一条死链上钻到黑。
 * - **多条入口并发**，但用信号量限制并发数（默认 3）：聚合站点对同一 IP 的并发很敏感，
 *   一次开十个请求会被判定为爬虫直接 429。
 * - **两道超时**：单页超时 + 整体超时。缺整体超时的话，某个站点半死不活会把整个解析拖到几十秒，
 *   用户在播放页看着转圈会以为 App 挂了。
 * - **maxPages 兜底**：防止 iframe 互相套的环（A 套 B、B 套 A）把请求打爆 —— visited 去重之外
 *   再加一层总页数上限，双保险。
 */
class PlaybackTargetResolver(
    private val loader: PlaybackPageLoader,
    private val extractors: List<PlaybackTargetExtractor> = defaultPlaybackTargetExtractors(),
    private val maxPages: Int = 12,
    private val concurrency: Int = 3,
    private val targetTimeoutMillis: Long = 20_000,
    private val totalTimeoutMillis: Long = 60_000,
) {

    suspend fun resolveAll(targets: List<PlaybackTarget>): List<ResolvedPlaybackTarget> {
        if (targets.isEmpty()) return emptyList()
        return coroutineScope {
            val permits = Semaphore(concurrency.coerceAtLeast(1))
            val results = withTimeoutOrNull(totalTimeoutMillis) {
                targets.map { target ->
                    async {
                        permits.withPermit { resolve(target) }
                    }
                }.awaitAll()
            }
            val ok = results?.filterNotNull().orEmpty()
            platformLog("Aggregator", "resolveAll targets=${targets.size} ok=${ok.size}")
            ok
        }
    }

    suspend fun resolve(initial: PlaybackTarget): ResolvedPlaybackTarget? {
        val queue = ArrayDeque<PlaybackTarget>()
        queue.addLast(initial)
        val visited = HashSet<String>()
        var pagesLoaded = 0

        while (queue.isNotEmpty()) {
            if (pagesLoaded >= maxPages) {
                platformLog("Aggregator", "stop: maxPages=$maxPages reached from ${initial.url}")
                return null
            }
            val target = queue.removeFirst()
            if (!visited.add(target.url)) continue
            pagesLoaded++

            val page = withTimeoutOrNull(targetTimeoutMillis) {
                loader.load(target.url, target.referrer)
            }
            if (page == null) {
                platformLog("Aggregator", "skip (timeout/load-fail) url=${target.url.take(80)}")
                continue
            }

            val extractor = extractors.firstOrNull { it.supports(page) }
            if (extractor == null) {
                platformLog("Aggregator", "no extractor for ${page.finalUrl.take(80)}")
                continue
            }

            when (val extraction = extractor.extract(target, page)) {
                is PlaybackTargetExtraction.Media -> {
                    val url = extraction.urls.firstOrNull() ?: continue
                    platformLog(
                        "Aggregator",
                        "hit extractor=${extractor::class.simpleName} url=${url.take(90)}",
                    )
                    return ResolvedPlaybackTarget(
                        id = target.id,
                        title = target.title,
                        // 抽取器知道就说抽取器的；不知道才按后缀猜 —— 否则无后缀的 HLS 会被
                        // 当成 mp4 走 ProgressiveMediaSource，报一个看不懂的 Source error。
                        url = url,
                        mimeType = extraction.mimeType
                            ?: if (url.contains(".m3u8", ignoreCase = true)) {
                                HLS_MIME
                            } else {
                                "video/mp4"
                            },
                        sourcePageUrl = page.finalUrl,
                    )
                }

                is PlaybackTargetExtraction.Next -> {
                    extraction.targets.forEach { queue.addLast(it) }
                }

                PlaybackTargetExtraction.Empty -> Unit
            }
        }
        return null
    }
}

/**
 * 真实的页面加载器：走应用统一 HttpClient（带 Cookie / UA / Origin / Referer 的那套），
 * 这里额外把"上一跳的 finalUrl"作为 Referer，防盗链站点靠这个放行。
 *
 * 失败一律返回 null 而不是抛异常：聚合链路里"某一跳失败"是常态，抛异常会让整条链断掉。
 */
class HttpPlaybackPageLoader(
    private val http: HttpClient,
) : PlaybackPageLoader {

    override suspend fun load(url: String, referrer: String): LoadedPlaybackPage? = runCatching {
        val response = http.get(url) {
            if (referrer.isNotBlank()) header(HttpHeaders.Referrer, referrer)
        }
        val html = response.bodyAsText()
        // 聚合链路最容易"看起来成功、其实拿到的不是那个页面"（跳验证页 / 空壳 / 登录墙），
        // 所以这里必须把"拿到了什么"落一笔：长度 + 几个特征串命中情况。
        platformLog(
            "Aggregator",
            "loaded len=${html.length} m3u8=${html.contains(".m3u8")} " +
                "cfg=${html.contains("PLAYER_CONFIG")} script=${html.contains("<script")}",
        )
        if (HtmlParsing.dumpEnabled) {
            dumpTextFile("agg_" + url.hashCode().toString(16), html)
        }
        LoadedPlaybackPage(requestedUrl = url, finalUrl = url, html = html)
    }.onFailure {
        platformLog("Aggregator", "load fail ${url.take(80)}: ${it.message}")
    }.getOrNull()
}
