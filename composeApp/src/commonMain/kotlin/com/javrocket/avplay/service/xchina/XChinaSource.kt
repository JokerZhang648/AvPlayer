package com.javrocket.avplay.service.xchina

import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoComment
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.service.ContentSource

class XChinaSource(
    internal val service: XChinaService,
) : ContentSource {

    override val id: String = "xchina"
    override val displayName: String = "XChina"
    override val baseUrl: String get() = service.getBaseUrl()

    override suspend fun home(): Result<HomeFeed> =
        service.getSeries(page = 1).map { items ->
            HomeFeed(listOf(VideoSection("近期热门", items)))
        }

    override suspend fun detail(id: String): Result<VideoDetail> = service.getVideoDetail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page).map { items ->
            Page(items = items, page = page, hasMore = items.size >= PAGE_SIZE)
        }

    /** 详情页直链或经聚合器解析嵌入播放器。 */
    override suspend fun playback(id: String, episode: Int): Result<PlaybackInfo> =
        service.getVideoDownload(id)

    suspend fun personVideos(
        personId: String,
        page: Int = 1,
        route: XChinaPersonRoute = XChinaPersonRoute.Model,
    ): Result<Page<VideoBrief>> =
        service.getPersonVideos(personId, page, route).map { items ->
            Page(items = items, page = page, hasMore = items.size >= PAGE_SIZE)
        }

    suspend fun modelVideos(modelId: String, page: Int = 1): Result<Page<VideoBrief>> =
        personVideos(modelId, page, XChinaPersonRoute.Model)

    suspend fun allComments(videoId: String): Result<Pair<List<VideoComment>, Int?>> =
        service.getAllVideoComments(videoId)

    companion object {
        private const val PAGE_SIZE = 20
        val DEFAULT_BASE_URL: String get() = XChinaService.DEFAULT_BASE_URL
    }
}
