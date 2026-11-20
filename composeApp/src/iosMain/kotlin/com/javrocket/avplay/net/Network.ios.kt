package com.javrocket.avplay.net

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSLog

/**
 * iOS 侧平台实现。对应反编译里 KMP 的 iOS actual 分支（原包内已被编进 Kotlin/Native）。
 * 引擎必须用 Darwin：它走 NSURLSession，天然共享系统 Cookie 存储。
 */
actual fun createHttpEngine(): HttpClientEngine = Darwin.create()

/**
 * TODO(Phase 3): 换成 WKWebView 的真实 UA（异步取一次并缓存）。
 * 原因与 Android 相同：UA 必须稳定，且要与 WebView 会话保持一致，
 * 否则 CF 的 cf_clearance 票据和 UA 绑不上。
 */
actual suspend fun platformUserAgent(): String =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

actual fun platformLog(tag: String, message: String) {
    NSLog("%@: %@", tag, message)
}
