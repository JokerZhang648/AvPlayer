package com.javrocket.avplay.net

import io.ktor.client.plugins.cookies.CookiesStorage

/**
 * Cookie 桥 —— 反编译里最关键的一块“跨进程状态共享”，也是 CF 挑战能不能过的分水岭。
 *
 * 问题本质：CF 的 `cf_clearance` 是 **HttpOnly** cookie，只存在于 WebView 的 Cookie 容器里。
 * HTTP 客户端如果不读这个容器，就会出现“用户在 WebView 里明明过了验证，App 里还是 403”。
 *
 * 方案：把 Ktor 的 CookiesStorage 直接接到平台 WebView 的 Cookie 容器上 ——
 * 读（get）时从容器取，写（addCookie）时写回容器，天然双向同步，不需要额外复制。
 *
 * 返回 null 表示“交给引擎自己管”：
 * iOS 的 NSURLSession 与 WKWebView 共享 NSHTTPCookieStorage，引擎自带的行为已经正确，
 * 再挂一层存储反而会和系统 cookie 打架（这是踩过的坑，写在这里免得以后“优化”回去）。
 */
expect fun platformCookieStorage(): CookiesStorage?
