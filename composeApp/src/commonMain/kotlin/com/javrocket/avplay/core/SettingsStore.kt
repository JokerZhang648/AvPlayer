package com.javrocket.avplay.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 配置存储抽象 —— 对应反编译里的 store/ConfigStore + store/LocalUrlStore。
 *
 * 原实现把每个站点入口域名单独存成 DataStore 的一个 key（javdb_url / huangguo_url / …），
 * 这里保留同一套语义，但先给一个内存实现，Phase 4 再换成 DataStore Preferences（KMP）。
 *
 * 为什么域名要可配置：这类站点频繁换域名/被墙，硬编码等于随时全站不可用。
 */
interface SettingsStore {
    val baseUrls: StateFlow<Map<String, String>>
    val userAgent: StateFlow<String?>

    fun baseUrl(siteId: String, fallback: String): String =
        baseUrls.value[siteId]?.takeIf { it.isNotBlank() } ?: fallback

    suspend fun putBaseUrl(siteId: String, url: String)
    suspend fun putUserAgent(ua: String)
}

class InMemorySettingsStore(
    initial: Map<String, String> = emptyMap(),
) : SettingsStore {

    private val _baseUrls = MutableStateFlow(initial)
    override val baseUrls: StateFlow<Map<String, String>> = _baseUrls.asStateFlow()

    private val _userAgent = MutableStateFlow<String?>(null)
    override val userAgent: StateFlow<String?> = _userAgent.asStateFlow()

    override suspend fun putBaseUrl(siteId: String, url: String) {
        _baseUrls.update { it + (siteId to url) }
    }

    override suspend fun putUserAgent(ua: String) {
        _userAgent.value = ua
    }
}

/** 站点常量。对应反编译里散落各处的 baseUrl 默认值。 */
object SiteIds {
    const val HUANGGUO = "huangguo"
    const val JAVDB = "javdb"
    const val MISSAV = "missav"
    const val XCHINA = "xchina"
    const val JAVLIBRARY = "javlibrary"
    const val AVDB = "avdb"
    const val AV01 = "av01"
}
