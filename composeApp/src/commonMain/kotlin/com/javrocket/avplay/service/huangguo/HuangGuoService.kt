package com.javrocket.avplay.service.huangguo

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.net.platformUserAgent
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.service.ManagedService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 黄果站适配器 —— 全量业务方法的“第一条竖切”。
 *
 * 端点全部来自反编译代码里的字面量（HuangGuoService / HuangGuoUrlKeys）：
 *   /api/home、/api/videos、/api/videos/category/{slug}、/api/search、
 *   /api/videos/{id}、/video/{id}/playback、/api/ranks/{type}、/api/topics、/api/videos/tag/{tag}
 *
 * 选它做样板的原因：纯 JSON、无 Cloudflare、无签名加密 —— 最适合先把
 * “Ktor → 模型 → UI” 这条竖切打通，再复制模板到其它站点。
 *
 * 注意：响应字段名是按“同名字段优先 + 常见别名兜底”的方式解析的（见 pickXxx），
 * 接入真实站时用 golden reference 的字段名对齐即可，不需要改结构。
 */
class HuangGuoService(
    baseUrl: String,
    private val http: HttpClient,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    // ---- 首页 / 列表 ----------------------------------------------------

    suspend fun getHome(): Result<HomeFeed> = runServiceOperation("huangguo.home", Timeouts.STANDARD) {
        val body = http.get("$base/api/home").bodyTextChecked("huangguo.home")
        parseHome(body)
    }

    suspend fun getVideos(page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("huangguo.videos", Timeouts.STANDARD) {
            val body = http.get("$base/api/videos") { parameter("page", page) }
                .bodyTextChecked("huangguo.videos")
            parseVideoPage(body, page)
        }

    suspend fun getCategoryVideos(slug: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("huangguo.category", Timeouts.STANDARD) {
            val body = http.get("$base/api/videos/category/${slug.encodeURLParameter()}") {
                parameter("page", page)
            }.bodyTextChecked("huangguo.category")
            parseVideoPage(body, page)
        }

    /** 首页 `featured` 列表，对应 Tab「专题」。 */
    suspend fun getFeaturedList(): Result<List<VideoBrief>> =
        runServiceOperation("huangguo.featured", Timeouts.STANDARD) {
            val body = http.get("$base/api/home").bodyTextChecked("huangguo.featured")
            val root = unwrap(body) as? JsonObject ?: throw AppError.Parse("huangguo.featured 响应不是对象")
            val arr = root["featured"] as? JsonArray ?: JsonArray(emptyList())
            val items = arr.mapNotNull { toBrief(it) }
            if (items.isEmpty()) throw AppError.Parse("huangguo.featured 为空")
            items
        }

    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("huangguo.search", Timeouts.STANDARD) {
            val body = http.get("$base/api/search") {
                parameter("keyword", keyword)
                parameter("page", page)
            }.bodyTextChecked("huangguo.search")
            parseVideoPage(body, page)
        }

    suspend fun getRanking(type: String = "weekly", page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("huangguo.ranking", Timeouts.STANDARD) {
            val body = http.get("$base/api/ranks/${type.encodeURLParameter()}") {
                parameter("page", page)
            }.bodyTextChecked("huangguo.ranking")
            parseVideoPage(body, page)
        }

    // ---- 详情 -----------------------------------------------------------

    suspend fun getVideoDetail(id: String): Result<VideoDetail> =
        runServiceOperation("huangguo.detail", Timeouts.STANDARD) {
            val body = http.get("$base/api/videos/${id.encodeURLParameter()}")
                .bodyTextChecked("huangguo.detail")
            parseDetail(body, id)
        }

    // ---- 播放 -----------------------------------------------------------

    /**
     * 播放地址。
     *
     * golden reference 里是 **POST** `/api/videos/{id}/episodes/{episode}/playback`，body 空 JSON；
     * 第一版按 REST 直觉写成 `GET /video/{id}/playback`，结果拿到 301 → 404 的站点 404 页面，
     * 一度以为是站点限制。端点必须以反编译的字符串为准。
     */
    suspend fun getPlayback(id: String, episode: Int = 1): Result<PlaybackInfo> =
        runServiceOperation("huangguo.playback", Timeouts.STANDARD) {
            val body = http.post("$base/api/videos/${id.encodeURLParameter()}/episodes/$episode/playback") {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }.bodyTextChecked("huangguo.playback")
            parsePlayback(body, videoId = id, sourcePageUrl = "$base/video/$id/")
        }

    // ---- 解析 -----------------------------------------------------------

    private fun parseHome(body: String): HomeFeed {
        val root = unwrap(body)
        val sections = ArrayList<VideoSection>()

        val obj = root as? JsonObject
        if (obj != null) {
            // 1) 显式 sections 数组
            val rawSections = obj.arrayOf("sections", "blocks", "modules")
            for (s in rawSections) {
                val so = s as? JsonObject ?: continue
                val title = so.stringOf("title", "name") ?: continue
                val items = so.arrayOf("items", "videos", "list", "rows").mapNotNull { toBrief(it) }
                if (items.isNotEmpty()) sections += VideoSection(title, items)
            }

            // 2) 顶层散落的“分类块”：每个 key 是一个区块标题
            val knownMeta = setOf("code", "message", "msg", "status", "success", "data", "sections", "blocks", "modules")
            for ((key, value) in obj) {
                if (key in knownMeta) continue
                val arr = value as? JsonArray ?: continue
                val items = arr.mapNotNull { toBrief(it) }
                if (items.size >= 2) sections += VideoSection(key, items)
            }
        }

        // 3) 兜底：整页就是一个平铺数组
        if (sections.isEmpty()) {
            val flat = root.asArray().mapNotNull { toBrief(it) }
            if (flat.isNotEmpty()) sections += VideoSection("最新", flat)
        }

        if (sections.isEmpty()) throw AppError.Parse("huangguo.home 未解析出任何区块")
        return HomeFeed(sections)
    }

    private fun parseVideoPage(body: String, page: Int): Page<VideoBrief> {
        val root = unwrap(body)
        val items = when (root) {
            is JsonArray -> root.mapNotNull { toBrief(it) }
            is JsonObject -> root.arrayOf("list", "items", "videos", "rows", "data").mapNotNull { toBrief(it) }
            else -> emptyList()
        }
        val explicitMore = (root as? JsonObject)?.boolOf("has_more", "hasMore", "more")
        val hasMore = when {
            explicitMore != null -> explicitMore
            items.isEmpty() -> false
            else -> items.size >= DEFAULT_PAGE_SIZE
        }
        return Page(items, page, hasMore)
    }

    private fun parseDetail(body: String, id: String): VideoDetail {
        val root = unwrap(body) as? JsonObject ?: throw AppError.Parse("huangguo.detail 响应不是对象")

        val brief = toBrief(root) ?: VideoBrief(id = id, title = root.stringOf("title", "name").orEmpty())
        val description = root.textOf("description", "desc", "intro", "summary").orEmpty()
        val actors = root.linksOf("actors", "actress", "stars")
        val tags = root.linksOf("tags", "tag", "categories", "genres")
        val episodes = root.arrayOf("episodes", "parts", "playlist").mapIndexed { i, el ->
            val eo = el as? JsonObject
            com.javrocket.avplay.model.Episode(
                index = eo?.intOf("index", "episode", "no") ?: (i + 1),
                title = eo?.stringOf("title", "name") ?: "第 ${i + 1} 集",
                url = eo?.stringOf("url", "link", "play_url").orEmpty(),
            )
        }
        val related = root.arrayOf("related", "recommend", "similar").mapNotNull { toBrief(it) }

        return VideoDetail(brief, description, actors, tags, episodes, related)
    }

    /**
     * 播放响应 → PlaybackInfo。
     *
     * 响应形状（golden reference `HuangGuoPlaybackData`）：
     * `{data:{status,msg,data:{streams:{main,hevc},cdn:{auth_key,auth_value},video_url}}}`
     * —— 注意有两层 `data`：外层是 HuangGuoResult，内层才是播放数据，`unwrap` 只剥了外层。
     *
     * 播放器（OkHttpDataSource）不走 Ktor 的默认头，所以三个防盗链头必须显式交给它：
     * `Origin` / `Referer: {site}/video/{id}/` / `User-Agent`，另有 CDN 下发的动态鉴权头。
     */
    private suspend fun parsePlayback(
        body: String,
        videoId: String,
        sourcePageUrl: String,
    ): PlaybackInfo {
        val root = unwrap(body) as? JsonObject ?: throw AppError.Parse("huangguo.playback 响应不是对象")
        val payload = (root["data"] as? JsonObject) ?: root

        val streams = payload.objectOf("streams")
        val url = streams?.stringOf("main", "hevc")?.takeIf { it.isNotBlank() }
            ?: payload.stringOf("video_url", "url", "play_url", "m3u8", "src")
            ?: throw AppError.Parse("huangguo.playback 响应里没有播放地址")

        val cdn = payload.objectOf("cdn")
        val headers = LinkedHashMap<String, String>()
        headers["Origin"] = base
        headers["Referer"] = sourcePageUrl
        runCatching { platformUserAgent() }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["User-Agent"] = it }
        cdn?.let { c ->
            val key = c.stringOf("auth_key", "authKey")
            val value = c.stringOf("auth_value", "authValue")
            if (!key.isNullOrBlank() && !value.isNullOrBlank()) headers[key] = value
        }

        val mime = guessMime(url)
        platformLog("HuangGuo", "playback resolved video=$videoId mime=$mime headers=${headers.keys}")
        return PlaybackInfo(url, mime, headers, sourcePageUrl)
    }

    private fun guessMime(url: String): String = when {
        url.contains(".m3u8", true) -> "application/vnd.apple.mpegurl"
        url.contains(".mpd", true) -> "application/dash+xml"
        url.contains(".mp4", true) -> "video/mp4"
        else -> "application/octet-stream"
    }

    // ---- JSON 工具（对未知字段名做别名兜底）----------------------------

    /**
     * 剥掉 `{data, status, msg}` / `{code, message, data}` 外壳。
     *
     * ⚠️ 两种包装的成功判定**不是一回事**，这里踩过坑：
     * - 黄果（`HuangGuoResult`）：原实现 `isSuccess()` 直接是 **`status == 1`**，
     *   即 **1 成功、0 失败**。第一次实现按 0/200 当成功，结果所有正常响应都被
     *   判成「接口返回错误（1）」。
     * - JavDB 风格：`code == 0` 成功，10000/10001/10002 是登录/订阅/版权三种失败。
     */
    private fun unwrap(body: String): JsonElement {
        val root = AppJson.parseToJsonElement(body)
        if (root is JsonObject) {
            root.intOf("code")?.let { code ->
                if (code != 0) throw mapBusinessCode(code, root.stringOf("message", "msg"))
            }
            root.intOf("status")?.let { status ->
                if (status != 1) {
                    val msg = root.stringOf("message", "msg")
                    throw AppError.Http(status, msg?.takeIf { it.isNotBlank() } ?: "接口返回错误（$status）")
                }
            }
            val data = root["data"]
            if (data != null && data !is JsonNull) return data
        }
        return root
    }

    /** 业务码 → 具名错误，对应原实现 JavDbConst 的 10000/10001/10002。 */
    private fun mapBusinessCode(code: Int, message: String?): AppError = when (code) {
        10000 -> AppError.AuthenticationRequired(loginVersion = 0)
        10001 -> AppError.PremiumRequired
        10002 -> AppError.CopyrightForbidden
        403 -> AppError.Http(403, message ?: "服务器拒绝访问")
        else -> AppError.Http(code, message ?: "接口返回错误（$code）")
    }

    /**
     * 一条记录 → VideoBrief。
     *
     * 三个坑（都来自实测首页 JSON）：
     * 1. `banners` 同时有 `id` 和 `video_id`，前者是 banner 自己的 id（28、33…），
     *    后者才是视频 id —— 必须 `video_id` 优先，否则点进去是错的内容；
     * 2. banner 的 `title` 是空串，正文在 `desc` 里，空标题要拿简介兜底（截断）；
     * 3. `cover` / `image` 都是 `/upload_01/...` 这种**相对路径**，必须拼域名 —— 但不是站点域名！
     *    实测：拼 `huangguoai.com` 会 404，正确域名是图片专用的 `pic.qnlycc.cn`
     *    （站点首页 HTML 里 100 处图片全指这个域名，形如
     *    `https://pic.qnlycc.cn/upload_01/upload/20xxxx/xxx.jpeg?auth_key=...`）。
     *    `auth_key` 是网页端的防盗链签名，但实测**不带也能取到图**，
     *    所以这里只拼域名 + 路径，不做签名 —— 少一个会失效的定时炸弹。
     */
    private fun toBrief(el: JsonElement): VideoBrief? {
        val o = el as? JsonObject ?: return null
        val id = o.stringOf("video_id", "vid", "id", "slug") ?: return null
        val title = o.stringOf("title", "name", "caption")?.takeIf { it.isNotBlank() }
            ?: o.stringOf("desc", "intro", "summary")?.let {
                if (it.length > 40) it.take(40) + "…" else it
            }.orEmpty()
        val cover = resolveCover(o.stringOf("image", "cover", "cover_url", "thumbnail", "poster"))
        val desc = o.textOf("desc", "description", "intro", "summary")
        val displayTitle = o.stringOf("title", "name", "caption")?.takeIf { it.isNotBlank() }
        val titleFinal = displayTitle
            ?: desc?.let { if (it.length > 40) it.take(40) + "…" else it }.orEmpty()
        val subtitle = if (displayTitle != null) desc else null
        return VideoBrief(
            id = id,
            title = titleFinal,
            cover = cover,
            code = o.stringOf("code", "number", "dvd_id", "badge"),
            duration = o.stringOf("duration", "length", "time"),
            rating = o.doubleOf("rating", "score", "rate"),
            subtitle = subtitle,
            hot = o.longOf("hot"),
            episodeCount = o.intOf("episode_count", "episodeCount"),
            isFinished = o.boolOf("is_finished", "isFinished"),
            sourceSite = SITE_ID,
        )
    }

    private fun resolveCover(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return if (raw.startsWith("http", ignoreCase = true)) raw else absolutize(IMAGE_BASE_URL, raw)
    }

    private fun JsonObject.stringOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
        (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content.isNotBlank() }?.content?.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.textOf(vararg keys: String): String? = stringOf(*keys)

    private fun JsonObject.intOf(vararg keys: String): Int? = stringOf(*keys)?.toIntOrNull()

    private fun JsonObject.longOf(vararg keys: String): Long? = stringOf(*keys)?.toLongOrNull()

    private fun JsonObject.doubleOf(vararg keys: String): Double? = stringOf(*keys)?.toDoubleOrNull()

    private fun JsonObject.boolOf(vararg keys: String): Boolean? = stringOf(*keys)?.lowercase()?.let {
        when (it) { "true", "1", "yes" -> true; "false", "0", "no" -> false; else -> null }
    }

    private fun JsonObject.arrayOf(vararg keys: String): JsonArray =
        keys.firstNotNullOfOrNull { k -> this[k] as? JsonArray } ?: JsonArray(emptyList())

    private fun JsonObject.objectOf(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonElement.asArray(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    private fun JsonObject.linksOf(vararg keys: String): List<ValueLink> {
        val raw = keys.firstNotNullOfOrNull { k -> this[k] as? JsonArray } ?: return emptyList()
        return raw.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.content.takeIf { it.isNotBlank() }?.let { ValueLink(it, "") }
                is JsonObject -> {
                    val name = el.stringOf("name", "value", "title", "text").orEmpty()
                    val link = el.stringOf("link", "url", "href").orEmpty()
                    if (name.isBlank() && link.isBlank()) null else ValueLink(name, link)
                }
                else -> null
            }
        }
    }

    companion object {
        const val SITE_ID = "huangguo"
        const val DEFAULT_BASE_URL = "https://huangguoai.com"

        /**
         * 图片域名 —— 与站点域名不同，容易踩坑。
         * 站点首页 HTML 里所有 `<img>`/背景图都指向这个域名；拼站点域名会 404。
         */
        const val IMAGE_BASE_URL = "https://pic.qnlycc.cn"

        private const val DEFAULT_PAGE_SIZE = 20
    }
}
