package com.javrocket.avplay.service.avdb

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.site.aggregator.PlaybackAggregatorService

/** AVDB（MacCMS 接口站）接入统一 ContentSource。 */
class AvdbSource(
    internal val service: AvdbService,
    private val aggregator: PlaybackAggregatorService? = null,
) : ContentSource {

    override val id: String = AvdbService.SITE_ID
    override val displayName: String = "AVDB"
    override val baseUrl: String get() = service.getBaseUrl()

    override suspend fun home(): Result<HomeFeed> = service.latest(1).map { page ->
        HomeFeed(sections = listOf(VideoSection(title = "最新", items = page.items)))
    }

    override suspend fun detail(id: String): Result<VideoDetail> = service.detail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page)

    /**
     * AVDB 只给"嵌入页"（`link_embed` → 第三方播放页），不给直链。
     * 所以这里分两步：先问接口拿入口，入口不是直链时交给聚合器顺着页面向下跳一跳。
     */
    override suspend fun playback(id: String, episode: Int): Result<PlaybackInfo> {
        val info = service.playback(id, episode).getOrElse { return Result.failure(it) }
        if (isDirectMedia(info.url) || info.url.isBlank()) return Result.success(info)
        val agg = aggregator
            ?: return Result.failure(AppError.Parse("AVDB 只返回嵌入页，但没有接聚合器"))
        platformLog("Avdb", "playback via aggregator entry=${info.url.take(80)}")
        return agg.playbackFromPage(
            pageUrl = info.url,
            id = id,
            title = id,
            referrer = info.sourcePageUrl,
        )
    }

    private fun isDirectMedia(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") || u.endsWith(".mp4") || u.contains(".mp4?")
    }

    companion object {
        val DEFAULT_BASE_URL: String get() = AvdbService.DEFAULT_BASE_URL
    }
}
