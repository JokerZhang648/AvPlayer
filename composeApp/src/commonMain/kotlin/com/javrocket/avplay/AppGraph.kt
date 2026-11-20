package com.javrocket.avplay

import com.javrocket.avplay.core.KeyValueStore
import com.javrocket.avplay.core.PersistentSettingsStore
import com.javrocket.avplay.core.SettingsStore
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.core.createKeyValueStore
import com.javrocket.avplay.net.AppJson
import com.javrocket.avplay.net.createHttpClient
import com.javrocket.avplay.net.createHttpEngine
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.net.platformUserAgent
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.av01.Av01Service
import com.javrocket.avplay.service.av01.Av01Source
import com.javrocket.avplay.service.avdb.AvdbService
import com.javrocket.avplay.service.avdb.AvdbSource
import com.javrocket.avplay.service.huangguo.HuangGuoService
import com.javrocket.avplay.service.huangguo.HuangGuoSource
import com.javrocket.avplay.service.javdb.JavDbService
import com.javrocket.avplay.service.javdb.JavDbSource
import com.javrocket.avplay.service.javlibrary.JavLibraryService
import com.javrocket.avplay.service.javlibrary.JavLibrarySource
import com.javrocket.avplay.service.xchina.XChinaService
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.ui.media.ImageLoader
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine

/**
 * 依赖装配 —— 对应反编译里的 service/ServiceManager + AppHolder。
 *
 * ⚠️ 使用顺序很重要：**必须先 await bootstrap()，再访问 sources()**。
 * 原因：HttpClient 创建时就把 UA 写进默认请求头（与原实现一致 —— UA 必须稳定），
 * 所以 UA 还没取到时创建的 client 会一直带默认 UA。`App()` 里用 `ready` 门控保证了顺序。
 *
 * Phase 3 起：
 *  - 配置落盘（PersistentSettingsStore + 平台 KV），换域名后重启 App 仍然生效
 *  - Cookie 与 WebView 共享，CF 过验证后无需重启即可继续请求
 *  - 域名变更后 rebuildSources() 会重建 client（因为 Origin/Referer 是建 client 时固化的）
 */
