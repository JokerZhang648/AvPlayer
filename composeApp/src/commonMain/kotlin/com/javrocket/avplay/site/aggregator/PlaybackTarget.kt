package com.javrocket.avplay.site.aggregator

/**
 * 聚合器契约 —— 对应反编译 `service/aggregator/PlaybackTargetResolver.kt` 的数据模型。
 *
 * 为什么要有这一层：很多站点自己不给播放地址，只给一个"播放页 / 聚合页"链接（iframe 套 iframe），
 * 真链藏在第二三跳的页面里。硬编码每个站点的每一跳会随改版全废，
 * 所以抽象成"页面 → 抽取器 → 下一个目标 / 媒体地址"的通用流程。
 */

/** 一跳待解析目标。`referrer` 是抓这一页时该带的来源（防盗链站点会校验）。 */
data class PlaybackTarget(
    val id: String,
    val title: String,
    val url: String,
    val referrer: String,
)

/** 已加载的页面。`finalUrl` 是跟随重定向后的地址 —— 相对链接必须按它拼，不能按 requestedUrl。 */
data class LoadedPlaybackPage(
    val requestedUrl: String,
    val finalUrl: String,
    val html: String,
)

/** 抽取结果：找到媒体 / 找到下一批目标 / 这一页什么都没有。 */
sealed interface PlaybackTargetExtraction {
    /**
     * `mimeType` 可选：地址带 `.m3u8` 后缀时能自己推断，
     * 但有一类站的地址是"无后缀 + 签名"（如 upload18 → helvid），只能由抽取器如实说明，
     * 否则会被当成 mp4 走渐进式播放、直接报 Source error。
     */
    data class Media(
        val urls: List<String>,
        val mimeType: String? = null,
    ) : PlaybackTargetExtraction

    data class Next(val targets: List<PlaybackTarget>) : PlaybackTargetExtraction
    data object Empty : PlaybackTargetExtraction
}

/** 最终可播放结果。 */
data class ResolvedPlaybackTarget(
    val id: String,
    val title: String,
    val url: String,
    val mimeType: String,
    val sourcePageUrl: String,
)

interface PlaybackPageLoader {
    suspend fun load(url: String, referrer: String): LoadedPlaybackPage?
}

interface PlaybackTargetExtractor {
    fun supports(page: LoadedPlaybackPage): Boolean
    fun extract(target: PlaybackTarget, page: LoadedPlaybackPage): PlaybackTargetExtraction
}

/** 聚合站点（反编译只有这两个）。 */
enum class PlaybackAggregatorSite(val displayName: String) {
    JAV_GG("JavGG"),
    JAV_GURU("JavGuru"),
}
