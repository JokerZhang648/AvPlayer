package com.javrocket.avplay.service.huangguo

import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.service.ContentSource

/** 黄果（JSON API 站）接入统一 ContentSource。 */
class HuangGuoSource(
    internal val service: HuangGuoService,
) : ContentSource {

    override val id: String = "huangguo"
    override val displayName: String = "黄果"
    override val baseUrl: String get() = service.getBaseUrl()

    override suspend fun home(): Result<HomeFeed> = service.getHome()

    override suspend fun detail(id: String): Result<VideoDetail> = service.getVideoDetail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page)

    override suspend fun playback(id: String, episode: Int): Result<PlaybackInfo> =
        service.getPlayback(id, episode)

    companion object {
        val DEFAULT_BASE_URL: String get() = HuangGuoService.DEFAULT_BASE_URL
    }
}
