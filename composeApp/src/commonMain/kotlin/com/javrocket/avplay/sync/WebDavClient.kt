package com.javrocket.avplay.sync

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.core.runServiceOperation
import com.javrocket.avplay.net.bodyTextChecked
import com.javrocket.avplay.net.platformLog
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.util.encodeBase64

/**
 * 最小 WebDAV 客户端 —— 只用三个动词，够同步一个 JSON 文件。
 *
 * | 目的 | 方法 | 期望状态码 |
 * |------|------|-----------|
 * | 探测文件是否存在 | `PROPFIND`（`Depth: 0`） | 207 存在 / 404 不存在 |
 * | 下载 | `GET` | 200 / 404（首次同步，正常） |
 * | 上传 | `PUT` | 201 或 204 |
 *
 * 为什么不引第三方 WebDAV 库：KMP 下没有一个能同时覆盖 Android/iOS 的成熟实现，
 * 而这里只需要 PUT/GET/PROPFIND 三个请求，手写比引库更可控（认证方式也一眼能看清）。
 *
 * 实测兼容目标：Nextcloud / ownCloud（`/remote.php/dav/files/<user>/…`）、群晖 WebDAV、
 * 坚果云（`https://dav.jianguoyun.com/dav/`）。这些都用 Basic Auth，且都按目录 207 响应。
 */
class WebDavClient(
    private val http: HttpClient,
    private val config: WebDavConfig,
) {

    private fun authHeader(): String? {
        if (config.username.isBlank() && config.password.isBlank()) return null
        val raw = "${config.username}:${config.password}"
        return "Basic " + raw.encodeToByteArray().encodeBase64()
    }

    private fun io.ktor.client.request.HttpRequestBuilder.withAuth() {
        authHeader()?.let { header(HttpHeaders.Authorization, it) }
    }

    /** 目录探活：能 PROPFIND 到 207 说明地址/账号/密码都对。 */
    suspend fun probe(): Result<String> = runServiceOperation("sync.probe", Timeouts.STANDARD) {
        if (!config.isValid()) throw AppError.Parse("WebDAV 地址必须是 http(s) 开头")
        val resp = http.request(config.dirUrl) {
            method = HttpMethod("PROPFIND")
            header("Depth", "0")
            withAuth()
        }
        val code = resp.status.value
        // 有的服务端对目录 PROPFIND 回 200/301/302，都算"地址可达"
        if (code !in listOf(200, 207, 301, 302)) {
            throw AppError.Http(code, "WebDAV 探活失败（$code）")
        }
        platformLog("Sync", "probe dir=${config.dirUrl} code=$code")
        "连接正常（$code）"
    }

    /**
     * 下载同步包；文件不存在返回 null（首次同步是正常路径，不是错误）。
     */
    suspend fun download(): Result<String?> = runServiceOperation("sync.download", Timeouts.LONG) {
        if (!config.isValid()) throw AppError.Parse("WebDAV 地址必须是 http(s) 开头")
        val resp = http.get(config.fileUrl) { withAuth() }
        val code = resp.status.value
        if (code == 404) {
            platformLog("Sync", "download 404 (first sync) file=${config.fileUrl}")
            return@runServiceOperation null
        }
        val text = resp.bodyTextChecked("sync.download")
        platformLog("Sync", "download code=$code len=${text.length}")
        text
    }

    /** 上传同步包。PUT 成功是 201（新建）或 204（覆盖）。 */
    suspend fun upload(body: String): Result<Unit> = runServiceOperation("sync.upload", Timeouts.LONG) {
        if (!config.isValid()) throw AppError.Parse("WebDAV 地址必须是 http(s) 开头")
        val resp = http.request(config.fileUrl) {
            method = HttpMethod.Put
            withAuth()
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(TextContent(body, ContentType.Application.Json))
        }
        val code = resp.status.value
        if (code !in listOf(200, 201, 204)) {
            throw AppError.Http(code, "WebDAV 上传失败（$code）")
        }
        platformLog("Sync", "upload code=$code len=${body.length}")
    }
}
