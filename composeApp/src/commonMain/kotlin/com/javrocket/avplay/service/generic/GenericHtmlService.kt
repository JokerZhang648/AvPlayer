package com.javrocket.avplay.service.generic

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.Episode
import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.net.platformUserAgent
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.parser.originOf
import com.javrocket.avplay.service.ManagedService
import com.javrocket.avplay.site.MediaUrlExtractor
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders

/**
 * 通用 HTML 站点适配器 —— 给"没有公开 JSON 接口、只能读网页"的站点用（首批接入 av01）。
 *
 * 设计取舍：不做逐站点硬编码 DOM（站点改版就废），而是抓三条最稳的信号：
 * 1. 条目 = 指向同站、且 URL/标题里含番号的 `<a>`；
 * 2. 封面 = 条目里第一个 `<img src>`（相对路径按页面地址补全）；
 * 3. 播放地址 = MediaUrlExtractor（正则 + 混淆 JS 解包），页面里真的没有就交给上层去走聚合器。
 *
 * 代价是精度不如专有解析器（可能多抓几条推荐位），但换来了"站点改版不炸 + 新站接入零解析代码"。
 */
class GenericHtmlService(
    private val siteId: String,
    private val siteName: String,
    baseUrl: String,
    private val http: HttpClient,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    suspend fun home(): Result<HomeFeed> = runServiceOperation("$siteId.home", Timeouts.STANDARD) {
        val html = http.get(base).bodyTextChecked("$siteId.home")
        val items = parseItems(html, base, needle = "")
        if (items.isEmpty()) throw AppError.Parse("$siteName 首页没有解析到条目")
        HomeFeed(sections = listOf(VideoSection(title = "最新", items = items)))
    }

    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("$siteId.search", Timeouts.STANDARD) {
            val html = http.get("$base/search") {
                parameter("q", keyword)
                parameter("page", page)
            }.bodyTextChecked("$siteId.search")
            val items = parseItems(html, base, needle = keyword)
            platformLog(siteId, "search q=$keyword items=${items.size}")
            Page(items = items, page = page, hasMore = items.isNotEmpty())
        }

    suspend fun detail(id: String): Result<VideoDetail> =
        runServiceOperation("$siteId.detail", Timeouts.STANDARD) {
            val url = absolutize(base, id)
            val html = http.get(url).bodyTextChecked("$siteId.detail")
            val doc = Html.parse(html)

            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { absolutize(base, it) }
            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()

            val media = MediaUrlExtractor.rankedMediaUrls(html)
            val brief = VideoBrief(
                id = id,
                title = ogTitle.ifBlank { doc.title }.ifBlank { id },
                cover = ogImage,
                sourceSite = siteId,
            )
            VideoDetail(
                brief = brief,
                description = ogDesc,
                episodes = media.mapIndexed { i, m ->
                    Episode(index = i + 1, title = "线路 ${i + 1}", url = m)
                },
            )
        }

    /**
     * 页面里直接抠媒体地址。抠不到抛 Parse，由上层决定要不要去聚合器 ——
     * 这里不自己调聚合器，避免"通用适配器"和"聚合链路"互相依赖成环。
     */
    suspend fun playback(id: String): Result<PlaybackInfo> =
        runServiceOperation("$siteId.playback", Timeouts.STANDARD) {
            val url = absolutize(base, id)
            val html = http.get(url) {
                header(HttpHeaders.Referrer, url)
            }.bodyTextChecked("$siteId.playback")

            val media = MediaUrlExtractor.rankedMediaUrls(html).firstOrNull()
                ?: throw AppError.Parse("$siteName 页面里没有直链，需要走聚合器")

            val headers = LinkedHashMap<String, String>()
            originOf(url).takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
            headers["Referer"] = url
            runCatching { platformUserAgent() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?.let { headers["User-Agent"] = it }

            PlaybackInfo(
                url = media,
                mimeType = if (media.contains(".m3u8", true)) "application/x-mpegURL" else "video/mp4",
                headers = headers,
                sourcePageUrl = url,
            )
        }

    private fun parseItems(html: String, pageUrl: String, needle: String): List<VideoBrief> {
        val lowered = needle.lowercase().replace("-", "")

        return Html.parse(html).select("a[href]")
            .map { node -> node to absolutize(pageUrl, node.attr("href")) }
            .filter { (_, url) -> url.startsWith("http") }
            .filterNot { (_, url) ->
                url.contains("/search") || url.contains("/tag/") ||
                    url.contains("/category") || url.contains("/login") || url == base
            }
            .filter { (node, url) ->
                lowered.isBlank() ||
                    url.lowercase().replace("-", "").contains(lowered) ||
                    node.text.lowercase().replace("-", "").contains(lowered)
            }
            .distinctBy { it.second }
            .take(MAX_ITEMS)
            .map { (node, url) ->
                val cover = node.selectFirst("img")?.attr("src")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { absolutize(pageUrl, it) }
                VideoBrief(
                    id = url,
                    title = node.text.trim().ifBlank { url.substringAfterLast('/') },
                    cover = cover,
                    sourceSite = siteId,
                )
            }
    }

    companion object {
        private const val MAX_ITEMS = 20
        const val AV01_SITE_ID = "av01"
        const val AV01_BASE_URL = "https://www.av01.media"
    }
}

/**
 * 通用适配器保留但不给具体站点用了 —— AV01 已改为走它自己的 JSON API
 * （见 service/av01/Av01Service.kt：SPA 壳 4.7KB，HTML 路径天然失效）。
 * 留着它是为了将来接入"没有 API 的杂站"时能零解析代码跑通。
 */
