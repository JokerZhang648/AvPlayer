package com.javrocket.avplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.javrocket.avplay.core.AndroidAppContext
import com.javrocket.avplay.ui.App

/**
 * Android 入口。整个 Activity 只做一件事：把共享的 App() 渲染出来。
 * 所有业务逻辑都在 commonMain 里 —— 这就是“一套代码两端跑”的落点。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 配置存储与 WebView 取 UA 都要 Context，这里注入一次（见 androidMain/core/KeyValueStore.android.kt）
        AndroidAppContext.init(this)
        setContent { App() }
    }
}

@Composable
fun AndroidAppPreview() {
    App()
}
