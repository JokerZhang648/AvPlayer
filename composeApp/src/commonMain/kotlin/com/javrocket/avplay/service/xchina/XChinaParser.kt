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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
            val meta = parseListCardMeta(a, options.baseUrl)

            val actorLink = meta.actorLink
                ?: actorLinkFromHtmlSegment(html, id, meta.actorLabel, options.baseUrl)
            val dateLabel = meta.dateLabel.ifBlank { dateLabelFromHtmlSegment(html, id) }
            val subtitle = if (actorLink != null) {
                when {
                    dateLabel.isNotBlank() -> dateLabel
                    meta.footLine?.contains(" · ") == true -> meta.footLine.substringAfter(" · ").trim()
                    else -> meta.footLine
                }
            } else {
                meta.footLine
            }
            val brief = VideoBrief(
                id = id,
                title = title,
                cover = cover,
                sourceSite = SITE_ID,
                duration = meta.duration,
                code = meta.seriesCode,
                category = meta.category,
                subtitle = subtitle,
                actorLink = actorLink,
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
        private val CARD_DATE = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private val SERIES_CODE = Regex("""^[A-Z0-9]{2,}-[A-Z0-9]{2,}""", RegexOption.IGNORE_CASE)

        private fun parseListCardMeta(a: com.javrocket.avplay.parser.HtmlNode, baseUrl: String): ListCardMeta {
            val pills = a.select("span.cover-pill").map { cleanText(it.text) }.filter { it.isNotBlank() }
            val duration = pills.firstOrNull { CARD_DURATION.matches(it) }
            val seriesCode = pills.firstOrNull { SERIES_CODE.matches(it) }
            val category = pills.firstOrNull { pill ->
                pill != duration && pill != seriesCode && !SERIES_CODE.matches(pill)
            }
            val foot = a.selectFirst("span[class*='text-xs'][class*='items-center']")
                ?: a.select("span[class*='text-xs']").lastOrNull()
            val actor = cleanText(
                foot?.selectFirst("a[href*='/model/'], a[href*='/actor/']")?.text
                    ?: foot?.selectFirst("[class*=truncate]")?.text,
            )
            val date = cleanText(
                foot?.selectFirst("[class*=tabular-nums]")?.text
                    ?: a.select("span[class*=tabular-nums]").map { cleanText(it.text) }
                        .firstOrNull { CARD_DATE.matches(it) },
            )
            val actorLink = modelAnchorInCard(a, baseUrl, actor)
            val footLine = when {
                actorLink != null && date.isNotBlank() -> date
                actorLink != null -> null
                actor.isNotBlank() && date.isNotBlank() -> "$actor · $date"
                actor.isNotBlank() -> actor
                date.isNotBlank() -> date
                else -> cleanText(foot?.text).takeIf { it.isNotBlank() && !it.contains("line-clamp") }
            }
            return ListCardMeta(duration, seriesCode, category, footLine, actorLink, actor, date)
        }

        private fun videoCardHtmlSegment(html: String, videoId: String): String? = Regex(
            """/video/$videoId\b[\s\S]*?(?=/video/[A-Za-z0-9]+\b|$)""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.value

        private fun dateLabelFromHtmlSegment(html: String, videoId: String): String {
            val segment = videoCardHtmlSegment(html, videoId) ?: return ""
            val fromTabular = Regex(
                """class=["'][^"']*tabular-nums[^"']*["'][^>]*>([^<]+)<""",
                RegexOption.IGNORE_CASE,
            ).find(segment)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (CARD_DATE.matches(fromTabular)) return fromTabular
            return CARD_DATE.find(segment)?.value.orEmpty()
        }

        private fun actorLinkFromHtmlSegment(
            html: String,
            videoId: String,
            actorName: String,
            baseUrl: String,
        ): ValueLink? {
            val segment = videoCardHtmlSegment(html, videoId) ?: return null
            val href = Regex(
                """href=["']([^"']*/(?:model|actor)/[A-Za-z0-9]+)["']""",
                RegexOption.IGNORE_CASE,
            ).findAll(segment).map { it.groupValues[1] }.firstOrNull() ?: return null
            val nameFromTag = Regex(
                """class=["'][^"']*truncate[^"']*["'][^>]*>([^<]+)<""",
                RegexOption.IGNORE_CASE,
            ).find(segment)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val name = firstNonBlank(actorName, nameFromTag, href.substringAfterLast('/'))
            return ValueLink(name, absolutize(baseUrl, href))
        }

        private fun modelAnchorInCard(
            card: com.javrocket.avplay.parser.HtmlNode,
            baseUrl: String,
            fallbackName: String,
        ): ValueLink? {
            card.select("a[href*='/model/'], a[href*='/actor/']").forEach { inner ->
                val href = inner.attr("href").trim()
                if (href.isBlank() || href.contains("/video/")) return@forEach
                if (!XChinaDetailParser.MODEL_ID.containsMatchIn(href)) return@forEach
                val name = cleanText(
                    inner.selectFirst("[class*=truncate]")?.text ?: inner.text,
                )
                if (name.isBlank()) return@forEach
                return ValueLink(name, absolutize(baseUrl, href))
            }
            val id = XChinaDetailParser.MODEL_ID.find(card.html)?.groupValues?.getOrNull(1)
                ?: return null
            val name = fallbackName.takeIf { it.isNotBlank() } ?: return null
            val path = if (card.html.contains("/model/")) "/model/$id" else "/actor/$id"
            return ValueLink(name, absolutize(baseUrl, path))
        }

        private fun VideoBrief.mergeListBrief(fresh: VideoBrief): VideoBrief = copy(
            title = title.ifBlank { fresh.title },
            cover = cover ?: fresh.cover,
            duration = duration ?: fresh.duration,
            code = code ?: fresh.code,
            category = category ?: fresh.category,
            subtitle = subtitle ?: fresh.subtitle,
            actorLink = actorLink ?: fresh.actorLink,
        )
    }
}

