package com.javrocket.avplay.site

/**
 * 从网页里抠“可播放地址” —— 还原反编译里的
 * service/aggregator/PlaybackTargetResolverKt 的媒体地址部分。
 *
 * 流程（与原实现一致）：
 *   1) 对原始 HTML 跑一次媒体 URL 正则
 *   2) **对 PackedJavaScript 解包出的每一段再跑一次**（直链常藏在混淆 JS 里）
 *   3) 过滤 blob: / 示例视频
 *   4) 去重
 *   5) 按 mediaRank 打分排序（最可能可播的排第一）
 */
object MediaUrlExtractor {

    // 与原实现同一条正则（注意里面同时支持 m3u8 / mp4 / get_video?）
    private val MEDIA_URL = Regex(
        """https?://[^'"\\\s<>]+(?:\.m3u8|\.mp4|get_video\?)[^'"\\\s<>]*""",
        RegexOption.IGNORE_CASE,
    )

    // 示例视频黑名单：这些是播放器 demo 用的，不是真内容
    private val SAMPLE_MARKERS = listOf(
        "test-videos.co.uk",
        "bigbuckbunny",
        "sample-video",
        "sample_video",
    )

    /** 纯匹配，不做过滤（调试/单测用）。 */
    fun matchAll(text: String): List<String> =
        MEDIA_URL.findAll(text).map { it.value }.toList()

    fun findMediaUrls(html: String): List<String> {
        if (html.isBlank()) return emptyList()

        // 先把 JS/JSON 里的转义还原**再**跑正则。
        // 原因：`window.PLAYER_CONFIG = {"m3u8":"https://.../x.m3u8?e=1\u0026h=sig"}` 这种写法里，
        // 正则把 `\` 当作 URL 终止符，会在第一个 `\u0026` 处截断，签名参数 h 全丢 —— 拿到的是"看起来对、
        // 一播就 403"的地址。顺序反过来（先匹配后解码）解决不了这个问题，因为截断发生在匹配阶段。
        val normalized = html
            .replace("\\u0026", "&")
            .replace("\\u002F", "/")
            .replace("\\u002f", "/")
            .replace("\\/", "/")

        val raw = ArrayList<String>()
        raw += matchAll(normalized)
        raw += matchAll(html)
        PackedJavaScript.unpackAll(normalized).forEach { raw += matchAll(it) }

        return raw.asSequence()
            .map { unescapeUrl(it) }
            .filter { isPlayableMediaUrl(it) }
            .distinct()
            .toList()
    }

    /** 排序后的结果：先按“可播可能性”打分，同分长的优先。 */
    fun rankedMediaUrls(html: String): List<String> =
        findMediaUrls(html).sortedWith(
            compareByDescending<String> { mediaRank(it) }
                .thenByDescending { it.length },
        )

    /** 过滤：blob: 是前端临时对象，取不到；示例视频不是内容。 */
    fun isPlayableMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        if (url.startsWith("blob:", ignoreCase = true)) return false
        val lower = url.lowercase()
        return SAMPLE_MARKERS.none { lower.contains(it) }
    }

    /**
     * 打分维度（与原实现同一套思路）：
     *   m3u8 > mp4（流式更常见、通常带多清晰度）
     *   带 token/签名的地址更可能是“能用的真链”
     *   长 URL 往往是真实 CDN 路径，短的是占位
     */
    fun mediaRank(url: String): Int {
        val lower = url.lowercase()
        var score = 0
        if (lower.contains(".m3u8")) score += 100
        if (lower.contains(".mp4")) score += 60
        if (lower.contains("sign=") || lower.contains("token=") || lower.contains("expires=")) score += 25
        if (lower.contains("/hls/") || lower.contains("/playlist/")) score += 15
        if (lower.contains("master")) score += 10
        if (lower.contains(".m3u8") && lower.contains("index")) score -= 5
        if (lower.startsWith("https://")) score += 5
        return score
    }

    /** 还原 JS 字符串里的转义。 */
    fun unescapeUrl(url: String): String = url
        .replace("\\/", "/")
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("&amp;", "&")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003D", "=")
        .trim()
}
