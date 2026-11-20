package com.javrocket.avplay

import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.service.xchina.XChinaDetailParser
import com.javrocket.avplay.service.xchina.XChinaListParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * XChina 解析器测试。
 *
 * 2026-11 站点改版：卡片从 `/video/id-<num>` 变成 `/video/<hash>`，还多了
 * `/video-series/<id>`、`/video-category/<slug>` 两类**不是视频**的链接。
 * 这里盯三件容易翻车的事：
 * 1. 只认 `/video/<hash>`，别把系列页/分类页当视频
 * 2. 标题不能被封面上那层 `cover-pill` 浮层（分类/时长/评论数）污染
 * 3. 相对封面路径要补全成绝对地址
 */
class XChinaParserTest {

    private val listHtml = """
        <html><body>
          <div class="grid grid-cols-2 gap-x-3 gap-y-5">
            <a href="/video/6ae4aea8d28b8" class="group block">
              <span class="ph aspect-video rounded-xl">
                <img src="https://r2.xchina.download/cover/6ae4aea8d28b8.webp" width="400" alt="封面 alt 文本">
                <span class="cover-pill min-w-0">模特私拍</span>
                <span class="cover-pill min-w-0 tabular-nums">17:30</span>
              </span>
              <span class="mt-2 text-sm leading-snug group-hover:text-brand-600 line-clamp-2 h-[2.75em]">卡片标题一</span>
              <span class="mt-1 flex h-4 items-center gap-2 text-xs">模特甲</span>
            </a>
            <a href="/video-series/107" class="group block">系列页，不是视频</a>
            <a href="/video-category/jav">分类页，不是视频</a>
            <a href="/video/abcd1234" class="group block">
              <img src="/cover/abcd1234.webp" alt="">
              <span class="line-clamp-2">卡片标题二</span>
            </a>
            <a href="/video/abcd1234" class="group block">重复项</a>
          </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><head>
          <title>标题C - XChina</title>
          <meta property="og:title" content="标题C">
          <meta property="og:image" content="/cover/c.jpg">
          <meta property="og:description" content="这是简介">
        </head><body>
          <h1>标题C</h1>
          <a href="/actor/6441b582c90d4">女优甲</a>
          <a href="/video-tag/tag-one">标签一</a>
          <a href="/video/99999abc">相关一</a>
        </body></html>
    """.trimIndent()

    private fun parseList() =
        XChinaListParser().parse(listHtml, ParseOptions(baseUrl = "https://xchina.co", page = 1))

    @Test
    fun picks_only_real_video_links_and_dedupes() {
        val items = parseList()
        assertEquals(2, items.size, "只应抓到两个真实视频卡片（重复项合并）: ${items.map { it.id }}")
        assertTrue(items.any { it.id == "6ae4aea8d28b8" })
        assertTrue(items.any { it.id == "abcd1234" })
        assertTrue(items.all { it.sourceSite == "xchina" })
    }

    @Test
    fun title_ignores_overlay_pills() {
        val first = parseList().first { it.id == "6ae4aea8d28b8" }
        assertEquals("卡片标题一", first.title)
        assertTrue(!first.title.contains("模特私拍"), "浮层分类不能进标题，实际: ${first.title}")
        assertTrue(!first.title.contains("17:30"), "时长不能进标题，实际: ${first.title}")
    }

    @Test
    fun absolutizes_relative_cover() {
        val items = parseList()
        assertEquals("https://r2.xchina.download/cover/6ae4aea8d28b8.webp", items.first { it.id == "6ae4aea8d28b8" }.cover)
        assertEquals("https://xchina.co/cover/abcd1234.webp", items.first { it.id == "abcd1234" }.cover)
    }

    @Test
    fun parses_detail_with_meta_and_links() {
        val detail = XChinaDetailParser("6ae4aea8d28b8")
            .parse(detailHtml, ParseOptions(baseUrl = "https://xchina.co"))

        assertEquals("标题C", detail.brief.title)
        assertEquals("https://xchina.co/cover/c.jpg", detail.brief.cover)
        assertEquals("这是简介", detail.description)
        assertEquals(listOf("女优甲"), detail.actors.map { it.value }, "新站演员路径是 /actor/")
        assertEquals(listOf("标签一"), detail.tags.map { it.value }, "新站标签路径是 /video-tag/")
        assertTrue(detail.related.any { it.id == "99999abc" })
        assertTrue(detail.related.none { it.id == "6ae4aea8d28b8" }, "相关推荐不应包含自己")
    }
}
