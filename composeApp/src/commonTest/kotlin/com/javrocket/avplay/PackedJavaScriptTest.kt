package com.javrocket.avplay

import com.javrocket.avplay.site.PackedJavaScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Packer 解包的回归测试。
 * 这段算法是全项目最容易写错的地方，必须有单测——原实现没有，这是我们的改进。
 */
class PackedJavaScriptTest {

    // 一段最小但结构完整的 Dean Edwards packer
    private val sample = """eval(function(p,a,c,k,e,d){e=function(c){return(c<a?'':e(parseInt(c/a)))+((c=c%a)>35?String.fromCharCode(c+29):c.toString(36))};return p}('0 1("2://3.4/5.6")',10,8,'var|src|https|host|com|path|m3u8|extra'.split('|'),0,{}))"""

    @Test
    fun unpacks_media_url_from_packed_js() {
        val results = PackedJavaScript.unpackAll(sample)
        assertEquals(1, results.size)
        assertTrue(
            results.first().contains("https://host.com/path.m3u8"),
            "解包结果应包含还原后的直链，实际是: ${results.first()}",
        )
    }

    @Test
    fun returns_empty_for_plain_html() {
        val html = "<html><body><video src=\"https://cdn.example.com/a.m3u8\"></video></body></html>"
        assertTrue(PackedJavaScript.unpackAll(html).isEmpty())
    }

    @Test
    fun handles_multiple_blocks() {
        val twice = sample + "\n\n" + sample
        assertEquals(2, PackedJavaScript.unpackAll(twice).size)
    }

    @Test
    fun rejects_illegal_radix() {
        val broken = """eval(function(p,a,c,k,e,d){return p}('0 1',1,2,'a|b'.split('|'),0,{}))"""
        assertTrue(PackedJavaScript.unpackAll(broken).isEmpty(), "radix<2 应被放弃")
    }
}
