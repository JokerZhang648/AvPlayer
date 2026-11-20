package com.javrocket.avplay

import com.javrocket.avplay.core.InMemoryKeyValueStore
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.store.HistoryEntry
import com.javrocket.avplay.store.LibrarySnapshot
import com.javrocket.avplay.store.LibraryStore
import com.javrocket.avplay.sync.WebDavConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 6 同步的合并语义 —— 这几条是全工程最"会丢数据"的地方，必须有测试兜住：
 * 合并策略一旦写错（比如远端直接覆盖本地），用户的历史会在一次同步里凭空消失。
 *
 * ⚠️ 造本地数据必须**预置 KV**而不是调 `record()`：`record()` 会强制把 `updatedAt`
 * 改成"现在"，那样任何远端时间戳都更旧，测出来全是"不采纳"，等于没测。
 */
class SyncMergeTest {

    private fun store(
        history: List<HistoryEntry> = emptyList(),
        favorites: List<HistoryEntry> = emptyList(),
    ): LibraryStore {
        val ser = ListSerializer(HistoryEntry.serializer())
        val kv = InMemoryKeyValueStore(
            buildMap {
                if (history.isNotEmpty()) put("store.history", AppJson.encodeToString(ser, history))
                if (favorites.isNotEmpty()) put("store.favorites", AppJson.encodeToString(ser, favorites))
            },
        )
        return LibraryStore(kv)
    }

    private fun entry(
        siteId: String,
        videoId: String,
        updatedAt: Long,
        positionMs: Long = 0,
    ) = HistoryEntry(
        siteId = siteId,
        videoId = videoId,
        title = "t-$videoId",
        positionMs = positionMs,
        durationMs = 600_000,
        updatedAt = updatedAt,
    )

    @Test
    fun merges_newer_remote_and_adds_missing() {
        val lib = store(
            history = listOf(
                entry("avdb", "1", updatedAt = 100),
                entry("avdb", "2", updatedAt = 200),
            ),
        )

        val stats = lib.mergeRemote(
            LibrarySnapshot(
                history = listOf(
                    // 同键但更新 → 采纳远端
                    entry("avdb", "1", updatedAt = 300, positionMs = 123_456),
                    // 本地没有 → 直接进来
                    entry("javdb", "new", updatedAt = 400),
                    // 同键但更旧 → 保留本地
                    entry("avdb", "2", updatedAt = 150, positionMs = 999),
                ),
                favorites = listOf(entry("huangguo", "fav", updatedAt = 500)),
            ),
        )

        assertEquals(2, stats.historyAdopted, "应采纳 1 条更新 + 1 条新增")
        assertEquals(1, stats.favoritesAdded)

        val byKey = lib.history.value.associateBy { it.siteId to it.videoId }
        assertEquals(123_456L, byKey["avdb" to "1"]?.positionMs, "更新的远端应覆盖本地进度")
        assertEquals(400L, byKey["javdb" to "new"]?.updatedAt)
        assertEquals(200L, byKey["avdb" to "2"]?.updatedAt, "更旧的远端不能覆盖本地")
        assertEquals(3, lib.history.value.size)
        assertEquals(1, lib.favorites.value.size)
    }

    @Test
    fun merged_history_is_sorted_by_recency() {
        val lib = store(history = listOf(entry("avdb", "old", updatedAt = 10)))
        lib.mergeRemote(LibrarySnapshot(history = listOf(entry("avdb", "fresh", updatedAt = 9999))))

        assertEquals("fresh", lib.history.value.first().videoId)
    }

    @Test
    fun ignores_entries_without_keys() {
        val lib = store(history = listOf(entry("avdb", "keep", updatedAt = 1)))

        val stats = lib.mergeRemote(
            LibrarySnapshot(
                history = listOf(
                    entry("", "no-site", updatedAt = 5),
                    entry("avdb", "", updatedAt = 6),
                ),
            ),
        )

        assertEquals(0, stats.historyAdopted)
        assertEquals(1, lib.history.value.size)
    }

    @Test
    fun caps_history_at_store_limit() {
        val lib = store()
        // MAX_HISTORY = 200，给 250 条远端记录，合并后只能留 200 条且都是最新的
        val remote = (1..250).map { entry("avdb", "v$it", updatedAt = it.toLong()) }
        lib.mergeRemote(LibrarySnapshot(history = remote))

        assertEquals(200, lib.history.value.size, "超过上限必须截断")
        assertEquals("v250", lib.history.value.first().videoId, "保留的应是最近的那批")
        assertTrue(lib.history.value.none { it.videoId == "v1" })
    }

    @Test
    fun snapshot_json_has_no_credentials() {
        val lib = store(history = listOf(entry("av01", "777", updatedAt = 42, positionMs = 5000)))
        val json = AppJson.encodeToString(
            LibrarySnapshot.serializer(),
            lib.snapshot(),
        )
        // 快照里不应出现任何凭据字段（防止有人往 LibrarySnapshot 里加 password）
        assertFalse(json.contains("password", ignoreCase = true))
        assertFalse(json.contains("cookie", ignoreCase = true))

        val back = AppJson.decodeFromString(LibrarySnapshot.serializer(), json)
        assertEquals(1, back.history.size)
        assertEquals(5000L, back.history.first().positionMs)
    }
}

/** WebDAV 地址规范化：用户十有八九只填目录，文件名必须自己补。 */
class WebDavConfigTest {

    @Test
    fun appends_file_name_to_directory() {
        val c = WebDavConfig(url = "http://10.0.2.2:8099/dav/avplay/")
        assertEquals("http://10.0.2.2:8099/dav/avplay/avplay-backup.json", c.fileUrl)
        assertEquals("http://10.0.2.2:8099/dav/avplay", c.dirUrl)
    }

    @Test
    fun keeps_full_json_path() {
        val c = WebDavConfig(url = "https://dav.jianguoyun.com/dav/avplay/mine.json")
        assertEquals("https://dav.jianguoyun.com/dav/avplay/mine.json", c.fileUrl)
        assertEquals("https://dav.jianguoyun.com/dav/avplay", c.dirUrl)
    }

    @Test
    fun validates_scheme() {
        assertTrue(WebDavConfig(url = "https://x/y").isValid())
        assertTrue(WebDavConfig(url = "HTTP://x/y").isValid())
        assertFalse(WebDavConfig(url = "").isValid())
        assertFalse(WebDavConfig(url = "x/y").isValid())
    }
}
