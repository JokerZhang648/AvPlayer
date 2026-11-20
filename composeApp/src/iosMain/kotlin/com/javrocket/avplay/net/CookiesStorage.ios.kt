package com.javrocket.avplay.net

import io.ktor.client.plugins.cookies.CookiesStorage

/**
 * iOS 侧返回 null —— 刻意不接管，交给 NSURLSession / Darwin 引擎。
 *
 * 原因：iOS 11 之后 WKWebView 的 `WKWebsiteDataStore.default()` 与
 * `NSHTTPCookieStorage.sharedHTTPCookieStorage` 已经是同一份 cookie 容器，
 * 引擎自己读写就能拿到 WebView 里过完 CF 的 cf_clearance。
 * 如果这里再包一层自定义 CookiesStorage，会出现“同一 cookie 存两份、过期时间不一致”的怪问题。
 */
actual fun platformCookieStorage(): CookiesStorage? = null
