package com.javrocket.avplay.service

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.HomeFeed
import com.javrocket.avplay.model.Page
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail

/**
 * 内容源统一接口 —— Phase 2 最重要的抽象。
 *
 * 反编译里的 14 个站点形态各异（JSON / HTML / 加密），但 UI 只关心四件事：
 * 首页、详情、搜索、能不能直接解析播放地址。提成接口后：
 *   - UI 只有一份实现，不会写 14 份相似界面；
 *   - 新增站点 = 实现一个 ContentSource，UI 一行不改；
 *   - 能力差异（有的站能直出 m3u8，有的不能）用默认实现表达，而不是到处 if/else。
 */
interface ContentSource {
    /** 稳定标识，同时是配置 key 的前缀（如 `xchina_url`）。 */
    val id: String

    val displayName: String

    val baseUrl: String

    suspend fun home(): Result<HomeFeed>

    suspend fun detail(id: String): Result<VideoDetail>

    suspend fun search(keyword: String, page: Int = 1): Result<Page<VideoBrief>>

    /**
     * 直接解析播放地址。默认不支持 —— 需要聚合器二次解析的站点保持这个默认实现，
     * UI 会收到一条可读的失败原因，而不是一个空地址。
     */
    suspend fun playback(id: String, episode: Int = 1): Result<PlaybackInfo> =
        Result.failure(AppError.Parse("「$displayName」不支持直接解析播放地址，需要走聚合器"))
}
