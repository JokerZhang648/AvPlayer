package com.javrocket.avplay.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 人机验证页 —— 两端都是“真的网页”，不是伪造的 UI。
 *
 * 为什么必须用真 WebView：CF 的挑战是 JS 计算 + 浏览器指纹，任何 HttpClient 复刻都不可能过。
 * 用户在页面里点完验证后，浏览器把 cf_clearance 写进 Cookie 容器，
 * 我们的 HTTP 层通过 CookiesStorage 立刻就能读到（见 net/CookiesStorage.kt）。
 *
 * 两端实现差异：
 *   Android → android.webkit.WebView（Cookie 与 CookieManager 天然同源）
 *   iOS     → WKWebView（Cookie 与 NSHTTPCookieStorage 天然同源）
 */
@Composable
expect fun ChallengeSheet(
    visible: Boolean,
    initialUrl: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    modifier: Modifier = Modifier,
)
