package com.javrocket.avplay

import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.parser.cleanText
import com.javrocket.avplay.parser.extractGroup
import com.javrocket.avplay.parser.originOf
import com.javrocket.avplay.parser.queryParamOf
import com.javrocket.avplay.parser.stripSiteSuffix
import kotlin.test.Test
import kotlin.test.assertEquals

/** 解析原语的边界测试 —— 站点改版时最先生病的就是这几个函数。 */
class ParserUtilsTest {

    @Test
    fun absolutize_handles_absolute_relative_and_protocol_relative() {
        assertEquals("https://x.com/a.m3u8", absolutize("https://x.com/cn", "https://x.com/a.m3u8"))
        assertEquals("https://x.com/a.jpg", absolutize("https://x.com/cn", "/a.jpg"))
        assertEquals("https://x.com/cn/a.jpg", absolutize("https://x.com/cn/", "a.jpg"))
        assertEquals("https://pics.dmm.co.jp/a.jpg", absolutize("https://x.com", "//pics.dmm.co.jp/a.jpg"))
        assertEquals("/weird//path", absolutize("", "/weird//path"), "没有 baseUrl 时原样返回")
    }

    @Test
    fun query_param_extraction() {
        assertEquals("ABC-123", queryParamOf("/cn/?v=ABC-123", "v"))
        assertEquals("X", queryParamOf("/cn/?a=1&v=X&b=2", "v"))
        assertEquals(null, queryParamOf("/cn/?a=1", "v"))
        assertEquals(null, queryParamOf("", "v"))
    }

    @Test
    fun origin_of_url() {
        assertEquals("https://pics.dmm.co.jp", originOf("https://pics.dmm.co.jp/mono/a.jpg"))
        assertEquals("", originOf("/relative/a.jpg"))
    }

    @Test
    fun clean_text_collapses_whitespace_and_nbsp() {
        assertEquals("标题 A", cleanText("  标题\u00A0 A "))
        assertEquals("a b c", cleanText("a\n\tb  c"))
        assertEquals("", cleanText(null))
    }

    @Test
    fun extract_group_from_pattern() {
        val re = Regex("""video/id-([A-Za-z0-9_-]+)""")
        assertEquals("12345", extractGroup("/video/id-12345", re))
        assertEquals(null, extractGroup("/video/id-", re))
    }

    @Test
    fun strip_site_suffix() {
        assertEquals("标题", stripSiteSuffix("标题 - JavLibrary", "JavLibrary", "- JavLibrary"))
        assertEquals("标题", stripSiteSuffix("标题 | XChina", "XChina"))
    }
}
