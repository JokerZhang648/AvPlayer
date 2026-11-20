package com.javrocket.avplay.service.javdb

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.Episode
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.service.ManagedService
import com.javrocket.avplay.site.MediaUrlExtractor
import com.javrocket.avplay.site.aggregator.PlaybackAggregatorService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * JavDB 适配器 —— **双通道**：API 优先，HTML 兜底。
 *
 * 为什么要两条：
 * - HTML 页面是人看的，站点一改版选择器就废；但 HTML 一定存在（也最容易带 Cloudflare）。
 * - 内容 API 结构稳定，可它需要站点授权/风控放行，未登录时常直接 404/403。
 * 所以顺序是"先试 API，失败就降级 HTML"，且把降级原因记进日志 —— 线上出问题一眼就能看出走的是哪条。
 *
 * 现状（诚实记录，别当成 bug）：
 * - `https://javdb.com/api/v1/movies/{id}` 实测返回 **404**（该端点未对普通客户端开放）；
 * - `https://javdb.com/search?q=...` 实测 **403**（Cloudflare 风控），需要 App 内过验证页拿到
 *   `cf_clearance` 后，由 Cookie 桥带上来才可能通。
 * 因此默认实际生效的是 HTML 通道；API 通道保留为"拿到授权后的快路径"。
 */
