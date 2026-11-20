package com.javrocket.avplay.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 站点无关的“视频条目”。字段是宽松的：每个站点能填多少填多少，
 * 缺的留空，UI 用 fallback 文案兜住。
 *
 * 对应反编译里的 model/AvInfo（原类有 20+ 字段，这里只保留跨站点通用的那部分）。
 */
@Serializable
data class VideoBrief(
    val id: String = "",
    val title: String = "",
    val cover: String? = null,
    val code: String? = null,
    val duration: String? = null,
    val rating: Double? = null,
    /** 列表副标题 / 简介（黄果 `desc`）。其它站点可留空。 */
    val subtitle: String? = null,
    /** 热度（黄果 `hot`）。 */
    val hot: Long? = null,
    val episodeCount: Int? = null,
    val isFinished: Boolean? = null,
    @SerialName("source_site") val sourceSite: String = "",
)

/** 首页的一个横向区块（“最新”“热门”“分类推荐”…）。 */
@Serializable
data class VideoSection(
    val title: String = "",
    val items: List<VideoBrief> = emptyList(),
)

@Serializable
data class HomeFeed(
    val sections: List<VideoSection> = emptyList(),
)

@Serializable
data class Episode(
    val index: Int = 1,
    val title: String = "",
    val url: String = "",
)

@Serializable
data class VideoDetail(
    val brief: VideoBrief = VideoBrief(),
    val description: String = "",
    val actors: List<ValueLink> = emptyList(),
    val tags: List<ValueLink> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val related: List<VideoBrief> = emptyList(),
)

/**
 * “显示名 + 链接”的通用结构。
 *
 * 这是整个架构的关键抽象：分类 / 标签 / 演员 / 片商 全部压成 ValueLink，
 * UI 只负责展示与点击，链接交给对应站点 Service 去解析 —— UI 不需要知道站点差异。
 * 对应反编译里的 model/ValueLink。
 */
@Serializable
data class ValueLink(
    val value: String = "",
    val link: String = "",
)

/** 通用分页结果，对应反编译里的 paging/Pagination + SuccessData。 */
data class Page<T>(
    val items: List<T>,
    val page: Int,
    val hasMore: Boolean,
)

/** 播放解析结果：交给播放层去建线路。 */
data class PlaybackInfo(
    val url: String,
    val mimeType: String = "application/vnd.apple.mpegurl",
    val headers: Map<String, String> = emptyMap(),
    val sourcePageUrl: String = "",
)
