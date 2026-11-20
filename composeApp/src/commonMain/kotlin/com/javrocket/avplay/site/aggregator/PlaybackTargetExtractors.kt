package com.javrocket.avplay.site.aggregator

import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.site.MediaUrlExtractor

/**
 * 4 个默认抽取器，顺序与反编译 `defaultPlaybackTargetExtractors()` 一致：
 * JavGuruGateway → TurboVid → KnownPlayer → NestedFrame。
 *
 * 顺序有讲究：越"专用"的越靠前。前三者认出了自己的站点特征就走专有逻辑（能拿到真链），
 * 认不出才轮到通用的 NestedFrame（继续顺着 iframe 往下钻）。
 */
fun defaultPlaybackTargetExtractors(): List<PlaybackTargetExtractor> = listOf(
    JavGuruGatewayExtractor,
    TurboVidExtractor,
    KnownPlayerExtractor,
    NestedFrameExtractor,
)

/** 直接从媒体地址抠（走 MediaUrlExtractor，含混淆 JS 解包），按可播性排序。 */
internal fun extractMedia(page: LoadedPlaybackPage): PlaybackTargetExtraction {
    val urls = MediaUrlExtractor.rankedMediaUrls(page.html)
    platformLog("Aggregator", "extractMedia urls=${urls.size} first=${urls.firstOrNull()?.take(60)}")
    return if (urls.isEmpty()) PlaybackTargetExtraction.Empty else PlaybackTargetExtraction.Media(urls)
}

/**
 * 解析 `window.PLAYER_CONFIG = {...}` 里的媒体字段，返回「地址 → MIME」。
 *
 * 为什么需要这条单独路径：这类播放页的地址是
 * `https://helvid.com/m/L3Yv...Lm0zdTg?e=...&h=...` —— **路径 base64、没有 `.m3u8` 后缀**，
 * 靠"URL 后缀"的正则一个都匹配不到（连 `html.contains(".m3u8")` 都是 false，
 * 唯一出现 m3u8 的地方是这个 JSON 的**键名**）。
 * 所以必须按字段名取，而不是按后缀猜；同时也只有这里能知道"它是 HLS"。
 */
