package com.javrocket.avplay.platform

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun platformName(): String =
    "iOS " + platform.UIKit.UIDevice.currentDevice.systemVersion

actual fun setImmersiveFullscreen(enabled: Boolean) {
    // iOS 全屏由 AVPlayerViewController 自己处理，这里暂无操作
}

actual fun setLandscapePlaybackFullscreen(enabled: Boolean) = Unit
