package com.javrocket.avplay.service.xchina

import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.net.HtmlParsing
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.service.ManagedService
import com.javrocket.avplay.site.MediaUrlExtractor
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

    // ---- 详情页 → 直链 --------------------------------------------------

    /**
     * 详情页里内嵌播放器配置（新站没有独立下载页），所以直接"抓详情页 + 媒体地址提取"。
     * 提取逻辑与聚合器共用（`MediaUrlExtractor` 已经处理 `\u0026` 转义、packer 混淆、
     * 无后缀 m3u8 路径三种情况）。
     */
    suspend fun getVideoDownload(id: String): Result<PlaybackInfo> =
        runServiceOperation("xchina.playback", Timeouts.STANDARD) {
            val pageUrl = "$base$PATH_VIDEO$id"
            val html = http.get(pageUrl).bodyTextChecked("xchina.playback")
            val urls = MediaUrlExtractor.rankedMediaUrls(html)
            if (urls.isEmpty()) {
                throw com.javrocket.avplay.core.AppError.Parse("xchina 详情页没有可用媒体地址（可能需会员或已改版）")
            }
            PlaybackInfo(
                url = urls.first(),
                sourcePageUrl = pageUrl,
            )
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://xchina.co"

        // 2026-11 改版后的路径（实测 /videos 200、/video/<hash> 403-CF、/download/<id> 404）
        private const val PATH_LIST = "/videos"
        private const val PATH_VIDEO = "/video/"
        private const val PATH_SEARCH = "/search"
        private const val PATH_MODEL = "/actor/"
        private const val PATH_CATEGORY = "/video-category/"
    }
}
