package com.javrocket.avplay.core

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import java.lang.ref.WeakReference

/**
 * Android 侧 KV：SharedPreferences。
 *
 * Context 从 [AndroidAppContext] 取 —— 由 MainActivity/Application 在启动时注入。
 * 没注入时（单测、Preview）回落到内存实现，保证不抛异常。
 */
actual fun createKeyValueStore(): KeyValueStore {
    val ctx = AndroidAppContext.get() ?: return InMemoryKeyValueStore()
    return SharedPrefsKeyValueStore(ctx)
}

class SharedPrefsKeyValueStore(
    context: Context,
    name: String = "avplay_settings",
) : KeyValueStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = runCatching { prefs.getString(key, null) }.getOrNull()

    override fun putString(key: String, value: String) {
        runCatching { prefs.edit().putString(key, value).apply() }
    }

    override fun remove(key: String) {
        runCatching { prefs.edit().remove(key).apply() }
    }

    override fun keys(): Set<String> = runCatching { prefs.all.keys.toSet() }.getOrDefault(emptySet())
}

/**
 * 全局 Application Context 持有者。
 *
 * 为什么不用 ContentProvider 自动初始化：本工程只有一个入口 Activity，
 * 显式注入比隐式初始化更好排查（也避免 Compose Preview 里被意外触发）。
 */
object AndroidAppContext {
    @Volatile
    private var context: Context? = null

    @Volatile
    private var activityRef: WeakReference<Activity>? = null

    fun init(context: Context) {
        this.context = context.applicationContext
        if (context is Activity) bindActivity(context)
    }

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /** 需调 Activity API（横竖屏、沉浸式）时用；`get()` 是 ApplicationContext。 */
    fun getActivity(): Activity? = activityRef?.get()

    fun get(): Context? = context
}
