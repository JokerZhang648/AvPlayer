package com.javrocket.avplay.service.javdb

import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.service.ContentSource

/** JavDB（双通道：API 优先 + HTML 兜底）接入统一 ContentSource。 */
class JavDbSource(
    internal val service: JavDbService,
) : ContentSource {

    override val id: String = JavDbService.SITE_ID
    override val displayName: String = "JavDB"
    override val baseUrl: String get() = service.getBaseUrl()

    override suspend fun home(): Result<HomeFeed> = service.home()

    override suspend fun detail(id: String): Result<VideoDetail> = service.detail(id)

    override suspend fun search(keyword: String, page: Int): Result<Page<VideoBrief>> =
        service.search(keyword, page)

    /**
     * JavDB 的 `id` 是 slug（形如 `mOz9Rv`），聚合站认的是**番号**（`Tushy.2026.11.15`）。
     * 所以这里必须先借详情页换出番号，否则聚合站按 slug 搜不到任何东西
     * （实测：拿 slug 去搜 javgg，candidates=0）。
     */
    override suspend fun playback(id: String): Result<PlaybackInfo> {
        val code = service.detail(id).getOrNull()?.brief?.code?.takeIf { it.isNotBlank() }
        return service.playbackCode(code ?: id)
    }

    companion object {
        val DEFAULT_BASE_URL: String get() = JavDbService.DEFAULT_BASE_URL
    }
}