class JavDbService(
    baseUrl: String,
    private val http: HttpClient,
    private val aggregator: PlaybackAggregatorService? = null,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    /** 最近一次详情解析走的通道（"api" / "html"），给诊断页用。 */
    var lastChannel: String = ""
        private set

    // ---- 首页 -----------------------------------------------------------

    suspend fun home(): Result<com.javrocket.avplay.model.HomeFeed> =
        runServiceOperation("javdb.home", Timeouts.STANDARD) {
            val html = http.get(base).bodyTextChecked("javdb.home")
            val items = parseListItems(html, base)
            if (items.isEmpty()) throw AppError.Parse("JavDB 首页没有解析到条目")
            com.javrocket.avplay.model.HomeFeed(
                sections = listOf(com.javrocket.avplay.model.VideoSection(title = "最新", items = items)),
            )
        }

    // ---- 搜索 / 详情 -----------------------------------------------------

    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("javdb.search", Timeouts.STANDARD) {
            val url = "$base/search?q=${keyword}&f=all&page=$page"
            val html = http.get(url).bodyTextChecked("javdb.search")
            val items = parseListItems(html, url)
            platformLog("JavDb", "search q=$keyword items=${items.size}")
            Page(items = items, page = page, hasMore = items.isNotEmpty())
        }

    suspend fun detail(id: String): Result<VideoDetail> =
        runServiceOperation("javdb.detail", Timeouts.STANDARD) {
            val viaApi = runCatching { loadDetailViaApi(id) }
                .onFailure { platformLog("JavDb", "api channel failed: ${it.message}") }
                .getOrNull()
            if (viaApi != null) {
                lastChannel = "api"
                return@runServiceOperation viaApi
            }
            lastChannel = "html"
            loadDetailViaHtml(id)
        }

    /**
     * 播放地址：JavDB 自身不给直链，交给聚合器按番号去找。
     * 注意 id 可能是 `/v/abc` 形态，先规整成番号。
     */
    suspend fun playback(id: String): Result<PlaybackInfo> = playbackCode(codeFromId(id))

    /** 直接用番号走聚合（上层已经从详情页换到番号时用这个）。 */
    suspend fun playbackCode(code: String): Result<PlaybackInfo> =
        runServiceOperation("javdb.playback", Timeouts.LONG) {
            platformLog("JavDb", "aggregate by code=$code")
            val agg = aggregator ?: throw AppError.Parse("JavDB 不含直链，需要一个聚合器实例")
            agg.playbackAnySite(code).getOrElse { throw it }
        }

    // ---- 通道 1：JSON API ----------------------------------------------

    private suspend fun loadDetailViaApi(id: String): VideoDetail {
        val url = "$base/api/v1/movies/${codeFromId(id)}"
        val body = http.get(url).bodyTextChecked("javdb.api")
        val root = AppJson.parseToJsonElement(body) as? JsonObject
            ?: throw AppError.Parse("javdb.api 响应不是对象")

        // 兼容 `{movie:{...}}` 与直接平铺两种形态
        val node = (root["movie"] as? JsonObject) ?: root
        val title = node.stringOf("title", "name") ?: throw AppError.Parse("javdb.api 缺少 title")
        val cover = node.stringOf("cover", "image", "pic")?.let { absolutize(base, it) }

        return VideoDetail(
            brief = VideoBrief(
                id = id,
                title = title,
                cover = cover,
                code = node.stringOf("number", "code") ?: codeFromId(id),
                sourceSite = SITE_ID,
            ),
            description = node.stringOf("description", "desc").orEmpty(),
        )
    }

    // ---- 通道 2：HTML ----------------------------------------------------

    /**
     * HTML 通道：按真实页面结构（2026-11 实测）取字段，不依赖 og 标签。
     *
     * 实测这个站的详情页**没有** `og:title` / `og:image`，元数据全在
     * `nav.panel.movie-panel-info` 的 `div.panel-block` 里：
     *   `<div class="panel-block first-block"><strong>番號:</strong> <span class="value">Tushy.2026.11.15</span>`
     *   `<strong>時長:</strong> <span class="value">31 分鍾</span>`
     *   `<strong>評分:</strong> <span class="value">… 4.0分, 由8人評價</span>`
     *   `<strong>演員:</strong> <a class="actor-female" href="/actors/x">名字</a>, …`
     * 标题是 `<h2 class="title is-4"><strong>番号</strong><strong class="current-title">纯标题</strong></h2>`，
     * 封面是 `img.video-cover`。
     */
    private suspend fun loadDetailViaHtml(id: String): VideoDetail {
        val url = if (id.startsWith("http")) id else "$base/v/${codeFromId(id)}"
        val html = http.get(url).bodyTextChecked("javdb.html")

        // 部分条目（欧美/新片/受限内容）对未登录用户会 302 到 /login，Ktor 跟随后拿到的是登录表单。
        // 此时按页面结构硬解析只会得到"空壳"（code 空、actors=0），看起来像解析坏了其实是权限问题。
        // 抛 AuthenticationRequired 更诚实：UI 才能提示"打开网页登录"，登录后 Cookie 共享即可正常解析。
        if (isLoginPage(html)) {
            platformLog("JavDb", "html detail requires login id=$id")
            throw AppError.AuthenticationRequired()
        }

        val doc = Html.parse(html)

        val codeText = panelValue(html, "番號", "番号")
            .ifBlank {
                // 兜底：页面里第一个 data-clipboard-text 就是番号（"複製番號"按钮）
                Regex("""data-clipboard-text="([^"]+)"""")
                    .find(html)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            }
        val heading = cleanTextOf(doc.selectFirst("h2.title")?.text)
        val currentTitle = cleanTextOf(doc.selectFirst("strong.current-title")?.text)
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()

        // 标题优先"番号 + 纯标题"（页面原本的展示形态），其次 og，最后才是整段 h2
        val title = buildString {
            if (codeText.isNotBlank()) append(codeText).append(' ')
            if (currentTitle.isNotBlank()) append(currentTitle)
        }.trim().ifBlank { heading }.ifBlank { ogTitle }.ifBlank { codeFromId(id) }

        val cover = doc.selectFirst("img.video-cover")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { absolutize(url, it) }

        val actors = doc.select("a.actor-female, a.actor-male").mapNotNull { a ->
            cleanTextOf(a.text).takeIf { it.isNotBlank() }?.let {
                ValueLink(value = it, link = absolutize(url, a.attr("href")))
            }
        }.distinctBy { it.value }

        val rating = Regex("""評分\s*[:：]\s*</strong>(.{0,400})""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.getOrNull(1)
            ?.let { Regex("""([0-9]+(?:\.[0-9]+)?)\s*分""").find(it)?.groupValues?.getOrNull(1) }
            ?.toDoubleOrNull()

        val tags = listOfNotNull(
            panelValue(html, "片商", "发行商"),
            panelValue(html, "系列"),
            panelValue(html, "類別", "类别"),
        ).flatMap { it.split(',', '，') }
            .map { cleanTextOf(it) }
            .filter { it.isNotBlank() }
            .map { ValueLink(value = it) }

        platformLog(
            "JavDb",
            "html detail id=$id code=$codeText actors=${actors.size} rating=$rating cover=${cover != null}",
        )

        return VideoDetail(
            brief = VideoBrief(
                id = id,
                title = title,
                cover = cover,
                code = codeText.ifBlank { codeFromId(id) },
                duration = panelValue(html, "時長", "时长"),
                rating = rating,
                sourceSite = SITE_ID,
            ),
            // javdb 详情页没有长简介，只有标题
            description = "",
            actors = actors,
            tags = tags,
            // 页面里的 mp4 都是"预告片"，正片要靠聚合器，所以不把它当剧集交给 UI
            episodes = emptyList(),
        )
    }

    /**
     * 从 `panel-block` 里取某个标签的值（中文/繁体两种写法都试）。
     *
     * 三种真实形态都要兜（2026-11 实测）：
     * 1) 纯文本：`<strong>時長:</strong> &nbsp;<span class="value">67 分鍾</span>`
     * 2) 带链接：`<strong>類別:</strong> …<span class="value"><a href="/tags?c7=45">第一人稱攝影</a>,&nbsp;<a…`
     * 3) 番号在**属性**里：`<a class="copy-to-clipboard" data-clipboard-text="EROFV-399">EROFV-399</a>`
     *    —— 原来的正则只认 `<span class="value">` 后紧跟的裸文本，遇到形态 3 会返回空，
     *    导致 `code` 丢了、聚合器只能拿 slug 去搜（结果 0 候选）。
     * 另外 `N/A` 当"没有"处理，不要交给 UI 显示成演员名。
     */
    /**
     * 判定"这是登录页而不是内容页"。
     *
     * 依据：内容页永远没有密码输入框；登录页一定有（`name="password"`）。
     * 这条判据比看 title 可靠 —— 站点 title 多语言（登入/登錄/Login）不好穷举。
     */
    private fun isLoginPage(html: String): Boolean =
        html.contains("name=\"password\"") || html.contains("name='password'") ||
            html.contains("action=\"/login\"") || html.contains("action='/login'")

    private fun panelValue(html: String, vararg labels: String): String {
        for (label in labels) {
            val marker = Regex("""<strong>\s*$label\s*[:：]\s*</strong>""").find(html) ?: continue
            val tail = html.substring(marker.range.last + 1).take(600)

            Regex("""data-clipboard-text="([^"]+)"""")
                .find(tail)?.groupValues?.getOrNull(1)
                ?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

            val span = Regex("""<span class="value">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
                .find(tail)?.groupValues?.getOrNull(1)
                ?: tail.take(120)
            // 标签替换成空串（不是空格）：这样 `<a>甲</a>,&nbsp;<a>乙</a>` 才能按逗号切开
            val cleaned = cleanTextOf(span.replace(Regex("<[^>]+>"), ""))
                .trimStart('&', 'n', 'b', 's', 'p', ';')
                .trim()
            if (cleaned.isNotBlank() && !cleaned.equals("N/A", ignoreCase = true)) return cleaned
        }
        return ""
    }

    private fun cleanTextOf(raw: String?): String = raw.orEmpty()
        .replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // ---- 列表解析 -------------------------------------------------------

    /** `a[href*="/v/"]` 就是条目；封面取条目里的 img，番号取 `<strong>` 或标题首段。 */
    private fun parseListItems(html: String, pageUrl: String): List<VideoBrief> =
        Html.parse(html).select("a[href*='/v/']")
            .map { node -> node to absolutize(pageUrl, node.attr("href")) }
            .distinctBy { it.second }
            .take(MAX_ITEMS)
            .map { (node, url) ->
                val strong = node.selectFirst("strong")?.text?.trim()
                VideoBrief(
                    id = url.substringAfter("/v/").substringBefore('/').ifBlank { url },
                    title = node.text.trim().ifBlank { url.substringAfterLast('/') },
                    cover = node.selectFirst("img")?.attr("src")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { absolutize(pageUrl, it) },
                    code = strong?.takeIf { it.isNotBlank() },
                    sourceSite = SITE_ID,
                )
            }

    /** `/v/abc123` / 完整 URL / 番号 三种输入都规整成番号。 */
    private fun codeFromId(id: String): String {
        if (id.startsWith("http")) return id.substringAfter("/v/").substringBefore('/').ifBlank { id }
        return id.removePrefix("/v/").substringBefore('/')
    }

    companion object {
        const val SITE_ID = "javdb"
        const val DEFAULT_BASE_URL = "https://javdb.com"
        private const val MAX_ITEMS = 30
    }
}

private fun JsonObject.stringOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
    (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}
