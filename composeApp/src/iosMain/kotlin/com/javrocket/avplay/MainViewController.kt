package com.javrocket.avplay

import androidx.compose.ui.window.ComposeUIViewController
import com.javrocket.avplay.ui.App
import platform.UIKit.UIViewController

/**
 * iOS 入口。Swift 侧只调这一个函数（见 iosApp/iosApp/ContentView.swift），
 * 界面与业务全部来自 commonMain。
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
