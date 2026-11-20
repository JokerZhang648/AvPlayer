package com.javrocket.avplay.ui

import androidx.compose.runtime.Composable

/** 系统返回键：全屏播放时优先退出全屏，而不是直接关播放页。 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)