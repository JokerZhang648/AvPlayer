package com.javrocket.avplay.service.av01

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
import com.javrocket.avplay.service.ManagedService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * AV01 适配器 —— 走站点自己的 JSON API（`/api/v1`），不抓网页。
 *
 * 为什么这条路可行（实测依据）：
 * 1. 站点是 React SPA，首页 HTML 只有 4.7KB 的壳，通用 HTML 适配器在这里天然失效；
 * 2. 前端 bundle 里 `this.baseUrl = apiUrl` 而 `production: { apiUrl: null }`，
 *    说明 **API 基址就是同源** `/api/v1`，不需要另找域名；
 * 3. 实测端点全部 200：
 *    - `GET  /api/v1/videos/types/latest?page=&limit=`   → `{videos:[...], pagination:{...}}`
 *    - `GET  /api/v1/videos/types/hottest?page=&limit=`
 *    - `POST /api/v1/videos/search?lang=jp` body `{"query":"..","pagination":{"page":1,"limit":20}}`
 *    - `GET  /api/v1/videos/{numericId}`                 → 单条详情
 *    - `GET  /api/v1/videos/{numericId}/manifest/master.m3u8` → HLS 主播放列表
 *    - `GET  /api/v1/videos/{numericId}/similars?page=&limit=`
 *
 * 关于封面：列表/详情里的 `cover` 是 **布尔值**（表示"有封面"），真实图片要拼
 * `https://files.iw01.xyz/covers/{id}/{size}.webp?token_v2=&expires=&ip=`，
 * 而 token 由 `https://files.iw01.xyz/edge/geo.js?json` 下发（TTL 10 分钟、绑定出口 IP）。
 * 所以封面 URL 必须**动态**拼，不能硬编码，也不能当常量缓存到磁盘 —— token 过期即 403。
 */
