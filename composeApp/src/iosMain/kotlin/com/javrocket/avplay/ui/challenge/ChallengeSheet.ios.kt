package com.javrocket.avplay.ui.challenge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.interop.UIKitView
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/**
 * iOS 侧用 WKWebView + 自己实现导航代理：
 * 加载完成后再问一次 document.title，如果是 CF 的标题就继续等，
 * 否则认为已经落到目标页（这一步比 Android 的 cookie 判定更保守，因为
 * WKWebView 的 cookie 要在 NSHTTPCookieStore 里异步取，didFinish 时可能还没落盘）。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun ChallengeSheet(
    visible: Boolean,
    initialUrl: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    modifier: Modifier,
) {
    if (!visible) return

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("人机验证", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onVerified) { Text("已通过") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }

            UIKitView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    val config = WKWebViewConfiguration()
                    WKWebView(
                        frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                        configuration = config,
                    ).apply {
                        navigationDelegate = object : NSObject(), WKNavigationDelegateProtocol {
                            override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
                                webView.evaluateJavaScript("document.title") { result, _ ->
                                    val title = (result as? String).orEmpty().lowercase()
                                    val stillChallenge =
                                        title.contains("just a moment") ||
                                            title.contains("attention required") ||
                                            title.contains("请稍候")
                                    if (!stillChallenge) onVerified()
                                }
                            }
                        }
                        NSURL.URLWithString(initialUrl)?.let { nsUrl ->
                            loadRequest(NSURLRequest.requestWithURL(nsUrl))
                        }
                    }
                },
            )
        }
    }
}
