package com.javrocket.avplay.platform

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun platformName(): String = "Android ${android.os.Build.VERSION.SDK_INT}"
