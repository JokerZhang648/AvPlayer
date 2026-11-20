package com.javrocket.avplay.core

import platform.Foundation.NSUserDefaults

/**
 * iOS 侧 KV：NSUserDefaults。
 *
 * 语义与 Android 的 SharedPreferences 一致（同步读、写入由系统异步 flush），
 * 这样 commonMain 的 SettingsStore 不需要为两端写不同逻辑。
 */
actual fun createKeyValueStore(): KeyValueStore =
    UserDefaultsKeyValueStore(NSUserDefaults.standardUserDefaults)

class UserDefaultsKeyValueStore(
    private val defaults: NSUserDefaults,
) : KeyValueStore {

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) {
        defaults.setObject(value, forKey = key)
    }

    override fun remove(key: String) {
        defaults.removeObjectForKey(key)
    }

    override fun keys(): Set<String> {
        val dict = defaults.dictionaryRepresentation()
        return dict.keys.mapNotNull { it as? String }.toSet()
    }
}
