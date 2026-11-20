package com.javrocket.avplay.service.xchina

import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.parser.BaseParser
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.parser.cleanText
import com.javrocket.avplay.parser.extractGroup
import com.javrocket.avplay.parser.firstNonBlank
import com.javrocket.avplay.model.VideoComment
import com.javrocket.avplay.parser.stripSiteSuffix
import com.javrocket.avplay.parser.stripXChinaDetailTitle

/**
 * XChina 是纯网页站，没有 API。
 *
 * 2026-11 改版后卡片形态：`<a href="/video/<hash>" class="group block">` 内含
 * `img[src]`（封面，`r2.xchina.download/cover/<hash>.webp`）与标题 `span[class*=line-clamp]`，
 * 另有 `cover-pill` 浮层（分类/时长/评论数）—— 那些**不能**当标题。
 *
 * 所以解析器采用「链接驱动」策略：先按 href 里的 `/video/<id>` 抠 id（`/video-series/`、
 * `/video-category/`、`/video-tag/` 因为都带连字符，不会被误匹配），
 * 再取 `span[class*=line-clamp]` 的文本作标题 —— 站点再改 class 名时这里最关键的一点仍然成立：
 * 标题永远在卡片内**唯一一个多行截断**的 span 里。
 */
class XChinaListParser : BaseParser<List<VideoBrief>> {

    override fun parse(html: String, options: ParseOptions): List<VideoBrief> {
        val doc = Html.parse(html)
        val out = LinkedHashMap<String, VideoBrief>()

        // 主路径：所有指向详情页的锚点
        doc.select("a[href*='/video/']").forEach { a ->
            val href = a.attr("href")
            val id = extractGroup(href, VIDEO_ID) ?: return@forEach
            val img = a.selectFirst("img")

            // 标题优先级：多行截断 span > img alt > 锚点全文（锚点全文含浮层噪声，放最后）
            val title = firstNonBlank(
                cleanText(a.selectFirst("span[class*=line-clamp]")?.text),
                cleanText(img?.attr("alt")),
                cleanText(a.text),
            )
            val cover = img?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { absolutize(options.baseUrl, it) }
            val meta = parseListCardMeta(a)

            val brief = VideoBrief(
                id = id,
                title = title,
                cover = cover,
                sourceSite = SITE_ID,
                duration = meta.duration,
                code = meta.seriesCode,
                category = meta.category,
                subtitle = meta.footLine,
            )
            val existing = out[id]
            if (existing == null) {
                out[id] = brief
            } else {
                out[id] = existing.mergeListBrief(brief)
            }
        }

        // 兜底：部分列表页是 data-* 卡片（没有 <a> 包住）
        if (out.isEmpty()) {
            doc.select("[data-video-id]").forEach { node ->
                val id = node.attr("data-video-id").takeIf { it.isNotBlank() } ?: return@forEach
                if (out.containsKey(id)) return@forEach

                val img = node.selectFirst("img")
                val title = firstNonBlank(cleanText(node.attr("data-title")), cleanText(node.text))
                out[id] = VideoBrief(
                    id = id,
                    title = title,
                    cover = img?.attr("src")?.takeIf { it.isNotBlank() }?.let { absolutize(options.baseUrl, it) },
                    sourceSite = SITE_ID,
                )
            }
        }

        return out.values.toList()
    }

    companion object {
        const val SITE_ID = "xchina"

        /** 只匹配 `/video/<hash>`；`/video-series/`、`/video-category/`、`/video-tag/` 都带连字符，天然排除。 */
        val VIDEO_ID = Regex("""/video/([A-Za-z0-9]+)""")
        private val CARD_DURATION = Regex("""^\d{1,3}:\d{2}$""")
        private val SERIES_CODE = Regex("""^[A-Z0-9]{2,}-[A-Z0-9]{2,}""", RegexOption.IGNORE_CASE)

        private fun parseListCardMeta(a: com.javrocket.avplay.parser.HtmlNode): ListCardMeta {
            val pills = a.select("span.cover-pill").map { cleanText(it.text) }.filter { it.isNotBlank() }
            val duration = pills.firstOrNull { CARD_DURATION.matches(it) }
            val seriesCode = pills.firstOrNull { SERIES_CODE.matches(it) }
            val category = pills.firstOrNull { pill ->
                pill != duration && pill != seriesCode && !SERIES_CODE.matches(pill)
            }
            val foot = a.selectFirst("span[class*='text-xs'][class*='items-center']")
                ?: a.select("span[class*='text-xs']").lastOrNull()
            val actor = cleanText(foot?.selectFirst("[class*=truncate]")?.text)
            val date = cleanText(foot?.selectFirst("[class*=tabular-nums]")?.text)
            val footLine = when {
                actor.isNotBlank() && date.isNotBlank() -> "$actor · $date"
                actor.isNotBlank() -> actor
                date.isNotBlank() -> date
                else -> cleanText(foot?.text).takeIf { it.isNotBlank() && !it.contains("line-clamp") }
            }
            return ListCardMeta(duration, seriesCode, category, footLine)
        }

        private fun VideoBrief.mergeListBrief(fresh: VideoBrief): VideoBrief = copy(
            title = title.ifBlank { fresh.title },
            cover = cover ?: fresh.cover,
            duration = duration ?: fresh.duration,
            code = code ?: fresh.code,
            category = category ?: fresh.category,
            subtitle = subtitle ?: fresh.subtitle,
        )
    }
}

