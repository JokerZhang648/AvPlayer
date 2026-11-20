package com.javrocket.avplay.service.huangguo

/**
 * 黄果顶部分类 Tab，对应 `GET /api/videos/category/{slug}`。
 * 换脸/魔改已从 `ai-huanlian`、`ai-mogai` 改为 `huanlian`、`mogai`（旧 slug 返回 410）。
 */
enum class HuangGuoCategory(val slug: String, val label: String) {
    AI_DUANJU("ai-duanju", "AI短剧"),
    AI_MANJU("ai-manju", "AI漫剧"),
    AI_HUANLIAN("huanlian", "AI换脸"),
    AI_MOGAI("mogai", "AI魔改"),
    /** 不走 category 接口，用首页 `featured` 区块。 */
    TOPIC("topic", "专题"),
    ;

    val isFeaturedTab: Boolean get() = this == TOPIC
}