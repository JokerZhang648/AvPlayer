package com.javrocket.avplay.site.aggregator

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.net.platformUserAgent
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.parser.originOf
import com.javrocket.avplay.service.ManagedService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText

/**
 * 聚合器服务：站点不提供直链时的兜底路径。
 *
 * 入口是"按番号在聚合站搜索"，再顺着搜索结果的播放页一跳跳找到真链
 * （jav.guru 还有 token 网关，由 JavGuruGatewayExtractor 处理）。
 *
 * ⚠️ 两个搜索 URL 模板：
 * - jav.guru 的 `/searcho/{code}.html` 来自反编译里的 `startsWith("/searcho/")` 判定，可信；
 * - javgg 用 WordPress 通用搜索 `/?s={code}`，是按站点形态推的，**本站未联网实测**。
 *   拿不到候选时只会返回空结果（不会误判），修模板时改这里一处即可。
 */
class PlaybackAggregatorService(
    private val http: HttpClient,
) : ManagedService {

    // 两层超时都要压在 Timeouts.LONG(30s) 以内：外层服务超时先到的话，
    // 内层还在跑的协程会被一起取消，日志里只能看到"超时"看不到是哪一跳卡住。
    private val resolver = PlaybackTargetResolver(
        loader = HttpPlaybackPageLoader(http),
        targetTimeoutMillis = 8_000,
        totalTimeoutMillis = 25_000,
    )

    override fun getBaseUrl(): String = SITES[JAV_GG_BASE] ?: ""

    /**
     * 按番号解析播放地址。`code` 形如 `SNOS-359` / `snos359`，两种写法都能命中。
     */
    suspend fun playback(
        code: String,
        site: PlaybackAggregatorSite = PlaybackAggregatorSite.JAV_GG,
    ): Result<PlaybackInfo> = runServiceOperation("aggregator.playback", Timeouts.LONG) {
        require(code.isNotBlank()) { "番号为空" }

        val entry = entryUrl(site, code)
        platformLog("Aggregator", "site=${site.name} entry=${entry.take(90)}")

        val html = runCatching {
            http.get(entry).bodyAsText()
        }.getOrElse { throw AppError.Network(it) }

        val targets = parseTargets(entry, html, code)
        platformLog("Aggregator", "candidates=${targets.size}")
        if (targets.isEmpty()) throw AppError.Parse("聚合站没有搜到「$code」的播放页")

        val resolved = resolver.resolveAll(targets).firstOrNull()
            ?: throw AppError.Parse("聚合解析失败：候选页里没找到可播地址")

        resolved.toPlaybackInfo()
    }

    /**
     * 两个聚合站都试：先 javgg（对日系番号命中率高），没结果再 jav.guru（有 token 网关，欧美/小众番号更全）。
     *
     * 实测：`MDVR-448`（VR 片）在 javgg 上 candidates=0 但 jav.guru 有；`SNOS-359` 反过来两个都有。
     * 所以只试一个站等于把一半番号判死刑。
     */
    suspend fun playbackAnySite(code: String): Result<PlaybackInfo> {
        var lastError: Throwable? = null
        for (site in listOf(PlaybackAggregatorSite.JAV_GG, PlaybackAggregatorSite.JAV_GURU)) {
            val result = playback(code, site)
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            platformLog("Aggregator", "site=${site.name} failed: ${lastError?.message}")
        }
        return Result.failure(lastError ?: AppError.Parse("聚合站都没有「$code」的播放页"))
    }

    /**
     * 已知入口页（例如 AVDB 的 `link_embed` 嵌入页）时直接从这里跳，不再去聚合站搜索。
     *
     * 和 playback 的区别只在"入口从哪来"：这条路径下入口是站点自己给的，
     * 比自己拿番号去聚合站碰运气命中率高得多。
     */
    suspend fun playbackFromPage(
        pageUrl: String,
        id: String,
        title: String = "",
        referrer: String = "",
    ): Result<PlaybackInfo> = runServiceOperation("aggregator.page", Timeouts.LONG) {
        require(pageUrl.startsWith("http")) { "入口页地址无效" }
        platformLog("Aggregator", "page entry=${pageUrl.take(90)}")

        val target = PlaybackTarget(
            id = id,
            title = title.ifBlank { id },
            url = pageUrl,
            referrer = referrer.ifBlank { originOf(pageUrl) },
        )
        val resolved = resolver.resolve(target)
            ?: throw AppError.Parse("入口页里没找到可播地址")

        resolved.toPlaybackInfo()
    }

    private suspend fun ResolvedPlaybackTarget.toPlaybackInfo(): PlaybackInfo {
        val headers = LinkedHashMap<String, String>()
        originOf(sourcePageUrl).takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
        headers["Referer"] = sourcePageUrl
        runCatching { platformUserAgent() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?.let { headers["User-Agent"] = it }
        return PlaybackInfo(
            url = url,
            mimeType = mimeType,
            headers = headers,
            sourcePageUrl = sourcePageUrl,
        )
    }

    /** 搜索页 → 候选播放页。只留同域名、URL 里含番号的链接，排除搜索/分类页自身。 */
    private fun parseTargets(pageUrl: String, html: String, code: String): List<PlaybackTarget> {
        val host = hostOf(pageUrl)
        val needle = code.lowercase().replace("-", "")
        return Html.parse(html).select("a[href]")
            .map { absolutize(pageUrl, it.attr("href")) to it.text.trim() }
            .filter { (url, _) -> url.startsWith("http") && hostOf(url) == host }
            .filter { (url, _) -> url.lowercase().replace("-", "").contains(needle) }
            .filterNot { (url, _) ->
                url.contains("/searcho/") || url.contains("/tag/") ||
                    url.contains("/category/") || url.contains("/?s=")
            }
            .distinctBy { it.first }
            .take(MAX_CANDIDATES)
            .map { (url, text) ->
                PlaybackTarget(
                    id = code,
                    title = text.ifBlank { code },
                    url = url,
                    referrer = pageUrl,
                )
            }
    }

    companion object {
        const val JAV_GG_BASE = "https://javgg.net"
        const val JAV_GURU_BASE = "https://jav.guru"
        private const val MAX_CANDIDATES = 6

        private val SITES = mapOf(JAV_GG_BASE to "JavGG", JAV_GURU_BASE to "JavGuru")

        internal fun entryUrl(site: PlaybackAggregatorSite, code: String): String = when (site) {
            PlaybackAggregatorSite.JAV_GG -> "$JAV_GG_BASE/?s=$code"
            PlaybackAggregatorSite.JAV_GURU -> "$JAV_GURU_BASE/searcho/${code.lowercase()}.html"
        }
    }
}
