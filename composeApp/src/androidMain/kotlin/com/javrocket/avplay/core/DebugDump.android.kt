package com.javrocket.avplay.core

import android.os.Environment
import java.io.File

/**
 * Android 落盘：写 **外部私有目录**（`/sdcard/Android/data/<pkg>/files/dump/`）。
 *
 * 为什么不写内部 `filesDir`：内部目录在未 root 的设备上 `adb pull` 会被权限挡住，
 * 而外部私有目录在 debug 包上可以直接 adb 拉取 —— 调试效率高很多。
 * 拿不到外部目录时退回内部目录，保证功能不丢。
 */
actual fun dumpTextFile(name: String, content: String): String? = runCatching {
    val ctx = AndroidAppContext.get() ?: return null
    val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val dir = File(base, "dump").apply { mkdirs() }
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val file = File(dir, if (safe.endsWith(".txt") || safe.endsWith(".html")) safe else "$safe.txt")
    file.writeText(content)
    file.absolutePath
}.getOrNull()

actual fun dumpDirPath(): String = runCatching {
    val ctx = AndroidAppContext.get() ?: return "（未初始化）"
    (ctx.getExternalFilesDir(null) ?: ctx.filesDir).resolve("dump").absolutePath
}.getOrDefault("（不可用）")
