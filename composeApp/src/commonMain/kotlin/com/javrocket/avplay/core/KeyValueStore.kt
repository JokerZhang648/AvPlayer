package com.javrocket.avplay.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 极小 KV 存储 —— 替代原实现的 DataStore Preferences。
 *
 * 为什么不直接上 androidx.datastore：DataStore 的 KMP 版依赖 okio + 协程文件 IO，
 * 在本工程里只用来存十来个字符串（站点域名、UA、开关），收益极低、依赖面却很大。
 * 这里用平台自带的 SharedPreferences / NSUserDefaults —— 都是系统级、无新依赖、
 * 行为等价（同步读、异步写由调用方决定）。
 *
 * 如果将来要存结构化大数据（播放历史），再换 Room，接口不变。
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun keys(): Set<String>
}

expect fun createKeyValueStore(): KeyValueStore

/**
 * 内存实现：给单测、以及“拿不到 Android Context / 不想落盘”的场景兜底用。
 * 反编译原实现在 Application 初始化失败时也是退化成内存存储，保证不崩。
 */
class InMemoryKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {
    private val map = LinkedHashMap<String, String>(initial)

    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(): Set<String> = map.keys.toSet()
}

/** 配置 key 前缀，集中在一处，避免和业务 key 撞车。 */
object SettingsKeys {
    private const val BASE_URL_PREFIX = "cfg.base_url."
    const val USER_AGENT = "cfg.user_agent"
    const val USING_SAVED_UA = "cfg.user_agent_from_webview"

    fun baseUrl(siteId: String) = BASE_URL_PREFIX + siteId
}

/**
 * 落盘版 SettingsStore —— Phase 3 起替换 InMemorySettingsStore。
 *
 * 读取策略：构造时一次性把已知站点的域名读进 StateFlow，之后写操作双写（内存 + 磁盘）。
 * 这样 UI 侧仍然是“同步读 StateFlow”，不需要改成 suspend。
 */
class PersistentSettingsStore(
    private val store: KeyValueStore = createKeyValueStore(),
    private val defaultBaseUrls: Map<String, String> = emptyMap(),
) : SettingsStore {

    private val _baseUrls = MutableStateFlow(loadBaseUrls())
    override val baseUrls: StateFlow<Map<String, String>> = _baseUrls.asStateFlow()

    private val _userAgent = MutableStateFlow(store.getString(SettingsKeys.USER_AGENT))
    override val userAgent: StateFlow<String?> = _userAgent.asStateFlow()

    private fun loadBaseUrls(): Map<String, String> {
        val loaded = LinkedHashMap<String, String>()
        store.keys()
            .filter { it.startsWith("cfg.base_url.") }
            .forEach { key ->
                val siteId = key.removePrefix("cfg.base_url.")
                store.getString(key)?.takeIf { it.isNotBlank() }?.let { loaded[siteId] = it }
            }
        // 磁盘上没有的站点用默认值补齐，UI 才能显示全部站点
        defaultBaseUrls.forEach { (siteId, url) -> loaded.putIfAbsent(siteId, url) }
        return loaded
    }

    override suspend fun putBaseUrl(siteId: String, url: String) {
        val normalized = url.trim().trimEnd('/')
        if (normalized.isBlank()) return
        _baseUrls.update { it + (siteId to normalized) }
        store.putString(SettingsKeys.baseUrl(siteId), normalized)
    }

    override suspend fun putUserAgent(ua: String) {
        _userAgent.value = ua
        store.putString(SettingsKeys.USER_AGENT, ua)
    }

    /** 清掉某个站点的自定义域名，回落到代码里的默认值。 */
    suspend fun resetBaseUrl(siteId: String) {
        store.remove(SettingsKeys.baseUrl(siteId))
        val fallback = defaultBaseUrls[siteId]
        _baseUrls.update { if (fallback == null) it - siteId else it + (siteId to fallback) }
    }
}
