package com.javrocket.avplay.net

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.Timeouts
import com.javrocket.avplay.service.CloudflareUtils
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import com.javrocket.avplay.parser.originOf
import kotlinx.serialization.json.Json

/**
 * 平台差异最小的三件事，全部走 expect/actual：
 * 1) HTTP 引擎（Android=OkHttp，iOS=Darwin）
 * 2) User-Agent（Android 从系统取，iOS 从 WKWebView 取）
 * 3) 日志出口（Android 用 Logcat/文件，iOS 用 NSLog/文件）
 *
 * 这三个是“共享代码能跑在两端”的最小平台契约。
 */
expect fun createHttpEngine(): HttpClientEngine
expect suspend fun platformUserAgent(): String
expect fun platformLog(tag: String, message: String)

/** WebView / 系统 Cookie 容器里该 URL 的 `Cookie` 请求头（ExoPlayer 不经过 Ktor，需显式带上）。 */
expect fun platformCookieHeader(url: String): String?

/** 同源 HLS / MP4 防盗链常用头：Origin、Referer、UA，以及与 Ktor 一致的 Cookie。 */
suspend fun playbackRequestHeaders(sourcePageUrl: String): Map<String, String> {
    val headers = LinkedHashMap<String, String>()
    originOf(sourcePageUrl).takeIf { it.isNotBlank() }?.let { headers["Origin"] = it }
    headers["Referer"] = sourcePageUrl
    runCatching { platformUserAgent() }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.let { headers["User-Agent"] = it }
    platformCookieHeader(sourcePageUrl)
        ?.takeIf { it.isNotBlank() }
        ?.let { headers["Cookie"] = it }
    return headers
}

val AppJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * 统一的 HttpClient 工厂 —— 对应反编译里每个 Service 的 httpClient$lambda$0。
 *
 * 装的东西与原实现一一对应：超时、UA/Accept 默认头、日志、JSON 反序列化。
 * Cookie（WebView 共享）与 HTML 解析插件在 Phase 3 再补进来，见 docs/MIGRATION.md。
 */
fun createHttpClient(
    engine: HttpClientEngine,
    userAgent: String,
    accept: String = "application/json, text/plain, */*",
    /**
     * 站点的 origin，例如 `https://huangguoai.com`。
     *
     * 传了就会补 `Origin` 与 `Referer` 两个头 —— 这是原实现
     * HuangGuoService.lambda$1$2$0 里的固定写法（`Origin: siteUrl`、
     * `Referer: siteUrl + "/"`）。缺这两个头时黄果后端会直接回
     * `{"code":1}`，客户端只能显示「接口返回错误（1）」，很容易误判成解析问题。
     */
    siteUrl: String? = null,
    /**
     * Cookie 存储。默认接平台 WebView 的 Cookie 容器 —— 这是 CF 挑战能过的前提，
     * 详见 net/CookiesStorage.kt 的说明。传 null 则完全交给引擎。
     */
    cookieStorage: CookiesStorage? = platformCookieStorage(),
): HttpClient = HttpClient(engine) {
    expectSuccess = false

    // Cookie 必须在 ContentNegotiation 之前装：CF 的 403 响应体也要先过 cookie 再判
    cookieStorage?.let { storage -> install(HttpCookies) { this.storage = storage } }

    install(ContentNegotiation) {
        json(AppJson)
    }

    install(Logging) {
        logger = object : Logger {
            override fun log(message: String) = platformLog("HTTP", message)
        }
        level = LogLevel.INFO
    }

    install(HttpTimeout) {
        requestTimeoutMillis = Timeouts.LONG
        connectTimeoutMillis = Timeouts.QUICK
        socketTimeoutMillis = Timeouts.STANDARD
    }

    defaultRequest {
        headers.append(HttpHeaders.UserAgent, userAgent)
        headers.append(HttpHeaders.Accept, accept)
        headers.append(HttpHeaders.AcceptLanguage, "zh-CN,zh;q=0.9,en;q=0.8")
        siteUrl?.trimEnd('/')?.let { origin ->
            headers.append(HttpHeaders.Origin, origin)
            // Ktor 的 HttpHeaders 没有 Referer 常量，这里用字面量
            headers.append("Referer", "$origin/")
        }
    }
}

/**
 * 取响应正文，并且**先过 Cloudflare 判定再返回**。
 *
 * 这是原实现里一个容易忽略但很重要的约定：所有站点适配器都是
 * “拿到 body → 先 isChallenge → 再交给解析器”。否则挑战页会被当成业务页解析，
 * 结果是“列表为空但没有任何报错”，极难排查。
 */
suspend fun HttpResponse.bodyTextChecked(operation: String): String {
    val text = bodyAsText()
    // 排查用：只打前 160 字，足够看清是业务码还是挑战页，又不会把整页塞进 logcat
    platformLog(
        "HTTP",
        "resp op=$operation status=${status.value} len=${text.length} head=${text.take(160).replace('\n', ' ')}",
    )

    if (status.value >= 400 && !CloudflareUtils.isChallenge(text)) {
        throw AppError.ofHttpStatus(status.value)
    }
    CloudflareUtils.challengeReason(text)?.let { reason ->
        // Ktor 3 的 HttpResponse 只暴露 call，URL 要从 call.request 取
        val url = call.request.url
        platformLog("CF", "challenge detected op=$operation reason=$reason url=$url")
        throw AppError.CloudflareChallenge(url.toString())
    }
    return text
}
