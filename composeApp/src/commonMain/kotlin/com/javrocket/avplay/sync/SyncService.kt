package com.javrocket.avplay.sync

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.KeyValueStore
import com.javrocket.avplay.core.SettingsStore
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.platform.epochMillis
import com.javrocket.avplay.platform.platformName
import com.javrocket.avplay.store.LibrarySnapshot
import com.javrocket.avplay.store.LibraryStore
import com.javrocket.avplay.store.MergeStats
import io.ktor.client.HttpClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 一次同步的结果，给 UI 直接展示（不要说"同步成功"这种没信息量的话）。 */
data class SyncOutcome(
    val remoteFound: Boolean,
    val merge: MergeStats,
    val mergedBaseUrls: Int,
    val uploaded: Boolean,
    val message: String,
)

/**
 * 双向同步服务。
 *
 * 流程（一条不落，缺一步就会出现"同步完历史互相覆盖"）：
 * 1. `GET` 远端包；404 视为首次同步（不报错）
 * 2. 解析 → `LibraryStore.mergeRemote()`：逐条按 `updatedAt` 取新，收藏取并集
 * 3. `baseUrls`：**本地优先**，远端只补本地没有的站点 —— 域名是这台设备能连通的那个，
 *    被别的设备的旧域名覆盖会直接导致全站打不开
 * 4. `PUT` 合并后的包回远端（这样第二台设备拿到的是合并结果，不会再来一遍覆盖）
 *
 * 密码只存本地 KV，永不进同步包。这一点是硬约束：包放在第三方 WebDAV 上，
 * 等于把账号密码明文扔到别人的服务器。
 */
class SyncService(
    private val kv: KeyValueStore,
    private val library: LibraryStore,
    private val settings: SettingsStore,
    private val http: HttpClient,
) {

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<WebDavConfig> = _config.asStateFlow()

    private val _lastResult = MutableStateFlow(kv.getString(KEY_LAST_MESSAGE) ?: "")
    val lastResult: StateFlow<String> = _lastResult.asStateFlow()

    fun saveConfig(config: WebDavConfig) {
        val normalized = config.copy(
            url = config.url.trim(),
            username = config.username.trim(),
            password = config.password.trim(),
        )
        kv.putString(KEY_URL, normalized.url)
        kv.putString(KEY_USER, normalized.username)
        kv.putString(KEY_PASSWORD, normalized.password)
        _config.value = normalized
        platformLog("Sync", "config saved url=${normalized.normalizedUrl}")
    }

    suspend fun testConnection(): Result<String> =
        WebDavClient(http, _config.value).probe().onFailure { recordFailure(it) }

    /**
     * 执行同步。所有失败都要写进 `lastResult` —— 用户下次打开同步页要能看到"上次为什么失败"。
     */
    suspend fun sync(): Result<SyncOutcome> {
        val config = _config.value
        if (!config.isValid()) {
            val err = AppError.Parse("请先填写 WebDAV 地址")
            recordFailure(err)
            return Result.failure(err)
        }

        val client = WebDavClient(http, config)

        val remoteRaw = client.download().getOrElse { e ->
            recordFailure(e)
            return Result.failure(e)
        }

        var remote: SyncBundle? = null
        if (remoteRaw != null) {
            remote = runCatching {
                AppJson.decodeFromString(SyncBundle.serializer(), remoteRaw)
            }.onFailure { platformLog("Sync", "remote parse failed: ${it.message}") }.getOrNull()
            if (remote == null) {
                // 远端文件坏了（手改、半截上传、旧格式）—— 不能静默覆盖，先报出来
                val err = AppError.Parse("远端同步包解析失败，已停止（避免覆盖远端数据）")
                recordFailure(err)
                return Result.failure(err)
            }
            if (remote.schema > SyncBundle.SCHEMA_VERSION) {
                val err = AppError.Parse("远端包版本（${remote.schema}）比本机新，请升级 App")
                recordFailure(err)
                return Result.failure(err)
            }
        }

        // 1) 合并历史/收藏
        val merge = remote?.let { library.mergeRemote(it.library) } ?: MergeStats()

        // 2) 合并域名：本地优先，远端只补空缺
        var adoptedUrls = 0
        remote?.baseUrls?.forEach { (siteId, url) ->
            val local = settings.baseUrls.value[siteId]
            if (local.isNullOrBlank() && url.isNotBlank()) {
                settings.putBaseUrl(siteId, url)
                adoptedUrls++
            }
        }

        // 3) 上传合并结果（远端没有时必须上传，否则第二台设备永远拿不到数据）
        val snapshot = library.snapshot()
        val bundle = SyncBundle(
            exportedAt = epochMillis(),
            device = platformName(),
            library = snapshot,
            baseUrls = settings.baseUrls.value,
        )
        val body = AppJson.encodeToString(SyncBundle.serializer(), bundle)
        client.upload(body).getOrElse { e ->
            recordFailure(e)
            return Result.failure(e)
        }

        val message = buildString {
            if (remote == null) append("首次同步，已上传本机数据")
            else append("历史合并 ${merge.historyAdopted} 条 · 收藏合并 ${merge.favoritesAdded} 条")
            if (adoptedUrls > 0) append(" · 域名补全 $adoptedUrls 项")
            append("（共 ${snapshot.history.size} 条历史 / ${snapshot.favorites.size} 条收藏）")
        }
        kv.putString(KEY_LAST_AT, epochMillis().toString())
        kv.putString(KEY_LAST_MESSAGE, message)
        _lastResult.value = message
        platformLog("Sync", "done remote=${remote != null} $message")

        return Result.success(
            SyncOutcome(
                remoteFound = remote != null,
                merge = merge,
                mergedBaseUrls = adoptedUrls,
                uploaded = true,
                message = message,
            ),
        )
    }

    /** 把失败也记进 lastResult —— 否则用户只看到"同步失败"，不知道该改哪里。 */
    private fun recordFailure(e: Throwable) {
        val msg = "同步失败：${e.message ?: e::class.simpleName}"
        kv.putString(KEY_LAST_MESSAGE, msg)
        _lastResult.value = msg
        platformLog("Sync", msg)
    }

    private fun loadConfig(): WebDavConfig = WebDavConfig(
        url = kv.getString(KEY_URL).orEmpty(),
        username = kv.getString(KEY_USER).orEmpty(),
        password = kv.getString(KEY_PASSWORD).orEmpty(),
    )

    companion object {
        private const val KEY_URL = "sync.webdav.url"
        private const val KEY_USER = "sync.webdav.user"
        private const val KEY_PASSWORD = "sync.webdav.password"
        private const val KEY_LAST_AT = "sync.last_at"
        private const val KEY_LAST_MESSAGE = "sync.last_message"

        /** 上次同步时间（毫秒），0 表示从未同步。 */
        fun lastSyncAt(kv: KeyValueStore): Long = kv.getString(KEY_LAST_AT)?.toLongOrNull() ?: 0L
    }
}
