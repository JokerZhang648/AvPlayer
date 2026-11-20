package com.javrocket.avplay.service.xchina

import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoComment
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.net.HtmlParsing
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.playbackRequestHeaders
import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.service.ManagedService
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.site.MediaUrlExtractor
import com.javrocket.avplay.site.aggregator.PlaybackAggregatorService
import com.javrocket.avplay.site.aggregator.parsePlayerConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.encodeURLParameter

/**
 * XChina 站点服务。
 *
 * ⚠️ 2026-11 站点改版，旧字面量（`videos/series-`、`video/id-`、`download/id-`）全部失效：
 *   - 列表：`/videos`（`?page=N` 有效，每页 104 个卡片）
 *   - 详情：`/video/<hash>`（hash 形如 `6ae4aea8d28b8`）—— 带 CF 保护，未过验证时返回
 *     `Just a moment...`（403），App 内过验证后即可访问
 *   - 下载页 `/download/<id>` 已 404，媒体地址只能从详情页取
 *
 * 所以这里的原则是「链接驱动」：路径常量集中在本文件，解析器按 href 形态抓卡片，
 * 站点再改版时只需要调整这几个常量与正则。
 */
class XChinaService(
    baseUrl: String,
    private val http: HttpClient,
    private val aggregator: PlaybackAggregatorService? = null,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    // ---- 列表 ----------------------------------------------------------

    suspend fun getSeries(page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("xchina.series", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_LIST",
                parser = XChinaListParser(),
                options = ParseOptions(baseUrl = base, page = page),
            ) { parameter("page", page) }
        }

    suspend fun getCategoryVideos(slug: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("xchina.category", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_CATEGORY${slug.encodeURLParameter()}",
                parser = XChinaListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = slug),
            ) { parameter("page", page) }
        }

    suspend fun search(keyword: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("xchina.search", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_SEARCH",
                parser = XChinaListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = keyword),
            ) {
                parameter("keyword", keyword)
                parameter("page", page)
            }
        }

    suspend fun actorDetail(name: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("xchina.actor", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_MODEL${name.encodeURLParameter()}",
                parser = XChinaListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = name),
            ) { parameter("page", page) }
        }

    /** 模特主页 `/model/<hash>`（2026-11 站点以 hash 为主键）。 */
    suspend fun getModelVideos(modelId: String, page: Int = 1): Result<List<VideoBrief>> =
        runServiceOperation("xchina.model", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_MODEL_PAGE$modelId",
                parser = XChinaListParser(),
                options = ParseOptions(baseUrl = base, page = page, sitePrefix = modelId),
            ) { parameter("page", page) }
        }

    // ---- 详情 ----------------------------------------------------------

    suspend fun getVideoDetail(id: String): Result<VideoDetail> =
        runServiceOperation("xchina.detail", Timeouts.STANDARD) {
            HtmlParsing.fetch(
                client = http,
                url = "$base$PATH_VIDEO$id",
                parser = XChinaDetailParser(id),
                options = ParseOptions(baseUrl = base, sitePrefix = id),
            )
        }

    /** 拉取视频全部评论页（`?cpage=N`，与站内评论区一致）。 */
    suspend fun getAllVideoComments(id: String): Result<Pair<List<VideoComment>, Int?>> =
        runServiceOperation("xchina.comments", Timeouts.LONG) {
            val pageUrl = "$base$PATH_VIDEO$id"
            val parser = XChinaCommentsParser()
            val options = ParseOptions(baseUrl = base)
            val firstHtml = http.get(pageUrl).bodyTextChecked("xchina.comments.1")
            val first = parser.parse(firstHtml, options)
            val merged = LinkedHashMap<String, VideoComment>()
            first.comments.forEach { merged[it.id] = it }
            val pages = first.totalPages.coerceIn(1, MAX_COMMENT_PAGES)
            for (p in 2..pages) {
                val html = http.get(pageUrl) { parameter("cpage", p) }.bodyTextChecked("xchina.comments.$p")
                parser.parse(html, options).comments.forEach { merged[it.id] = it }
            }
            val list = merged.values.toList()
            Pair(list, first.totalCount ?: list.size.takeIf { it > 0 })
        }

    // ---- 详情页 → 直链 --------------------------------------------------

    /**
     * 详情页里内嵌播放器或第三方嵌入页（新站没有独立 `/download/`）。
     *
     * 顺序：详情 HTML 直抠链（含 `PLAYER_CONFIG` 无后缀地址）→ 聚合器从详情/嵌入页向下跳
     * （与 AVDB 的 `link_embed` 同源逻辑，见 `docs/MIGRATION.md` §2）。
     */
    suspend fun getVideoDownload(id: String): Result<PlaybackInfo> =
        runServiceOperation("xchina.playback", Timeouts.LONG) {
            val pageUrl = "$base$PATH_VIDEO$id"
            val html = http.get(pageUrl).bodyTextChecked("xchina.playback")

            val direct = rankedDirectMedia(html, pageUrl)
            if (direct != null) {
                platformLog("XChina", "playback direct id=$id url=${direct.take(72)}")
                val headers = playbackRequestHeaders(pageUrl)
                platformLog("XChina", "playback headers=${headers.keys}")
                return@runServiceOperation PlaybackInfo(
                    url = direct,
                    mimeType = "application/x-mpegURL",
                    headers = headers,
                    sourcePageUrl = pageUrl,
                )
            }

            val agg = aggregator
                ?: throw AppError.Parse("xchina 详情页没有直链，且未配置播放聚合器")
            val embedEntries = findEmbedEntries(html, pageUrl)
            platformLog(
                "XChina",
                "playback via aggregator id=$id embeds=${embedEntries.size} hasIframe=${html.contains("<iframe", ignoreCase = true)}",
            )

            for (entry in embedEntries + pageUrl) {
                val resolved = agg.playbackFromPage(
                    pageUrl = entry,
                    id = id,
                    referrer = pageUrl,
                ).getOrNull()
                if (resolved != null) return@runServiceOperation resolved
            }
            throw AppError.Parse("xchina 详情页没有可用媒体地址（可能需登录或站点改版）")
        }

    /** 详情页内可直接播放的地址（站内 player `data-src` → 正则 → `PLAYER_CONFIG`）。 */
    private fun rankedDirectMedia(html: String, pageUrl: String): String? {
        extractNativePlayerSrc(html, pageUrl)?.let { return it }
        val fromRegex = MediaUrlExtractor.rankedMediaUrls(html)
        val fromConfig = parsePlayerConfig(html).map { it.first }
        return (fromRegex + fromConfig).firstOrNull()
    }

    /**
     * 新站自研播放器：`#video-player` 用相对路径 `data-src="/hls/<id>/master.m3u8"`，
     * 不含 `https://`，通用正则会漏掉（日志里 `m3u8=true` 但 `urls=0` 就是这个原因）。
     * VIP 仅影响去广告/下载，不等于没有播放地址。
     */
    /**
     * 第三方播放页入口：iframe、`embed` 链接、常见网盘域直链到播放页。
     * 详情页常常是"壳"，真链在子页（如 upload18 的 `PLAYER_CONFIG`）。
     */
    private fun findEmbedEntries(html: String, pageUrl: String): List<String> {
        val seen = LinkedHashSet<String>()
        val doc = Html.parse(html)
        for (node in doc.select("iframe[src], iframe[data-src]")) {
            val raw = node.attr("src").ifBlank { node.attr("data-src") }.trim()
            val abs = absolutize(pageUrl, raw)
            if (abs.startsWith("http")) seen.add(abs)
        }
        val embedHosts = listOf(
            "upload18.", "helvid.", "dood", "streamtape.", "earnvid", "vidhide", "voe.",
            "playmogo.", "/play/index/", "/embed/",
        )
        for (node in doc.select("a[href]")) {
            val abs = absolutize(pageUrl, node.attr("href"))
            if (!abs.startsWith("http")) continue
            val lower = abs.lowercase()
            if (embedHosts.any { lower.contains(it) }) seen.add(abs)
        }
        return seen.toList()
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://xchina.co"

        internal fun extractNativePlayerSrc(html: String, pageUrl: String): String? {
            val doc = Html.parse(html)
            val candidates = buildList {
                doc.select("#video-player[data-src], [data-src*='m3u8']").forEach { node ->
                    val raw = node.attr("data-src").trim()
                    if (raw.isNotBlank()) add(raw)
                }
                Regex("""data-src\s*=\s*['"]([^'"]+\.m3u8[^'"]*)['"]""", RegexOption.IGNORE_CASE)
                    .findAll(html)
                    .forEach { add(it.groupValues[1]) }
            }
            return candidates
                .map { absolutize(pageUrl, it.trim()) }
                .firstOrNull { MediaUrlExtractor.isPlayableMediaUrl(it) || it.contains(".m3u8", ignoreCase = true) }
        }

        // 2026-11 改版后的路径（实测 /videos 200、/video/<hash> 403-CF、/download/<id> 404）
        private const val PATH_LIST = "/videos"
        private const val PATH_VIDEO = "/video/"
        private const val PATH_SEARCH = "/search"
        private const val PATH_MODEL = "/actor/"
        private const val PATH_MODEL_PAGE = "/model/"
        private const val PATH_CATEGORY = "/video-category/"
        private const val MAX_COMMENT_PAGES = 100
    }
}