class Av01Service(
    baseUrl: String,
    private val http: HttpClient,
) : ManagedService {

    private val base: String = baseUrl.trimEnd('/')

    override fun getBaseUrl(): String = base

    // ---- 列表 / 搜索 ----------------------------------------------------

    suspend fun latest(page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("av01.latest", Timeouts.STANDARD) {
            val body = http.get("$base/api/v1/videos/types/latest") {
                parameter("page", page)
                parameter("limit", PAGE_SIZE)
            }.bodyTextChecked("av01.latest")
            val geo = geoToken()
            parsePage(body, page) { id -> coverUrl(id, LIST_COVER, geo) }
        }

    suspend fun hottest(page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("av01.hottest", Timeouts.STANDARD) {
            val body = http.get("$base/api/v1/videos/types/hottest") {
                parameter("page", page)
                parameter("limit", PAGE_SIZE)
            }.bodyTextChecked("av01.hottest")
            val geo = geoToken()
            parsePage(body, page) { id -> coverUrl(id, LIST_COVER, geo) }
        }

    /**
     * 搜索是 POST + JSON body：`{"query": "...", "pagination": {"page":1,"limit":20}}`。
     * 注意参数名是 **query** 而不是 q —— 传 q 站点不报错、只回空结果，很容易误判成"没搜到"。
     */
    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>> =
        runServiceOperation("av01.search", Timeouts.STANDARD) {
            val payload = JsonObject(
                mapOf(
                    "query" to JsonPrimitive(keyword),
                    "pagination" to JsonObject(
                        mapOf(
                            "page" to JsonPrimitive(page),
                            "limit" to JsonPrimitive(PAGE_SIZE),
                        ),
                    ),
                ),
            ).toString()
            val body = http.post("$base/api/v1/videos/search") {
                parameter("lang", "jp")
                setBody(TextContent(payload, ContentType.Application.Json))
            }.bodyTextChecked("av01.search")
            val geo = geoToken()
            val result = parsePage(body, page) { id -> coverUrl(id, LIST_COVER, geo) }
            platformLog("Av01", "search q=$keyword page=$page items=${result.items.size}")
            result
        }

    // ---- 详情 -----------------------------------------------------------

    suspend fun detail(id: String): Result<VideoDetail> =
        runServiceOperation("av01.detail", Timeouts.STANDARD) {
            val numericId = normalizeId(id)
            val body = http.get("$base/api/v1/videos/$numericId")
                .bodyTextChecked("av01.detail")
            val o = AppJson.parseToJsonElement(body) as? JsonObject
                ?: throw AppError.Parse("av01.detail 响应不是对象")
            if (o["id"] == null) throw AppError.Parse("av01.detail 缺少 id")

            val geo = geoToken()
            val brief = toBrief(o, cover = coverUrl(numericId, DETAIL_COVER, geo)) { sid ->
                coverUrl(sid, LIST_COVER, geo)
            } ?: throw AppError.Parse("av01.detail 解析失败")
            val related = runCatching {
                val similar = http.get("$base/api/v1/videos/$numericId/similars") {
                    parameter("page", 1)
                    parameter("limit", 12)
                }.bodyTextChecked("av01.similars")
                val arr = (AppJson.parseToJsonElement(similar) as? JsonObject)?.get("videos") as? JsonArray
                val geo = geoToken()
                arr.orEmpty().mapNotNull { toBrief(it, cover = null) { sid ->
                    coverUrl(sid, LIST_COVER, geo)
                } }
            }.getOrDefault(emptyList())

            VideoDetail(
                brief = brief,
                description = translated(o, "description").orEmpty(),
                actors = o.objectsOf("actresses").map { ValueLink(value = namedOf(it)) }
                    .filter { it.value.isNotBlank() },
                tags = o.objectsOf("tags").map { ValueLink(value = namedOf(it)) }
                    .filter { it.value.isNotBlank() },
                episodes = listOf(
                    Episode(
                        index = 1,
                        title = "全片",
                        url = manifestUrl(numericId),
                    ),
                ),
                related = related,
            )
        }

    /**
     * 播放链路（实测过完整三段，缺一段就是 403）：
     *
     * 1. `GET /api/v1/videos/{id}/cdn-access?token_v2=&expires=&ip=` → `access_token`
     *    （issuer 是 `av01-cdn`，**不是** geo.js 里那个 `av01-api` 的 token；
     *      用 geo 的 access_token 会回 401 `Jwt issuer is not configured`）
     * 2. `GET /api/v1/videos/{id}/manifest/master.m3u8` → 各档 `index90-svN-v1-a1.m3u8?ro=0`
     * 3. `GET /api/v1/videos/{id}/manifest/<档位>?ro=0&access_token=<cdn>` → **完整 media playlist**
     *    （329KB、含 ~900 段），其中 `#EXT-X-MAP` 与分片都是绝对 URL 且**已自带 access_token**
     *
     * 为什么不直接把 master.m3u8 交给播放器：master 里的档位地址指向
     * `customers.iw01.xyz/fmp4/.../map/xxx.m3u8`，那个 CDN 只服务 map/init 与分片，
     * 对 playlist 路径回 403 `Forbidden Resource Pattern`；必须经站点 `/manifest/` 代理并带 token。
     *
     * 为什么要落盘：media playlist 里的分片 URL 带一次性 token、且 Expires 约 50 分钟，
     * 播放器若自己再去拉 master/variant 就会拿到无 token 版本 → 403。
     * 先把「已带 token 的最终 playlist」写成本地文件，播放器只顺着播放列表拉分片即可。
     */
    suspend fun playback(id: String): Result<PlaybackInfo> =
        runServiceOperation("av01.playback", Timeouts.STANDARD) {
            val numericId = normalizeId(id)
            val geo = geoToken() ?: throw AppError.Parse("av01 取不到 geo token（封面/CDN 都用它）")
            val cdnToken = cdnAccessToken(numericId, geo)
            val master = http.get(manifestUrl(numericId)).bodyTextChecked("av01.master")
            val variant = pickVariant(master) ?: throw AppError.Parse("av01 master 里没有可用档位")

            val variantPath = variant.substringBefore('?')
            val variantQuery = variant.substringAfter('?', "")
            val proxyUrl = buildString {
                append("$base/api/v1/videos/$numericId/manifest/$variantPath")
                append("?access_token=$cdnToken")
                if (variantQuery.isNotBlank()) append("&$variantQuery")
            }
            val playlist = http.get(proxyUrl).bodyTextChecked("av01.playlist")
            if (!playlist.contains("#EXTM3U")) throw AppError.Parse("av01 代理回来的不是 m3u8")

            val path = com.javrocket.avplay.core.dumpTextFile("av01_${numericId}.m3u8", playlist)
                ?: throw AppError.Parse("av01 播放列表无法落盘（拿不到可写目录）")
            val fileUrl = if (path.startsWith("file://")) path else "file://$path"

            platformLog(
                "Av01",
                "playback id=$numericId variant=$variantPath segs=${playlist.lineSequence().count { it.startsWith("http") }} file=$path",
            )
            PlaybackInfo(
                url = fileUrl,
                mimeType = "application/x-mpegURL",
                // 分片 URL 自带 token，不需要任何自定义头（实测无 Referer 也 200）
                headers = emptyMap(),
                sourcePageUrl = "$base/jp/video/$numericId",
            )
        }

    /** CDN token：`av01-cdn` issuer，绑定出口 IP，exp 约 50 分钟。 */
    private suspend fun cdnAccessToken(numericId: String, geo: GeoToken): String {
        val body = http.get("$base/api/v1/videos/$numericId/cdn-access") {
            parameter("token_v2", geo.token)
            parameter("expires", geo.expires)
            parameter("ip", geo.ip)
        }.bodyTextChecked("av01.cdn-access")
        val o = AppJson.parseToJsonElement(body) as? JsonObject
        return o?.str("access_token")?.takeIf { it.isNotBlank() }
            ?: throw AppError.Parse("av01 cdn-access 没有返回 access_token")
    }

    /**
     * 从 master 里挑一档：取 RESOLUTION 最高的那个 URI。
     * master 形态是「#EXT-X-STREAM-INF:...RESOLUTION=WxH」下一行跟 URI，没有别的结构。
     */
    private fun pickVariant(master: String): String? {
        var best: String? = null
        var bestPixels = -1
        var pending: String? = null
        master.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                pending = line
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                val res = pending?.let { Regex("RESOLUTION=(\\d+)x(\\d+)").find(it) }
                val pixels = res?.let { it.groupValues[1].toInt() * it.groupValues[2].toInt() } ?: 0
                if (pixels > bestPixels) {
                    bestPixels = pixels
                    best = line
                }
                pending = null
            }
        }
        return best
    }

    private fun manifestUrl(numericId: String): String = "$base/api/v1/videos/$numericId/manifest/master.m3u8"

    /**
     * 详情页传进来的 id 可能是两种形态：
     *  - `226383`（列表里的数字 id）
     *  - `https://www.av01.media/jp/video/226383/snos-459-ltx?canonical=v2026q4`（页面 URL）
     * 统一收敛成数字 id —— 站点 API 只认数字，传非数字会 400 `Invalid video ID format`。
     */
    private fun normalizeId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.all { it.isDigit() } && trimmed.isNotEmpty()) return trimmed
        val byPath = Regex("/jp/video/(\\d+)").find(trimmed)?.groupValues?.getOrNull(1)
        if (!byPath.isNullOrBlank()) return byPath
        val digits = Regex("(\\d{3,})").find(trimmed)?.groupValues?.getOrNull(1)
        return digits ?: trimmed
    }

    // ---- 封面 -----------------------------------------------------------

    /**
     * 封面 URL（带短期 token）。
     *
     * geo token 只在内存里缓存，且用「取到后 5 分钟」作为刷新界限（站点 TTL 是 600s）——
     * 不写进 KV：token 绑定出口 IP 且 10 分钟过期，落盘只会让下次冷启动拿到一堆 403。
     */
    private suspend fun coverUrl(id: String, size: Int): String? = coverUrl(id, size, geoToken())

    private fun coverUrl(id: String, size: Int, geo: GeoToken?): String? {
        geo ?: return null
        return "$R2_BASE/covers/$id/$size.webp?token_v2=${geo.token}&expires=${geo.expires}&ip=${geo.ip}"
    }

    private suspend fun geoToken(): GeoToken? {
        val cached = geoCache
        if (cached != null && cached.mark.elapsedNow() < GEO_REUSE) return cached

        return runCatching {
            val text = http.get("$GEO_URL?json").bodyTextChecked("av01.geo")
            val o = AppJson.parseToJsonElement(text) as? JsonObject ?: return@runCatching null
            val token = o.str("token_v2") ?: return@runCatching null
            val expires = o.str("expires") ?: return@runCatching null
            val ip = o.str("ip") ?: return@runCatching null
            GeoToken(token = token, expires = expires, ip = ip, mark = TimeSource.Monotonic.markNow())
                .also { geoCache = it }
        }.getOrNull()
    }

    private class GeoToken(
        val token: String,
        val expires: String,
        val ip: String,
        val mark: TimeMark,
    )

    private var geoCache: GeoToken? = null

    // ---- 解析 -----------------------------------------------------------

    private fun parsePage(
        body: String,
        page: Int,
        coverOf: (String) -> String? = { null },
    ): Page<VideoBrief> {
        val root = AppJson.parseToJsonElement(body) as? JsonObject
            ?: throw AppError.Parse("av01 列表响应不是对象")
        root.str("error")?.let { throw AppError.Parse("av01 接口错误：$it") }
        val arr = root["videos"] as? JsonArray ?: JsonArray(emptyList())
        val items = arr.mapNotNull { toBrief(it, cover = null, coverOf = coverOf) }
        val pag = root["pagination"] as? JsonObject
        val totalPages = pag?.int("totalPages") ?: 0
        platformLog("Av01", "list page=$page items=${items.size} totalPages=$totalPages")
        return Page(
            items = items,
            page = page,
            hasMore = if (totalPages > 0) page < totalPages else items.size >= PAGE_SIZE,
        )
    }

    /**
     * 单条 → VideoBrief。列表接口不给图片 URL（`cover` 是布尔），
     * 详情才现拼 token 化地址，所以列表页保持"无图"占位，详情页有图。
     */
    private fun toBrief(
        el: JsonElement,
        cover: String?,
        coverOf: (String) -> String? = { null },
    ): VideoBrief? {
        val o = el as? JsonObject ?: return null
        val id = o.str("id") ?: return null
        val code = o.str("dvd_id") ?: o.str("dmm_id")
        val durationSeconds = o.long("duration")
        return VideoBrief(
            id = id,
            title = translated(o, "title") ?: o.str("title") ?: code.orEmpty(),
            cover = cover ?: coverOf(id),
            code = code,
            duration = durationSeconds?.let { formatDuration(it) },
            rating = null,
            sourceSite = SITE_ID,
        )
    }

    /** 时长是秒（如 8059），换成「2小时14分」更好读。 */
    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分"
            h > 0 -> "${h}小时"
            else -> "${m}分"
        }
    }

    /** 取 `<key>_translations.cn`，没有中文就退回原字段。 */
    private fun translated(o: JsonObject, key: String): String? {
        val table = o["${key}_translations"] as? JsonObject
        return table?.str("cn")?.takeIf { it.isNotBlank() }
            ?: o.str(key)?.takeIf { it.isNotBlank() }
    }

    private fun namedOf(o: JsonObject): String =
        (o["name_translations"] as? JsonObject)?.str("cn")?.takeIf { it.isNotBlank() }
            ?: o.str("name").orEmpty()

    companion object {
        const val SITE_ID = "av01"
        const val DEFAULT_BASE_URL = "https://www.av01.media"

        /** R2 媒体域（封面/预览用），与页面域不同，见类注释。 */
        private const val R2_BASE = "https://files.iw01.xyz"
        private const val GEO_URL = "https://files.iw01.xyz/edge/geo.js"
        private val GEO_REUSE = 5.seconds
        private const val PAGE_SIZE = 20
        private const val LIST_COVER = 400
        private const val DETAIL_COVER = 800
    }
}

// ---- JSON 小工具（本文件私有）--------------------------------------------

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

private fun JsonObject.long(key: String): Long? = str(key)?.toLongOrNull()

private fun JsonObject.objectsOf(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
