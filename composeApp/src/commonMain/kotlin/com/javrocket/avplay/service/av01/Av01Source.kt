package com.javrocket.avplay.service.av01

import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.service.ContentSource

/**
 * AV01 接入统一 ContentSource。
 *
 * 与 avdb / javlibrary 不同，AV01 **自己有直链**（`/api/v1/videos/{id}/manifest/master.m3u8`），
 * 所以不需要聚合器 —— 播放走 service.playback 一步到位。
 */
class Av01Source(
    internal val service: Av01Service,
) : ContentSource {

    override val id: String = Av01Service.SITE_ID
    override val displayName: String = "AV01"
    override val baseUrl: String get() = service.getBaseUrl()

    /**
     * 首页两个区块：最新 / 最热。
     * 最热是"锦上添花"，它失败时不能让整页失败 —— 否则一次超时就把首页打空。
     */
    override suspend fun home(): Result<HomeFeed> {
        val latest = service.latest(1).getOrElse { return Result.failure(it) }
        val hottest = service.hottest(1).getOrElse {
            platformLog("Av01", "hottest failed: ${it.message}")
            null
        }
        if (latest.items.isEmpty() && hottest == null) {
            return Result.failure(
                com.javrocket.avplay.core.AppError.Parse("AV01 首页没有解析到条目"),
            )
        }
        return Result.success(
            HomeFeed(
                sections = buildList {
                    if (latest.items.isNotEmpty()) add(VideoSection("最新", latest.items))
                    hottest?.items?.takeIf { it.isNotEmpty() }
                        ?.let { add(VideoSection("最热", it)) }
                },
            ),
        )
    }

    override suspend fun detail(id: String): Result<VideoDetail> = service.detail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page)

    override suspend fun playback(id: String): Result<PlaybackInfo> = service.playback(id)

    companion object {
        val DEFAULT_BASE_URL: String get() = Av01Service.DEFAULT_BASE_URL
    }
}
