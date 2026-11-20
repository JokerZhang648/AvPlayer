package com.javrocket.avplay.service.huangguo

/**
 * 黄果顶部分类 Tab。
 * category：`GET /api/videos/category/{slug}`（目前仅 ai-duanju / ai-manju 可用）。
 * 官网已下线 AI换脸、AI魔改（任意 slug 均返回「分类不存在」或 410）。
 */
enum class HuangGuoCategory(val slug: String, val label: String) {
    AI_DUANJU("ai-duanju", "AI短剧"),
    AI_MANJU("ai-manju", "AI漫剧"),
    /** `GET /api/ranks/{type}`，slug 为榜单类型（如 hot）。 */
    HOT_RANK("hot", "热门榜"),
    /** 不走 category 接口，用首页 `featured` 区块。 */
    TOPIC("topic", "专题"),
    ;

    val isFeaturedTab: Boolean get() = this == TOPIC
    val isRankingTab: Boolean get() = this == HOT_RANK
    val usesCategoryApi: Boolean get() = !isFeaturedTab && !isRankingTab
}