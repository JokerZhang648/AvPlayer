package com.javrocket.avplay.sync

import com.javrocket.avplay.store.LibrarySnapshot
import kotlinx.serialization.Serializable

/**
 * WebDAV 同步包 —— 一个文件装下"跨设备要一致"的全部状态。
 *
 * 为什么带 `schema`：将来加字段（比如播放列表）时，老版本的 App 读到新包不能直接报错，
 * 要按版本决定"部分读取"还是"拒绝"。没有版本号的同步包在升级那一刻必然打架。
 *
 * 为什么不带 Cookie / UA：那些是**设备本地身份**，跨设备复制会让站点判成会话异常。
 */
@Serializable
data class SyncBundle(
    val schema: Int = SCHEMA_VERSION,
    val exportedAt: Long = 0,
    val device: String = "",
    val library: LibrarySnapshot = LibrarySnapshot(),
    val baseUrls: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** WebDAV 连接参数。密码只存在设备本地 KV 里，不进同步包。 */
data class WebDavConfig(
    val url: String,
    val username: String = "",
    val password: String = "",
    val fileName: String = "avplay-backup.json",
) {
    val normalizedUrl: String get() = url.trim().trimEnd('/')

    /** 完整文件地址：允许用户只填目录（绝大多数 WebDAV 客户端都是填目录）。 */
    val fileUrl: String
        get() {
            val base = normalizedUrl
            if (base.endsWith(".json", ignoreCase = true)) return base
            return "$base/$fileName"
        }

    /** 目录地址（PROPFIND 探活用它）。 */
    val dirUrl: String
        get() {
            val f = fileUrl
            return f.substringBeforeLast('/')
        }

    fun isValid(): Boolean = normalizedUrl.startsWith("http", ignoreCase = true)
}
