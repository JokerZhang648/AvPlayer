package com.javrocket.avplay.service.javlibrary

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.site.aggregator.PlaybackAggregatorService

/**
 * 把站点服务适配成统一的 ContentSource。
 * 这一层是“纯胶水”，只做三件事：拼首页区块、分页包装、关闭透传。
 */
class JavLibrarySource(
    internal val service: JavLibraryService,
    private val aggregator: PlaybackAggregatorService? = null,
) : ContentSource {

    override val id: String = "javlibrary"
    override val displayName: String = "JavLibrary"
    override val baseUrl: String get() = service.getBaseUrl()

    override suspend fun home(): Result<HomeFeed> =
        service.bestRated(page = 1).map { items ->
            HomeFeed(listOf(VideoSection("最高评分", items)))
        }

    override suspend fun detail(id: String): Result<VideoDetail> = service.detail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.searchById(keyword, page).map { items ->
            Page(items = items, page = page, hasMore = items.size >= PAGE_SIZE)
        }

    // JavLibrary 是索引站，详情页不含直链。注意 id 是站点内部 hash，
    // 聚合站认的是**番号**，所以先借详情换出 code 再去聚合 —— 直接拿 hash 搜是搜不到的。
    override suspend fun playback(id: String, episode: Int): Result<PlaybackInfo> {
        val agg = aggregator ?: return Result.failure(AppError.Parse("JavLibrary 不含直链，需要聚合器"))
        val code = service.detail(id).getOrNull()?.brief?.code?.takeIf { it.isNotBlank() } ?: id
        return agg.playbackAnySite(code)
    }

    companion object {
        private const val PAGE_SIZE = 20
        val DEFAULT_BASE_URL: String get() = JavLibraryService.DEFAULT_BASE_URL
    }
}
