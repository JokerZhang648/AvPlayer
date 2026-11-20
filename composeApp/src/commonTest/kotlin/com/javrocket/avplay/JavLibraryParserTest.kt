package com.javrocket.avplay

import com.javrocket.avplay.parser.ParseOptions
import com.javrocket.avplay.service.javlibrary.JavLibraryDetailParser
import com.javrocket.avplay.service.javlibrary.JavLibraryListParser
import com.javrocket.avplay.service.javlibrary.JavLibraryStarParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JavLibrary 解析器测试。
 * 这个站的 DOM 十几年没换过，所以直接按真实 id（#video_title / #video_jacket_img…）断言。
 */
class JavLibraryParserTest {

    /**
     * 2026-11 实测结构：详情链接改成了伪静态 `./CODE.html`，外层是 `div.video`。
     * ⚠️ 关键细节：`div.video` 的 `id` 属性是**站点内部 hash**（`vid_javme3yw6u`），
     * 番号在 `div.id` 的文本里（`SNOS-359`）。所以 fixture 里故意让两者不同 ——
     * 用内部 hash 当番号去拼详情 URL 会 404。
     */
    private val listHtml = """
        <html><body>
          <div class="videos">
            <div class="video" id="vid_javme3yw6u">
              <a href="./ABC-123.html" title="ABC-123 标题">
                <img src="//pics.dmm.co.jp/mono/movie/adult/abc123/abc123ps.jpg">
                <div class="id">ABC-123</div>
                <div class="title">标题</div>
              </a>
            </div>
            <div class="video" id="vid_javme33sty">
              <a href="./DEF-456.html" title="DEF-456 标题">
                <img src="//pics.dmm.co.jp/mono/movie/adult/def456/def456ps.jpg">
                <div class="id">DEF-456</div>
              </a>
            </div>
            <div class="video" id="vid_javme3yw6u">
              <a href="./ABC-123.html" title="ABC-123 重复">
                <div class="id">ABC-123</div>
              </a>
            </div>
          </div>
        </body></html>
    """.trimIndent()

    /** 旧结构（搜索无结果跳转页仍是这种）：`?v=CODE`。parser 里保留了这条兜底路径。 */
    private val legacyListHtml = """
        <html><body>
          <div class="videos">
            <a href="https://www.javlibrary.com/cn/?v=GHI-789">GHI-789 旧结构标题</a>
          </div>
        </body></html>
    """.trimIndent()

    private val detailHtml = """
        <html><body>
          <h3 id="video_title">ABC-123 完整标题</h3>
          <img id="video_jacket_img" src="//pics.dmm.co.jp/mono/movie/adult/abc123/abc123pl.jpg">
          <div class="score">(9.5)</div>
          <span id="video_id">ABC-123</span>
          <a href="https://www.javlibrary.com/cn/vl_star.php?s=xyz">女优甲</a>
          <a href="https://www.javlibrary.com/cn/vl_tag.php?c=tag1">标签一</a>
          <a href="https://www.javlibrary.com/cn/?v=ZZZ-999">相关</a>
        </body></html>
    """.trimIndent()

    private val starHtml = """
        <html><body>
          <a href="https://www.javlibrary.com/cn/vl_star.php?s=a">女优A</a>
          <a href="https://www.javlibrary.com/cn/vl_star.php?s=b">女优B</a>
          <a href="https://www.javlibrary.com/cn/?v=X-1">不是女优</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parses_list_of_pretty_url() {
        val items = JavLibraryListParser()
            .parse(listHtml, ParseOptions(baseUrl = "https://www.javlibrary.com/cn/"))

        assertEquals(2, items.size, "同内部 hash 应去重: $items")
        val abc = items.first { it.code == "ABC-123" }
        assertEquals("javme3yw6u", abc.id, "id 必须是详情页要用的内部 hash，用番号拼 URL 会 404")
        assertEquals("ABC-123", abc.code, "code 必须是番号（展示 + 聚合搜索用）")
        assertEquals("标题", abc.title, "title 属性里的番号前缀应被削掉（列表里只需纯标题）: $abc")
        assertEquals(
            "https://pics.dmm.co.jp/mono/movie/adult/abc123/abc123ps.jpg",
            abc.cover,
            "协议相对地址要补 https",
        )
    }

    @Test
    fun parses_legacy_query_url_as_fallback() {
        val items = JavLibraryListParser()
            .parse(legacyListHtml, ParseOptions(baseUrl = "https://www.javlibrary.com/cn/"))

        assertEquals(1, items.size, "旧结构应仍能被兜底解析: $items")
        assertEquals("GHI-789", items.first().code)
    }

    @Test
    fun parses_detail_fields() {
        val detail = JavLibraryDetailParser("ABC-123")
            .parse(detailHtml, ParseOptions(baseUrl = "https://www.javlibrary.com/cn/"))

        assertEquals("ABC-123 完整标题", detail.brief.title)
        assertEquals(9.5, detail.brief.rating)
        assertEquals(
            "https://pics.dmm.co.jp/mono/movie/adult/abc123/abc123pl.jpg",
            detail.brief.cover,
        )
        assertEquals(listOf("女优甲"), detail.actors.map { it.value })
        assertEquals(listOf("标签一"), detail.tags.map { it.value })
        assertTrue(detail.related.none { it.code == "ABC-123" })
    }

    @Test
    fun parses_star_page() {
        val stars = JavLibraryStarParser()
            .parse(starHtml, ParseOptions(baseUrl = "https://www.javlibrary.com/cn/"))

        assertEquals(listOf("女优A", "女优B"), stars.map { it.value })
    }
}
