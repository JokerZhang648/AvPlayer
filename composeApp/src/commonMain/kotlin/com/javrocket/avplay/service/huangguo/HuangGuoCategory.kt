package com.javrocket.avplay.service.huangguo

/**
 * 黄果顶部分类 Tab —— 与反编译 [HuangGuoCategory] 一致，对应
 * `GET /api/videos/category/{slug}`。
 */
enum class HuangGuoCategory(val slug: String, val label: String) {
    AI_DUANJU("ai-duanju", "AI短剧"),
    AI_MANJU("ai-manju", "AI漫剧"),
    AI_HUANLIAN("ai-huanlian", "AI换脸"),
    AI_MOGAI("ai-mogai", "AI魔改"),
    /** 不走 category 接口，用首页 `featured` 区块。 */
    TOPIC("topic", "专题"),
    ;

    val isFeaturedTab: Boolean get() = this == TOPIC
}