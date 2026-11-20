package com.javrocket.avplay.store

import com.javrocket.avplay.core.KeyValueStore
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.platform.epochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * 一条观看记录。
 *
 * 关键字段是 `positionMs` + `episodeIndex`：续播必须同时记住"看到哪一集、这一集看到哪"，
 * 只记位置不记集数会导致多集视频每次都从第 1 集开头放。
 */
@Serializable
data class HistoryEntry(
    val siteId: String,
    val videoId: String,
    val title: String = "",
    val cover: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val episodeIndex: Int = 1,
    val updatedAt: Long = 0,
) {
    /** 已经看了 95% 以上就当看完，续播时从开头放。 */
    fun resumePositionMs(): Long =
        if (durationMs > 0 && positionMs >= durationMs * 0.95) 0 else positionMs

    val progressRatio: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** 续播用：本集已看完且还有下一集时，从下集 0 秒起播。 */
    fun resumeEpisodeAndPosition(maxEpisode: Int): Pair<Int, Long> {
        val max = maxEpisode.coerceAtLeast(1)
        var ep = episodeIndex.coerceIn(1, max)
        val finished = durationMs > 0 && positionMs >= durationMs * 0.95
        if (finished && ep < max) {
            ep += 1
            return ep to 0L
        }
        return ep to resumePositionMs()
    }
}

/** 一次同步的合并结果（UI 用它告诉用户"本次从远端拿到几条"）。 */
data class MergeStats(
    val historyAdopted: Int = 0,
    val favoritesAdded: Int = 0,
)

/** 可序列化的库快照 —— 就是同步包里的 payload。 */
@Serializable
data class LibrarySnapshot(
    val history: List<HistoryEntry> = emptyList(),
    val favorites: List<HistoryEntry> = emptyList(),
)

/**
 * 历史 / 收藏 —— 替代反编译里的 Room（HistoryEntity / FavoriteEntity）。
 *
 * 为什么不用 Room：Room 的 KMP 版要引 4 个 artifact + SQLite 驱动 + Gradle 插件，
 * 而这里的数据量是"几十条记录、几个字段"，用 JSON 存进已有的 KV 里更轻，也不会因为
 * schema 升级（新增一个字段）就要写迁移。总量上限 200 条（超出淘汰最旧的），内存占用可忽略。
 * 需要全文检索或几千条以上时再换 Room，接口不变。
 */
class LibraryStore(private val kv: KeyValueStore = com.javrocket.avplay.core.createKeyValueStore()) {

    private val _history = MutableStateFlow(loadHistory())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _favorites = MutableStateFlow(loadFavorites())
    val favorites: StateFlow<List<HistoryEntry>> = _favorites.asStateFlow()

    // ---- 历史 ----

    /**
     * 记录观看进度。同一 (siteId, videoId) 只保留一条，按更新时间倒序。
     * 每次进度变化都会调用（播放页每 5 秒一次），所以这里必须是纯内存 + 落盘的轻操作。
     */
    fun record(entry: HistoryEntry) {
        val normalized = entry.copy(updatedAt = epochMillis())
        val next = (_history.value
            .filterNot { it.siteId == normalized.siteId && it.videoId == normalized.videoId } + normalized)
            .sortedByDescending { it.updatedAt }
            .take(MAX_HISTORY)
        _history.value = next
        persistHistory(next)
    }

    fun resumeOf(siteId: String, videoId: String): HistoryEntry? =
        _history.value.firstOrNull { it.siteId == siteId && it.videoId == videoId }

    /**
     * 首页"继续观看"用的前几条（排除已看完的）。
     *
     * `siteId` 非空时只返回该站点 —— 首页必须按当前站点过滤：
     * 各站点的 videoId 是各自的 id 空间，混在一起会出现"在 A 站点点了 B 站点的历史，
     * 用 B 的 id 去查 A 的接口"这种串台（实测 AVDB 与黄果都有 id=10586，页面能开，但内容完全不是同一个）。
     */
    fun continueWatching(siteId: String? = null, limit: Int = 8): List<HistoryEntry> =
        _history.value
            .filter { siteId == null || it.siteId == siteId }
            .filter { it.durationMs <= 0 || it.progressRatio < 0.95 }
            .take(limit)

    fun removeHistory(siteId: String, videoId: String) {
        val next = _history.value.filterNot { it.siteId == siteId && it.videoId == videoId }
        _history.value = next
        persistHistory(next)
    }

    fun clearHistory() {
        _history.value = emptyList()
        kv.remove(KEY_HISTORY)
    }

    // ---- 收藏 ----

