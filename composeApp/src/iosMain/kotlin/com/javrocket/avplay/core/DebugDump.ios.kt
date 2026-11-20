package com.javrocket.avplay.core

import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.writeToFile
import platform.Foundation.NSFileManager

/**
 * iOS 落盘：写 Documents 目录。
 * 这样可以走 iTunes 文件共享 / Xcode Devices 面板直接导出，不需要越狱或额外权限。
 */
actual fun dumpTextFile(name: String, content: String): String? = runCatching {
    val dir = dumpDirPath()
    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val fileName = if (safe.endsWith(".txt") || safe.endsWith(".html")) safe else "$safe.txt"
    val path = "$dir/$fileName"
    (content as NSString).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding)
    path
}.getOrNull()

actual fun dumpDirPath(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
    val documents = paths.firstOrNull() as? String ?: return "（不可用）"
    val dump = "$documents/dump"
    runCatching { NSFileManager.defaultManager.createDirectoryAtPath(dump, true, null, null) }
    return dump
}
