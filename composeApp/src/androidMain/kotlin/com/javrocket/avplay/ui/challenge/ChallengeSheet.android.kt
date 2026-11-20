package com.javrocket.avplay.ui.challenge

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ChallengeSheet(
    visible: Boolean,
    initialUrl: String,
    onDismiss: () -> Unit,
    onVerified: () -> Unit,
    modifier: Modifier,
) {
    if (!visible) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("人机验证", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    // 自动检测偶尔会漏（比如验证后页面停在原地的变体），留一个手动兜底
                    TextButton(onClick = {
                        runCatching { CookieManager.getInstance().flush() }
                        onVerified()
                    }) { Text("已通过") }
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                // CF 挑战页有时会混用 http 资源，不允许混合内容会白屏
                                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                // 用系统 WebView 的真实 UA，不用 App 自己的 UA（后者会被识别成非浏览器）
                                userAgentString = userAgentString
                            }
                            val cm = CookieManager.getInstance()
                            cm.setAcceptCookie(true)
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    val cookies = url?.let {
                                        runCatching { CookieManager.getInstance().getCookie(it) }.getOrNull()
                                    }.orEmpty()
                                    // cf_clearance 是判定的唯一可靠信号：页面加载完不一定过，拿到票据才是过
                                    if (cookies.contains("cf_clearance")) {
                                        runCatching { CookieManager.getInstance().flush() }
                                        onVerified()
                                    }
                                }
                            }
                            loadUrl(initialUrl)
                        }
                    },
                )
            }
        }
    }
}
