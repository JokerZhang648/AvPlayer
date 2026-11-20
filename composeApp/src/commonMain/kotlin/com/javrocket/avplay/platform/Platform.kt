package com.javrocket.avplay.platform

/** 当前时间（毫秒）。两端实现不同，所以走 expect/actual。 */
expect fun epochMillis(): Long

/** 平台名，用于日志与诊断。 */
expect fun platformName(): String
