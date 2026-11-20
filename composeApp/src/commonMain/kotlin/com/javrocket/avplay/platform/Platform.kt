package com.javrocket.avplay.platform

/** 当前时间（毫秒）。两端实现不同，所以走 expect/actual。 */
expect fun epochMillis(): Long

/** 平台名，用于日志与诊断。 */
expect fun platformName(): String

/** 进入/退出沉浸式全屏（竖屏播放页用）。 */
expect fun setImmersiveFullscreen(enabled: Boolean)

/** 横屏沉浸式全屏（站内 16:9 播放器点「全屏」用）。 */
expect fun setLandscapePlaybackFullscreen(enabled: Boolean)
