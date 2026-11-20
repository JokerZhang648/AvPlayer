package com.javrocket.avplay.service.avdb

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.model.Episode
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.service.ManagedService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * AVDB 适配器 —— 走它的 MacCMS 采集接口（`/api.php/provide/vod/`）。
 *
 * 为什么不是抓网页：
 * 1. 网页版有 Cloudflare 与 JS 渲染，解析成本高且易碎；
 * 2. 这个接口是站点自己在页面上就外链的（搜索结果里每个条目都是
 *    `/api.php/provide/vod?ac=detail&ids=xxx`），是**官方暴露**的结构化入口，不是逆向猜测；
 * 3. 已实测：`ac=list&wd=SNOS-359` 返回 200 / JSON，键为 `id / name / movie_code / episodes / server_data`。
 *
 * 该家族（苹果CMS / MacCMS）的响应形态在不同站点有差异，所以解析全部走"多键名兜底 + 多结构兜底"，
 * 拿不到播放地址时抛 Parse（而不是静默失败），UI 才能区分"站点没内容"和"解析器漏了"。
 */
class AvdbService(
    baseUrl: String,
    private val http: HttpClient,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    // ---- 列表 / 搜索 ----------------------------------------------------

    suspend fun latest(page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("avdb.latest", Timeouts.STANDARD) {
            val body = http.get("$base/api.php/provide/vod/") {
                parameter("ac", "list")
                parameter("pg", page)
            }.bodyTextChecked("avdb.latest")
            parseList(body, page)
        }

    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("avdb.search", Timeouts.STANDARD) {
            val body = http.get("$base/api.php/provide/vod/") {
                parameter("ac", "list")
                parameter("wd", keyword)
                parameter("pg", page)
            }.bodyTextChecked("avdb.search")
            parseList(body, page)
        }

    // ---- 详情 -----------------------------------------------------------

    suspend fun detail(id: String): Result<VideoDetail> =
        runServiceOperation("avdb.detail", Timeouts.STANDARD) {
            val body = http.get("$base/api.php/provide/vod/") {
                parameter("ac", "detail")
                parameter("ids", id)
            }.bodyTextChecked("avdb.detail")
            val item = firstItem(body) ?: throw AppError.Parse("avdb.detail 没有返回数据")
            val brief = toBrief(item) ?: throw AppError.Parse("avdb.detail 缺少 id")
            VideoDetail(
                brief = brief,
                description = item.stringOf("content", "description", "desc", "blurb").orEmpty(),
                actors = item.listOf("actor", "actors")
                    .filterNot { it.equals("Updating", true) || it.equals("Unknown", true) }
                    .map { ValueLink(value = it) },
                tags = (listOfNotNull(
                    item.stringOf("type_name", "class"),
                    item.stringOf("tag"),
                    item.stringOf("year"),
                ).flatMap { it.split(',', '/') } + item.listOf("category", "country"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.equals("Updating", true) }
                    .distinct()
                    .map { ValueLink(value = it) },
                episodes = parseEpisodes(item),
                related = emptyList(),
            )
        }

    /**
     * 播放地址：直接从详情里的剧集列表取第一条（或指定集）。
     *
     * 取不到时抛 Parse —— AVDB 的 `link_embed` 是嵌入页，需要走聚合器再解析一跳，
     * 那是 aggregator 的职责，不在这里硬解。
     */
    suspend fun playback(id: String, episode: Int = 1): Result<PlaybackInfo> =
        runServiceOperation("avdb.playback", Timeouts.STANDARD) {
            val body = http.get("$base/api.php/provide/vod/") {
                parameter("ac", "detail")
                parameter("ids", id)
            }.bodyTextChecked("avdb.playback")
            val item = firstItem(body) ?: throw AppError.Parse("avdb.playback 没有返回数据")

            val episodes = parseEpisodes(item)
            val chosen = episodes.getOrNull((episode - 1).coerceAtLeast(0))
            val url = chosen?.url?.takeIf { it.isNotBlank() }
                ?: item.stringOf("link_embed", "link", "url")
                    ?.takeIf { it.isNotBlank() }
                ?: throw AppError.Parse("avdb.playback 里没有可用播放地址")

            val headers = mapOf("Referer" to "$base/")
            platformLog("Avdb", "playback id=$id episode=$episode m3u8=${url.contains(".m3u8")}")
            PlaybackInfo(
                url = url,
                mimeType = if (url.contains(".m3u8", true)) "application/x-mpegURL" else "video/mp4",
                headers = headers,
                sourcePageUrl = "$base/",
            )
        }

    // ---- 解析 -----------------------------------------------------------

    private fun parseList(body: String, page: Int): Page<VideoBrief> {
        val root = AppJson.parseToJsonElement(body) as? JsonObject
            ?: throw AppError.Parse("avdb 列表响应不是对象")
        root.intOf("code")?.let { code ->
            if (code != 1) throw AppError.Http(code, root.stringOf("msg") ?: "接口返回错误（$code）")
        }
        val list = root["list"] as? JsonArray ?: JsonArray(emptyList())
        val items = list.mapNotNull { toBrief(it) }
        val pageCount = root.intOf("pagecount") ?: 1
        platformLog("Avdb", "list page=$page items=${items.size} pagecount=$pageCount")
        return Page(items = items, page = page, hasMore = page < pageCount)
    }

    private fun firstItem(body: String): JsonObject? {
        val root = AppJson.parseToJsonElement(body) as? JsonObject ?: return null
        val list = root["list"] as? JsonArray ?: return null
        return list.firstOrNull() as? JsonObject
    }

    private fun toBrief(el: JsonElement): VideoBrief? {
        val o = el as? JsonObject ?: return null
        val id = o.stringOf("id", "vod_id") ?: return null
        val code = o.stringOf("movie_code", "vod_sub", "code", "slug")
        return VideoBrief(
            id = id,
            title = o.stringOf("name", "vod_name") ?: code.orEmpty(),
            cover = o.stringOf("pic", "vod_pic", "poster_url", "thumb_url", "cover", "thumbnail"),
            code = code,
            duration = o.stringOf("duration", "vod_len", "remarks"),
            rating = o.doubleOf("score", "vod_score", "rating"),
            sourceSite = SITE_ID,
        )
    }

    /**
     * 剧集解析：MacCMS 家族至少四种形态，全都兜。
     * 1) `episodes` 是数组，元素带 `url`/`link`/`name`
     * 2) `episodes` / `play_url` 是字符串：`第01集$https://...m3u8#第02集$https://...`
     * 3) `server_data` 是数组，元素带 `url`
     * 4) `episodes` 是对象（AVDB 实测形态）：
     *    `{"server_name":"VIP #1","server_data":{"Full":{"slug":"full","link_embed":"https://..."}}}`
     */
    private fun parseEpisodes(o: JsonObject): List<Episode> {
        val out = ArrayList<Episode>()

        // 形态 4 优先：AVDB 的 server_data 是「剧集名 → {slug, link_embed}」的 map。
        // 注意 link_embed 是嵌入页（如 upload18.to/play/index/...），不是直链，
        // 真正播放要走聚合器；这里只负责把「有哪些集、入口在哪」如实交给上层。
        (o["episodes"] as? JsonObject)?.let { epsObj ->
            when (val sd = epsObj["server_data"]) {
                is JsonObject -> for ((key, v) in sd) {
                    val so = v as? JsonObject ?: continue
                    val url = so.stringOf("link_embed", "link", "url", "play_url") ?: continue
                    out += Episode(
                        index = out.size + 1,
                        title = key.ifBlank { "第${out.size + 1}集" },
                        url = url,
                    )
                }

                is JsonArray -> sd.forEach { el ->
                    val so = el as? JsonObject ?: return@forEach
                    val url = so.stringOf("link_embed", "url", "link", "play_url") ?: return@forEach
                    out += Episode(
                        index = out.size + 1,
                        title = so.stringOf("name", "title") ?: "第${out.size + 1}集",
                        url = url,
                    )
                }

                else -> Unit
            }
        }
        if (out.isNotEmpty()) return out

        (o["server_data"] as? JsonArray)?.forEach { el ->
            val so = el as? JsonObject ?: return@forEach
            val url = so.stringOf("url", "link", "play_url") ?: return@forEach
            out += Episode(index = out.size + 1, title = so.stringOf("name") ?: "第${out.size + 1}集", url = url)
        }
        if (out.isNotEmpty()) return out

        when (val eps = o["episodes"]) {
            is JsonArray -> eps.forEach { el ->
                val eo = el as? JsonObject ?: return@forEach
                val url = eo.stringOf("url", "link", "play_url") ?: return@forEach
                out += Episode(index = out.size + 1, title = eo.stringOf("name") ?: "第${out.size + 1}集", url = url)
            }

            is JsonPrimitive -> out += splitMaccmsPlayUrl(eps.content)
            else -> Unit
        }
        if (out.isEmpty()) {
            o.stringOf("play_url", "vod_play_url")?.let { out += splitMaccmsPlayUrl(it) }
        }
        return out
    }

    /** `名称$地址#名称$地址` → 剧集列表；多播放源用 `$$$` 分隔，只取第一组。 */
    private fun splitMaccmsPlayUrl(raw: String): List<Episode> {
        if (raw.isBlank()) return emptyList()
        return raw.substringBefore("$$$")
            .split('#')
            .mapIndexedNotNull { i, chunk ->
                val parts = chunk.split('$')
                if (parts.size < 2) return@mapIndexedNotNull null
                val name = parts[0].trim()
                val url = parts[1].trim()
                if (!url.startsWith("http")) return@mapIndexedNotNull null
                Episode(index = i + 1, title = name.ifBlank { "第${i + 1}集" }, url = url)
            }
    }

    companion object {
        const val SITE_ID = "avdb"
        const val DEFAULT_BASE_URL = "https://avdbapi.com"
    }
}

// ---- JSON 小工具（本文件私有，避免与其它站点互相耦合）------------------

private fun JsonObject.stringOf(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
    (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
}

private fun JsonObject.intOf(vararg keys: String): Int? = stringOf(*keys)?.toIntOrNull()

/** 取"可能是字符串、也可能是字符串数组"的字段 —— AVDB 的 actor / category / country 都是数组。 */
private fun JsonObject.listOf(vararg keys: String): List<String> =
    keys.firstNotNullOfOrNull { k ->
        when (val v = this[k]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { s -> s.isNotBlank() } }
                .takeIf { it.isNotEmpty() }

            is JsonPrimitive -> listOf(v.content).takeIf { v.content.isNotBlank() }
            else -> null
        }
    }.orEmpty()

private fun JsonObject.doubleOf(vararg keys: String): Double? = stringOf(*keys)?.toDoubleOrNull()
