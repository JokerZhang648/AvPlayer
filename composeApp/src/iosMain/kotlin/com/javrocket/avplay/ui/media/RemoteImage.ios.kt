package com.javrocket.avplay.ui.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage

/**
 * iOS 解码：skia 直接吃字节。
 *
 * 不走 UIImage 是因为 UIImage 只能给 UIKit 用，而 Compose 的 Image 要的是 SNP/ImageBitmap。
 * skia 的解码器在 Compose MP 的 iOS 目标里本来就带着（渲染后端就是 skia），不增加体积。
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
