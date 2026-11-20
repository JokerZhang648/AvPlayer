package com.javrocket.avplay.net

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import com.javrocket.avplay.core.AndroidAppContext
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 侧平台实现。对应反编译里的 network/PlatformHttpConfig_androidKt。
 * 引擎选 OkHttp：原实现的播放链路也用 OkHttp，行为更容易对齐。
 */
actual fun createHttpEngine(): HttpClientEngine = OkHttp.create()

/**
 * UA 取系统 WebView 的真实标识 —— Phase 3 的关键改动。
 *
 * 原实现为什么这么做（而不是拼一个模板串）：站点会用 UA 判断“是不是真浏览器”。
 * 自己拼的串版本号往往和真机不符，撞上 CF 时会被直接判成 bot，
 * 而 WebView 的 UA 就是这台机器上真实在跑的浏览器内核标识，最不容易被挑出来。
 *
 * 注意：WebView 构造必须在主线程，否则抛 “A WebView method was called on thread”。
 */
@SuppressLint("SetJavaScriptEnabled")
actual suspend fun platformUserAgent(): String {
    val fallback = "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; " +
        "${android.os.Build.MODEL}) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Mobile Safari/537.36"

    val ctx = AndroidAppContext.get() ?: return fallback

    return withContext(Dispatchers.Main) {
        runCatching { WebView(ctx).settings.userAgentString }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: fallback
    }
}

actual fun platformCookieHeader(url: String): String? =
    runCatching { CookieManager.getInstance().getCookie(url) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

actual fun platformLog(tag: String, message: String) {
    // Logcat 单条上限约 4000 字符，超长要分段，否则会被截断丢日志
    val chunk = 3000
    // JVM 单测里 android.util.Log 是未实现的桩，直接调会抛 RuntimeException，
    // 所以统一 runCatching 兜住，测试环境退化成 println，不影响断言
    fun emit(part: String) {
        runCatching { android.util.Log.d(tag, part) }.onFailure { println("[$tag] $part") }
    }
    if (message.length <= chunk) {
        emit(message)
    } else {
        message.chunked(chunk).forEach(::emit)
    }
}
