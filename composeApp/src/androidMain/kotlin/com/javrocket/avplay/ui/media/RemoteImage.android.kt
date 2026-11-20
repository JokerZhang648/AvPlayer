package com.javrocket.avplay.ui.media

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android 解码。用 `inJustDecodeBounds` 先读尺寸、按目标宽度降采样，
 * 避免把 4000px 的原图整张塞进内存（列表里几十张会把堆打爆）。
 */
actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap? {
    if (bytes.isEmpty()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sample = 1
        val target = 400 // 列表缩略图宽度上限（dp≈110，2x~3x 屏也够）
        while (bounds.outWidth / sample > target * 2) sample *= 2

        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    }.getOrNull()
}