class AppGraph(
    private val kv: KeyValueStore = createKeyValueStore(),
    val settings: SettingsStore = PersistentSettingsStore(kv, DefaultBaseUrls.ALL),
) {
    private val engine: HttpClientEngine by lazy { createHttpEngine() }

    private var userAgent: String = DEFAULT_USER_AGENT

    private var generalClientCache: HttpClient? = null
    private var huangguoClientCache: HttpClient? = null
    private var sourcesCache: List<ContentSource>? = null
    private var imageLoaderCache: ImageLoader? = null

    /** 通用 client（HTML 站点共用）。 */
    private fun generalHttp(): HttpClient =
        generalClientCache ?: createHttpClient(engine, userAgent).also { generalClientCache = it }

    /**
     * 黄果 client：后端校验 Origin/Referer，必须按站点单独建（见 docs/MIGRATION.md §8）。
     * 之所以不能和通用 client 合并：Origin 是建 client 时写进默认头的，一个 client 只能有一个站点身份。
     */
    private fun huangguoHttp(baseUrl: String): HttpClient =
        huangguoClientCache ?: createHttpClient(
            engine = engine,
            userAgent = userAgent,
            accept = "application/json",
            siteUrl = baseUrl,
        ).also { huangguoClientCache = it }

    val imageLoader: ImageLoader
        get() = imageLoaderCache ?: ImageLoader(generalHttp()).also { imageLoaderCache = it }

    /** 历史 / 收藏 / 续播（JSON 存 KV，见 store/LibraryStore.kt 的取舍说明）。 */
    val library: com.javrocket.avplay.store.LibraryStore by lazy {
        com.javrocket.avplay.store.LibraryStore(kv)
    }

    /** WebDAV 同步（Phase 6）。 */
    val sync: com.javrocket.avplay.sync.SyncService by lazy {
        com.javrocket.avplay.sync.SyncService(kv = kv, library = library, settings = settings, http = generalHttp())
    }

    /**
     * 站点诊断（Phase 6）。传 lambda 而不是 List，是因为诊断要按"当前装配结果"跑；
     * 域名改动后重建过 sources，诊断也应该跟着看到新的 sources。
     */
    val diagnostics: com.javrocket.avplay.diag.DiagnosticsService by lazy {
        com.javrocket.avplay.diag.DiagnosticsService { sources() }
    }

    private fun buildSources(): List<ContentSource> {
        val huangguoBase = settings.baseUrl(SiteIds.HUANGGUO, HuangGuoService.DEFAULT_BASE_URL)
        val aggregator = com.javrocket.avplay.site.aggregator.PlaybackAggregatorService(generalHttp())
        return listOf(
            HuangGuoSource(HuangGuoService(baseUrl = huangguoBase, http = huangguoHttp(huangguoBase))),
            XChinaSource(
                XChinaService(
                    baseUrl = settings.baseUrl(SiteIds.XCHINA, XChinaService.DEFAULT_BASE_URL),
                    http = generalHttp(),
                ),
            ),
            JavLibrarySource(
                JavLibraryService(
                    baseUrl = settings.baseUrl(SiteIds.JAVLIBRARY, JavLibraryService.DEFAULT_BASE_URL),
                    http = generalHttp(),
                ),
                aggregator = aggregator,
            ),
            JavDbSource(
                JavDbService(
                    baseUrl = settings.baseUrl(SiteIds.JAVDB, JavDbService.DEFAULT_BASE_URL),
                    http = generalHttp(),
                    aggregator = aggregator,
                ),
            ),
            AvdbSource(
                AvdbService(
                    baseUrl = settings.baseUrl(SiteIds.AVDB, AvdbService.DEFAULT_BASE_URL),
                    http = generalHttp(),
                ),
                aggregator = aggregator,
            ),
            Av01Source(
                Av01Service(
                    baseUrl = settings.baseUrl(SiteIds.AV01, Av01Service.DEFAULT_BASE_URL),
                    http = generalHttp(),
                ),
            ),
        )
    }

    suspend fun bootstrap() {
        // UA 一旦落盘就复用：每次冷启动都换 UA 会被站点判成“会话异常”
        val saved = settings.userAgent.value
        userAgent = if (!saved.isNullOrBlank()) {
            saved
        } else {
            runCatching { platformUserAgent() }.getOrDefault(DEFAULT_USER_AGENT).also {
                settings.putUserAgent(it)
            }
        }
        platformLog("App", "bootstrap ua=${userAgent.take(60)} sites=${settings.baseUrls.value.keys}")
        // 调试期默认把 HTML 原文落盘（站点改版时靠它对着改选择器）。
        // 发布版应关掉：HtmlParsing.dumpEnabled = false
        com.javrocket.avplay.net.HtmlParsing.dumpEnabled = true
    }

    fun sources(): List<ContentSource> = sourcesCache ?: buildSources().also { sourcesCache = it }

    fun source(id: String): ContentSource? = sources().firstOrNull { it.id == id }

    /**
     * 域名改了 / 登录态变了之后调用：丢掉所有缓存，下次访问 sources() 重新装配。
     * 必须连 client 一起丢 —— Origin/Referer/UA 都固化在 client 的默认头里。
     */
    suspend fun rebuildSources() {
        sourcesCache = null
        imageLoaderCache = null
        runCatching { generalClientCache?.close() }
        runCatching { huangguoClientCache?.close() }
        generalClientCache = null
        huangguoClientCache = null
        platformLog("App", "sources rebuilt")
    }

    fun json() = AppJson

    fun close() {
        runCatching { generalClientCache?.close() }
        runCatching { huangguoClientCache?.close() }
        generalClientCache = null
        huangguoClientCache = null
        sourcesCache = null
        engine.close()
    }

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}

/**
 * 各站点的默认入口 —— 集中一处，方便换域名时只改这里。
 * 站点会频繁换域名，所以运行期可以使用配置覆盖（SettingsStore）。
 */
object DefaultBaseUrls {
    val ALL: Map<String, String> = mapOf(
        SiteIds.HUANGGUO to HuangGuoService.DEFAULT_BASE_URL,
        SiteIds.XCHINA to XChinaService.DEFAULT_BASE_URL,
        SiteIds.JAVLIBRARY to JavLibraryService.DEFAULT_BASE_URL,
        SiteIds.JAVDB to JavDbService.DEFAULT_BASE_URL,
        SiteIds.AVDB to AvdbService.DEFAULT_BASE_URL,
        SiteIds.AV01 to Av01Service.DEFAULT_BASE_URL,
    )
}
