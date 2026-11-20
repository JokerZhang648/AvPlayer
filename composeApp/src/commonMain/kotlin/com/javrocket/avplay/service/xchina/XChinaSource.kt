package com.javrocket.avplay.service.xchina

import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
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
            HomeFeed(listOf(VideoSection("最新", items)))
        }

    override suspend fun detail(id: String): Result<VideoDetail> = service.getVideoDetail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page).map { items ->
            Page(items = items, page = page, hasMore = items.size >= PAGE_SIZE)
        }

    /** XChina 的下载页直接暴露媒体地址，所以这里可以直出。 */
    override suspend fun playback(id: String, episode: Int): Result<PlaybackInfo> =
        service.getVideoDownload(id)

    companion object {
        private const val PAGE_SIZE = 20
        val DEFAULT_BASE_URL: String get() = XChinaService.DEFAULT_BASE_URL
    }
}
