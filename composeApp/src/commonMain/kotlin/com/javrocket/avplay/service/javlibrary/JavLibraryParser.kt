package com.javrocket.avplay.service.javlibrary

import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.parser.BaseParser
import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.parser.cleanText
import com.javrocket.avplay.parser.firstNonBlank
import com.javrocket.avplay.parser.queryParamOf
import com.javrocket.avplay.parser.stripSiteSuffix

/**
 * JavLibrary 是纯网页站，但 DOM 结构非常稳定（它十几年没换过骨架），
 * 所以这里可以用「固定 id 选择器」精确取字段，比链接驱动更准。
 *
 * 真实 DOM 约定（原实现也是按这些 id 取的）：
 *   #video_title / #video_id / #video_date / #video_review / #video_jacket_img
 *   #video_director / #video_maker / #video_label / #video_genres / #video_cast
 *   列表项链接形如 /?v=ABC-123
 */
class JavLibraryListParser : BaseParser<List<VideoBrief>> {

    override fun parse(html: String, options: ParseOptions): List<VideoBrief> {
        val doc = Html.parse(html)
        val out = LinkedHashMap<String, VideoBrief>()

        // ⚠️ 2026-11 实测：站点已把详情链接从 `?v=CODE` 改成伪静态 `./CODE.html`。
        // 旧的选择器 `a[href*='?v=']` 在新页面上匹配 0 条 —— 表现就是"首页有标题、列表却空"，
        // 而且没有任何报错，是最难查的那种。真实结构：
        //   <div class="video" id="vid_CODE">
        //     <a href="./CODE.html" title="CODE 标题">
        //       <div class="id">CODE</div><img src="..."><div class="title">标题</div>
        //     </a>
        //     <div class="toolbar">…收藏/已看/拥有…</div>
        //   </div>
        doc.select("div.video").forEach { div ->
            val anchor = div.selectFirst("a[href]")
            val href = anchor?.attr("href").orEmpty()

            // 两个标识必须分开！实测（jl_list.html 真实页）：
            //   详情链接是 `./javme3yw6u.html`  —— 站点内部 hash，**详情页只能用它打开**；
            //   `div.id` 的文本是 `SNOS-359`   —— 番号，展示和聚合搜索用；
            //   `div.video` 的 `id` 属性是 `vid_javme3yw6u` —— 同上那个 hash。
            // Phase 3 曾把 href 误当成 ./CODE.html，结果番号被当 URL 用，详情稳定 404。
            val hashFromHref = href.substringAfterLast('/').removeSuffix(".html").substringBefore('?')
                .takeIf { it.isNotBlank() && !it.startsWith("?") }
            val attrId = div.attr("id").removePrefix("vid_").trim().takeIf { it.isNotBlank() }
            val listedCode = cleanText(div.selectFirst("div.id")?.text)
            val legacyQuery = queryParamOf(href, "v")

            // detailId = 打开详情页用的标识；code = 番号
            val detailId = attrId ?: hashFromHref ?: listedCode.takeIf { it.isNotBlank() } ?: legacyQuery
                ?: return@forEach
            val code = listedCode.takeIf { it.isNotBlank() } ?: detailId

            if (out.containsKey(detailId)) return@forEach

            val img = div.selectFirst("img")
            val codeText = code

            // div.title 是纯标题；a 的 title 属性带番号前缀（"SNOS-359 xxx"），需要削掉
            val rawTitle = firstNonBlank(
                cleanText(div.selectFirst("div.title")?.text),
                cleanText(anchor?.attr("title")),
            )
            val title = rawTitle.removePrefix(codeText).trim()
                .ifBlank { cleanText(anchor?.attr("title")) }
                .ifBlank { codeText }

            val cover = img?.attr("src")
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?.let { absolutize(options.baseUrl, it) }

            out[detailId] = VideoBrief(
                id = detailId,
                title = title,
                cover = cover,
                code = codeText,
                sourceSite = SITE_ID,
            )
        }

        // 兜底：极少数页面（搜索无结果跳转页）仍是旧结构
        if (out.isEmpty()) {
            doc.select("a[href*='?v=']").forEach { a ->
                val href = a.attr("href")
                val code = queryParamOf(href, "v") ?: return@forEach
                if (out.containsKey(code)) return@forEach
                val img = a.selectFirst("img")
                out[code] = VideoBrief(
                    id = code,
                    title = firstNonBlank(cleanText(a.text), cleanText(img?.attr("title")), code),
                    cover = img?.attr("src")?.takeIf { it.isNotBlank() }
                        ?.let { absolutize(options.baseUrl, it) },
                    code = code,
                    sourceSite = SITE_ID,
                )
            }
        }

        return out.values.toList()
    }

