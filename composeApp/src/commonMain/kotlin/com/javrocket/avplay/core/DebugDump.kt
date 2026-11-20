package com.javrocket.avplay.core

/**
 * 调试落盘 —— 把原始响应/日志写成文件，供 `adb pull` 或诊断页导出。
 *
 * 为什么需要它：站点改版时最有效的信息是“服务器到底给了什么 HTML”，
 * 而 logcat 一条只有 4 KB 且会被滚掉；把原文落盘再拉回来，比反复加日志快一个数量级。
 * Phase 6 的诊断页“导出日志”也复用这个能力。
 */
expect fun dumpTextFile(name: String, content: String): String?

/** 当前落盘目录（诊断页要显示给用户看）。 */
expect fun dumpDirPath(): String