private data class ListCardMeta(
    val duration: String?,
    val seriesCode: String?,
    val category: String?,
    val footLine: String?,
    val actorLink: ValueLink?,
    val actorLabel: String,
    val dateLabel: String,
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

        // 新站模特页 /model/<hash>；旧站 /actor/<hash>；再兜底 JSON-LD VideoObject.actor
        val fromAnchors = doc.select("a[href*='/model/'], a[href*='/actor/']").mapNotNull { a ->
            val href = a.attr("href")
            if (href.contains("/video/")) return@mapNotNull null
            val name = cleanText(a.text)
            if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, href))
        }
        val fromLd = parseLdJsonActors(html, options.baseUrl)
        val actors = (fromAnchors + fromLd).distinctBy { it.link.ifBlank { it.value } }

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

        /** 演员主页可能是 `/model/<hash>` 或 `/actor/<hash>`，跟链接走。 */
        fun personRouteFromLink(link: String): XChinaPersonRoute = when {
            link.contains("/actor/", ignoreCase = true) -> XChinaPersonRoute.Actor
            else -> XChinaPersonRoute.Model
        }

        private val ldJson = Json { ignoreUnknownKeys = true }

        private val LD_JSON_BLOCK =
            Regex("""<script[^>]*type=["']application/ld\+json["'][^>]*>(.*?)</script>""", RegexOption.IGNORE_CASE)

        fun parseLdJsonActors(html: String, baseUrl: String): List<ValueLink> {
            val out = mutableListOf<ValueLink>()
            LD_JSON_BLOCK.findAll(html).forEach { match ->
                val raw = match.groupValues[1].trim()
                if (raw.isBlank()) return@forEach
                val root = runCatching { ldJson.parseToJsonElement(raw) }.getOrNull() ?: return@forEach
                collectLdActors(root, baseUrl, out)
            }
            return out.distinctBy { it.link.ifBlank { it.value } }
        }

        private fun collectLdActors(el: kotlinx.serialization.json.JsonElement, baseUrl: String, out: MutableList<ValueLink>) {
            when (el) {
                is JsonObject -> {
                    val type = el["@type"]?.jsonPrimitive?.content.orEmpty()
                    if (type.equals("VideoObject", ignoreCase = true)) {
                        el["actor"]?.let { appendLdPeople(it, baseUrl, out) }
                    }
                    el.forEach { (_, v) -> collectLdActors(v, baseUrl, out) }
                }
                is JsonArray -> el.forEach { collectLdActors(it, baseUrl, out) }
                else -> Unit
            }
        }

        private fun appendLdPeople(el: kotlinx.serialization.json.JsonElement, baseUrl: String, out: MutableList<ValueLink>) {
            when (el) {
                is JsonArray -> el.forEach { appendLdPeople(it, baseUrl, out) }
                is JsonObject -> {
                    val type = el["@type"]?.jsonPrimitive?.content.orEmpty()
                    if (type.isNotBlank() && !type.equals("Person", ignoreCase = true)) return
                    val name = el["name"]?.jsonPrimitive?.content?.trim().orEmpty()
                    val url = el["url"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (name.isBlank() && url.isBlank()) return
                    val link = if (url.isNotBlank()) absolutize(baseUrl, url) else ""
                    if (link.isNotBlank() && !MODEL_ID.containsMatchIn(link)) return
                    out += ValueLink(
                        value = name.ifBlank { link.substringAfterLast('/') },
                        link = link,
                    )
                }
                else -> Unit
            }
        }
    }
}

enum class XChinaPersonRoute {
    Model,
    Actor,
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