internal fun parsePlayerConfig(html: String): List<Pair<String, String>> {
    if (!html.contains("PLAYER_CONFIG")) return emptyList()
    val out = ArrayList<Pair<String, String>>()
    for (key in PLAYER_CONFIG_KEYS) {
        val re = Regex(""""$key"\s*:\s*"((?:\\.|[^"\\])+)"""")
        val raw = re.find(html)?.groupValues?.getOrNull(1) ?: continue
        val url = MediaUrlExtractor.unescapeUrl(raw)
        if (url.startsWith("http")) {
            out += url to if (key == "m3u8") HLS_MIME else "video/mp4"
        }
    }
    return out.distinctBy { it.first }
}

internal const val HLS_MIME = "application/x-mpegURL"

private val PLAYER_CONFIG_KEYS = listOf("m3u8", "alternate720", "mp4")

/** 顺着 iframe 往下钻。相对地址必须按 `finalUrl` 拼，不能按 requestedUrl（有跳转时两者不同）。 */
internal fun extractFrames(
    target: PlaybackTarget,
    page: LoadedPlaybackPage,
): PlaybackTargetExtraction {
    val seen = HashSet<String>()
    val next = ArrayList<PlaybackTarget>()
    for (node in Html.parse(page.html).select("iframe[src]")) {
        val raw = node.attr("src").trim()
        if (raw.isBlank() || raw.startsWith("about:", ignoreCase = true) || raw.startsWith("data:", ignoreCase = true)) {
            continue
        }
        val absolute = absolutize(page.finalUrl, raw)
        if (absolute.isBlank() || !seen.add(absolute)) continue
        next += PlaybackTarget(
            id = target.id,
            title = target.title,
            url = absolute,
            referrer = page.finalUrl,
        )
    }
    return if (next.isEmpty()) PlaybackTargetExtraction.Empty else PlaybackTargetExtraction.Next(next)
}

/** jav.guru 的 token 网关：脚本里 base/rtype/cid/keys，用 DOM 上的分片 token 反转后拼查询串。 */
object JavGuruGatewayExtractor : PlaybackTargetExtractor {

    override fun supports(page: LoadedPlaybackPage): Boolean {
        val host = hostOf(page.finalUrl) ?: return false
        return host.endsWith("jav.guru") &&
            pathOf(page.finalUrl).startsWith("/searcho/") &&
            page.html.contains("fullToken.rev()")
    }

    override fun extract(target: PlaybackTarget, page: LoadedPlaybackPage): PlaybackTargetExtraction {
        val gateway = parseJavGuruGateway(page.finalUrl, page.html) ?: return PlaybackTargetExtraction.Empty
        return PlaybackTargetExtraction.Next(
            listOf(gateway.copy(id = target.id, title = target.title)),
        )
    }
}

/** 从网关页解析出真正的播放页地址（对应反编译 `PlaybackAggregatorParser.parseJavGuruGateway`）。 */
internal fun parseJavGuruGateway(pageUrl: String, html: String): PlaybackTarget? {
    val scripts = Html.parse(html).select("script").map { it.html }
    val script = scripts.firstOrNull {
        it.contains("window.cfg") && it.contains("fullToken.rev()")
    } ?: return null

    val base = Regex("""base:\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.getOrNull(1) ?: return null
    val rtype = Regex("""rtype:\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.getOrNull(1) ?: return null
    val cid = Regex("""cid:\s*['"]([^'"]+)['"]""").find(script)?.groupValues?.getOrNull(1) ?: return null
    val keysRaw = Regex("""keys:\s*\[([^]]+)]""").find(script)?.groupValues?.getOrNull(1) ?: return null

    val keys = Regex("""['"]([^'"]+)['"]""").findAll(keysRaw).map { it.groupValues[1] }.toList()
    if (keys.isEmpty()) return null

    val tokenHost = Html.parse(html).selectFirst("#$cid") ?: return null
    val joined = keys.joinToString("") { tokenHost.attr(it) }
    if (joined.isBlank()) return null

    // 站点把 token 分段放在属性里，再反向拼接：顺序还原后才能拿到合法签名
    val url = "$base?${rtype}r=${joined.reversed()}"
    return PlaybackTarget(
        id = "",
        title = "",
        url = absolutize(pageUrl, url),
        referrer = pageUrl,
    )
}

/** turbovid 系：脚本里有 `urlPlay = '...'`，否则退回通用抠链。 */
object TurboVidExtractor : PlaybackTargetExtractor {

    private val URL_PLAY = Regex("""(?:var\s+)?urlPlay\s*=\s*['"]([^'"]+)['"]""")

    override fun supports(page: LoadedPlaybackPage): Boolean =
        hostOf(page.finalUrl)?.contains("turbovid", ignoreCase = true) == true

    override fun extract(target: PlaybackTarget, page: LoadedPlaybackPage): PlaybackTargetExtraction {
        val url = URL_PLAY.find(page.html)?.groupValues?.getOrNull(1)
            ?.let { MediaUrlExtractor.unescapeUrl(it) }
            ?.takeIf { it.isNotBlank() }
        if (url == null) return extractMedia(page)
        return PlaybackTargetExtraction.Media(listOf(url))
    }
}

/** 一批已知的第三方网盘/播放域：它们页面里通常直接就是媒体链，抠不到才继续钻 iframe。 */
object KnownPlayerExtractor : PlaybackTargetExtractor {

    private val HOSTS = listOf(
        "javclan.com", "javstreamhq.xyz", "jav-vids.xyz", "streamtape.",
        "earnvidjavgg.xyz", "vidhide", "maxstream.", "voe.",
        "johnfullwonder.com", "playmogo.com", "dood",
        // AVDB 的 link_embed 指向的就是这个站：页里 window.PLAYER_CONFIG 直接带签名 m3u8
        "upload18.",
    )

    override fun supports(page: LoadedPlaybackPage): Boolean {
        val host = hostOf(page.finalUrl)?.lowercase() ?: return false
        return HOSTS.any { host.contains(it) }
    }

    override fun extract(target: PlaybackTarget, page: LoadedPlaybackPage): PlaybackTargetExtraction {
        // 先读播放器配置（这类站的地址没后缀，只有配置里才有），再退回通用抠链
        val configured = parsePlayerConfig(page.html)
        if (configured.isNotEmpty()) {
            return PlaybackTargetExtraction.Media(
                urls = configured.map { it.first },
                mimeType = configured.first().second,
            )
        }
        val media = extractMedia(page)
        return if (media is PlaybackTargetExtraction.Media) media else extractFrames(target, page)
    }
}

/**
 * 兜底：任何页面都能试。
 *
 * 顺序是"先抠链、再钻 frame"：认不出站点特征的页面里，媒体地址可能直接就写在
 * `PLAYER_CONFIG` / `<video src>` 里（实测 upload18.to 的嵌入页就是），
 * 只钻 iframe 会白白丢掉这种最好拿的地址。
 * 抠不到才顺着 iframe 下一跳 —— 那才是真正需要钻的页面。
 */
object NestedFrameExtractor : PlaybackTargetExtractor {
    override fun supports(page: LoadedPlaybackPage): Boolean = true

    override fun extract(target: PlaybackTarget, page: LoadedPlaybackPage): PlaybackTargetExtraction {
        val configured = parsePlayerConfig(page.html)
        if (configured.isNotEmpty()) {
            return PlaybackTargetExtraction.Media(
                urls = configured.map { it.first },
                mimeType = configured.first().second,
            )
        }
        val media = extractMedia(page)
        return if (media is PlaybackTargetExtraction.Media) media else extractFrames(target, page)
    }
}

// ---- URL 小工具（不引 ktor 的 Url 解析，避免为了取 host 去建对象）----

internal fun hostOf(url: String): String? {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return null
    return afterScheme
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
        .substringAfter('@')
        .takeIf { it.isNotBlank() }
}

internal fun pathOf(url: String): String {
    val afterScheme = url.substringAfter("://", "")
    if (afterScheme.isEmpty()) return ""
    val noHost = afterScheme.substringAfter('/', "")
    return "/" + noHost.substringBefore('#')
}