    companion object {
        const val SITE_ID = "javlibrary"
    }
}

/** “最受欢迎女优”页：只产出名字+链接，交给 UI 当筛选入口。 */
class JavLibraryStarParser : BaseParser<List<ValueLink>> {
    override fun parse(html: String, options: ParseOptions): List<ValueLink> {
        val doc = Html.parse(html)
        return doc.select("a[href*='vl_star.php']")
            .mapNotNull { a ->
                val name = cleanText(a.text)
                if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, a.attr("href")))
            }
            .distinctBy { it.value }
    }
}

class JavLibraryDetailParser(private val code: String) : BaseParser<VideoDetail> {

    override fun parse(html: String, options: ParseOptions): VideoDetail {
        val doc = Html.parse(html)

        val title = firstNonBlank(
            cleanText(doc.selectFirst("#video_title")?.text),
            cleanText(doc.selectFirst("meta[property='og:title']")?.attr("content")),
            stripSiteSuffix(doc.title, "JavLibrary", "- JAVLibrary"),
            code,
        )

        val cover = firstNonBlank(
            doc.selectFirst("#video_jacket_img")?.attr("src"),
            doc.selectFirst("meta[property='og:image']")?.attr("content"),
        ).takeIf { it.isNotBlank() }?.let { absolutize(options.baseUrl, it) }

        // 评分形如 "(9.5)"，从 .score 里抠数字
        val rating = cleanText(doc.selectFirst("div.score, span.score")?.text)
            .let { Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }

        val description = firstNonBlank(
            cleanText(doc.selectFirst("meta[property='og:description']")?.attr("content")),
            cleanText(doc.selectFirst("#video_review")?.text),
        )

        val actors = doc.select("a[href*='vl_star.php']").mapNotNull { a ->
            val name = cleanText(a.text)
            if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, a.attr("href")))
        }.distinctBy { it.value }

        val tags = doc.select("a[href*='vl_tag.php']").mapNotNull { a ->
            val name = cleanText(a.text)
            if (name.isBlank()) null else ValueLink(name, absolutize(options.baseUrl, a.attr("href")))
        }.distinctBy { it.value }

        val related = JavLibraryListParser()
            .parse(html, options)
            .filter { it.id != code }
            .take(12)

        // 传入的 code 是内部 hash，页面上 #video_id 才是番号（SNOS-359）。
        // 展示与聚合都必须用番号，所以这里优先取页面的。
        // 注意该节点文本带字段名前缀（"识别码: SNOS-359" 或 "ID: SNOS-359"），要削掉。
        val displayCode = firstNonBlank(
            cleanText(doc.selectFirst("#video_id")?.text).substringAfterLast(':').substringAfterLast('：').trim(),
            code,
        )

        return VideoDetail(
            brief = VideoBrief(
                id = code,
                title = title,
                cover = cover,
                code = displayCode,
                rating = rating,
                sourceSite = JavLibraryListParser.SITE_ID,
            ),
            description = description,
            actors = actors,
            tags = tags,
            related = related,
        )
    }
}