    fun isFavorite(siteId: String, videoId: String): Boolean =
        _favorites.value.any { it.siteId == siteId && it.videoId == videoId }

    /** 返回收藏后的状态（true=已收藏）。 */
    fun toggleFavorite(entry: HistoryEntry): Boolean {
        val exists = isFavorite(entry.siteId, entry.videoId)
        val next = if (exists) {
            _favorites.value.filterNot { it.siteId == entry.siteId && it.videoId == entry.videoId }
        } else {
            (_favorites.value + entry.copy(updatedAt = epochMillis()))
                .sortedByDescending { it.updatedAt }
                .take(MAX_FAVORITES)
        }
        _favorites.value = next
        persistFavorites(next)
        return !exists
    }

    // ---- 同步（Phase 6）----

    /**
     * 导出当前快照（WebDAV 上传用）。
     * 只导出"数据"，不导配置 —— 配置由 SettingsStore 单独负责，两者合并策略不同。
     */
    fun snapshot(): LibrarySnapshot = LibrarySnapshot(
        history = _history.value,
        favorites = _favorites.value,
    )

    /**
     * 合并远端快照。
     *
     * 策略（不做"远端覆盖本地"，那是丢数据的经典做法）：
     * - 历史：同一 (siteId, videoId) 取 `updatedAt` 较新的那条 —— 谁最后看谁说了算；
     * - 收藏：取并集，同一条取 `updatedAt` 较新的（收藏时间只影响排序，不影响存在性）；
     * - 两边都按上限截断，避免远端数据把本地撑爆。
     */
    fun mergeRemote(snapshot: LibrarySnapshot): MergeStats {
        val localBefore = _history.value.size
        val favBefore = _favorites.value.size
        val remoteHistory = snapshot.history.filter { it.siteId.isNotBlank() && it.videoId.isNotBlank() }
        val localByKey = _history.value.associateBy { it.siteId to it.videoId }.toMutableMap()
        var adopted = 0

        remoteHistory.forEach { remote ->
            val key = remote.siteId to remote.videoId
            val local = localByKey[key]
            if (local == null) {
                localByKey[key] = remote
                adopted++
            } else if (remote.updatedAt > local.updatedAt) {
                localByKey[key] = remote
                adopted++
            }
        }

        val mergedHistory = localByKey.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_HISTORY)
        _history.value = mergedHistory
        persistHistory(mergedHistory)

        val remoteFavs = snapshot.favorites.filter { it.siteId.isNotBlank() && it.videoId.isNotBlank() }
        val favByKey = _favorites.value.associateBy { it.siteId to it.videoId }.toMutableMap()
        var favAdded = 0
        remoteFavs.forEach { remote ->
            val key = remote.siteId to remote.videoId
            val local = favByKey[key]
            if (local == null) {
                favByKey[key] = remote
                favAdded++
            } else if (remote.updatedAt > local.updatedAt) {
                favByKey[key] = remote
            }
        }
        val mergedFavs = favByKey.values
            .sortedByDescending { it.updatedAt }
            .take(MAX_FAVORITES)
        _favorites.value = mergedFavs
        persistFavorites(mergedFavs)

        platformLog(
            "Sync",
            "merge history: local=$localBefore -> ${_history.value.size} adopted=$adopted " +
                "favorites=$favBefore -> ${mergedFavs.size} added=$favAdded",
        )
        return MergeStats(historyAdopted = adopted, favoritesAdded = favAdded)
    }

    // ---- 序列化 ----

    private fun loadHistory(): List<HistoryEntry> = decode(kv.getString(KEY_HISTORY))
    private fun loadFavorites(): List<HistoryEntry> = decode(kv.getString(KEY_FAVORITES))

    private fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(HistoryEntry.serializer()), raw)
        }.onFailure {
            // 存的东西解不出来（版本不兼容/手改坏了）不能崩 App，丢掉重建
            platformLog("Store", "decode failed: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun persistHistory(list: List<HistoryEntry>) {
        runCatching {
            kv.putString(KEY_HISTORY, AppJson.encodeToString(ListSerializer(HistoryEntry.serializer()), list))
        }
    }

    private fun persistFavorites(list: List<HistoryEntry>) {
        runCatching {
            kv.putString(KEY_FAVORITES, AppJson.encodeToString(ListSerializer(HistoryEntry.serializer()), list))
        }
    }

    companion object {
        private const val KEY_HISTORY = "store.history"
        private const val KEY_FAVORITES = "store.favorites"
        private const val MAX_HISTORY = 200
        private const val MAX_FAVORITES = 500
    }
}