private data class ListCardMeta(
    val duration: String?,
    val seriesCode: String?,
    val category: String?,
    val footLine: String?,
)

/** 详情页解析：优先读 OpenGraph（最稳），再退回正文元素。 */
class XChinaDetailParser(private val videoId: String) : BaseParser<VideoDetail> {

    override fun parse(html: String, options: ParseOptions): VideoDetail {
        val doc = Html.parse(html)

        val title = firstNonBlank(
            cleanText(doc.selectFirst("h1")?.text),
            stripXChinaDetailTitle(doc.selectFirst("meta[property='og:title']")?.attr("content").orEmpty()),
            stripXChinaDetailTitle(doc.title),
            videoId,
        )
        val cover = firstNonBlank(
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
            doc.selectFirst("img[src*='cover']")?.attr("src"),
            doc.selectFirst("img[src*='poster']")?.attr("src"),
        ).takeIf { it.isNotBlank() }?.let { absolutize(options.baseUrl, it) }

        val description = firstNonBlank(
            cleanText(doc.selectFirst("meta[property='og:description']")?.attr("content")),
            cleanText(doc.selectFirst("div.description")?.text),
            cleanText(doc.selectFirst("div.intro")?.text),
            cleanText(doc.selectFirst("p.summary")?.text),
        )

        // 新站模特页 /model/<hash>；旧站 /actor/<hash>
        val actors = doc.select("a[href*='/model/'], a[href*='/actor/']").mapNotNull { a ->
            val name = cleanText(a.text)
            if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, a.attr("href")))
        }.distinctBy { it.link.ifBlank { it.value } }

        // 新站标签路径是 /video-tag/<slug>（旧站是 tag/ / category/）
        val tags = doc.select("a[href*='/video-tag/'], a[href*='tag/'], a[href*='category/']").mapNotNull { a ->
            val name = cleanText(a.text)
            if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, a.attr("href")))
        }.distinctBy { it.value }

        // 相关推荐：复用列表解析器，然后把自己剔掉
        val related = XChinaListParser()
            .parse(html, options)
            .filter { it.id != videoId }
            .take(12)

        return VideoDetail(
            brief = VideoBrief(
                id = videoId,
                title = title,
                cover = cover,
                sourceSite = XChinaListParser.SITE_ID,
            ),
            description = description,
            actors = actors,
            tags = tags,
            related = related,
        )
    }

    companion object {
        val MODEL_ID = Regex("""/(?:model|actor)/([A-Za-z0-9]+)""")

        fun modelIdFromLink(link: String): String? = MODEL_ID.find(link)?.groupValues?.getOrNull(1)
    }
}

data class XChinaCommentsPage(
    val comments: List<VideoComment>,
    val totalCount: Int? = null,
    val page: Int = 1,
    val totalPages: Int = 1,
)

/** 从视频详情页 HTML 解析评论区块（含 `?cpage=N` 分页）。 */
class XChinaCommentsParser : BaseParser<XChinaCommentsPage> {

    override fun parse(html: String, options: ParseOptions): XChinaCommentsPage {
        val doc = Html.parse(html)
        val jump = doc.selectFirst("form[data-comment-jump]")
        val totalPages = jump?.attr("data-total")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val page = jump?.attr("data-current")?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val totalCount = doc.selectFirst("[data-comment-total]")
            ?.text
            ?.trim()
            ?.toIntOrNull()
        val comments = parseXChinaComments(doc)
        return XChinaCommentsPage(
            comments = comments,
            totalCount = totalCount,
            page = page,
            totalPages = totalPages,
        )
    }

    companion object {
        fun parseXChinaComments(doc: com.javrocket.avplay.parser.HtmlDoc): List<VideoComment> {
            return doc.select("section#comments li[data-comment], li[data-comment]").mapNotNull { li ->
                val id = li.attr("data-comment").trim()
                if (id.isBlank()) return@mapNotNull null
                val body = li.selectFirst(".min-w-0.flex-1") ?: li
                val author = cleanText(body.selectFirst("span.font-medium")?.text)
                val time = cleanText(body.selectFirst("time")?.text)
                val floor = cleanText(
                    body.selectFirst("span.tabular-nums.text-subtle")?.text
                        ?: body.selectFirst("span.ml-auto")?.text,
                )
                val content = cleanText(body.selectFirst("p")?.text)
                if (content.isBlank() && author.isBlank()) return@mapNotNull null
                val likes = body.selectFirst("button[data-vote=like] span")?.text?.trim()?.toIntOrNull() ?: 0
                val dislikes = body.selectFirst("button[data-vote=dislike] span")?.text?.trim()?.toIntOrNull() ?: 0
                VideoComment(
                    id = id,
                    author = author.ifBlank { "匿名色友" },
                    time = time,
                    floor = floor,
                    content = content,
                    likes = likes,
                    dislikes = dislikes,
                )
            }
        }
    }
}
