package com.javrocket.avplay.net

import android.webkit.CookieManager
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.Url

/**
 * Android 的 Cookie 桥：直接读写 `android.webkit.CookieManager`。
 *
 * 为什么能共享：WebView 与 CookieManager 在同一进程内是同一份存储，
 * WebView 里过完 CF 写下的 cf_clearance，这里下一次 get 就能读到 —— 不需要解析 Set-Cookie、不需要定时同步。
 */
actual fun platformCookieStorage(): CookiesStorage? = WebViewCookieStorage()

class WebViewCookieStorage : CookiesStorage {

    override suspend fun get(requestUrl: Url): List<Cookie> {
        // CookieManager 返回的是 "a=b; c=d" 这种原始串；单条解析失败不能影响整批
        val raw = runCatching { CookieManager.getInstance().getCookie(requestUrl.toString()) }
            .getOrNull()
            .orEmpty()
        if (raw.isBlank()) return emptyList()

        return raw.split(';').mapNotNull { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) return@mapNotNull null
            val name = trimmed.substring(0, eq).trim()
            val value = trimmed.substring(eq + 1).trim()
            runCatching {
                Cookie(
                    name = name,
                    value = value,
                    domain = requestUrl.host,
                    path = "/",
                )
            }.getOrNull()
        }
    }

    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.setCookie(requestUrl.toString(), cookie.toString())
            // 不 flush 的话，进程被杀时这次写入可能还没落盘
            cm.flush()
        }
    }

    override fun close() = Unit
}
