package com.javrocket.avplay

import com.javrocket.avplay.parser.Html
import com.javrocket.avplay.parser.absolutize
import com.javrocket.avplay.service.xchina.XChinaService
import com.javrocket.avplay.site.aggregator.parsePlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** XChina 详情页播放抽取：与 [com.javrocket.avplay.service.xchina.XChinaService] 内逻辑对齐的单测。 */
class XChinaPlaybackTest {

    @Test
    fun player_config_on_detail_page_yields_hls_without_suffix() {
        val html = """
            <html><body>
              <script>
                window.PLAYER_CONFIG = {"m3u8":"https:\/\/helvid.com\/m\/L3Yv?e=1\u0026h=sig"}
              </script>
            </body></html>
        """.trimIndent()
        val urls = parsePlayerConfig(html).map { it.first }
        assertEquals(1, urls.size)
        assertTrue(urls.first().contains("h=sig"), "签名应还原: ${urls.first()}")
    }

    @Test
    fun native_player_data_src_relative_m3u8() {
        val base = "https://xchina.co/video/6ae4aea8d28b8"
        val html = """
            <div id="video-player" data-src="/hls/6ae4aea8d28b8/master.m3u8"></div>
        """.trimIndent()
        val url = XChinaService.extractNativePlayerSrc(html, base)
        assertEquals("https://xchina.co/hls/6ae4aea8d28b8/master.m3u8", url)
    }

    @Test
    fun collects_iframe_and_embed_link_entries() {
        val base = "https://xchina.co/video/abc123"
        val html = """
            <iframe src="//upload18.to/play/index/foo"></iframe>
            <a href="https://streamtape.com/e/xyz">备用</a>
        """.trimIndent()
        val entries = embedEntriesLikeXChina(html, base)
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.contains("upload18.to/play") })
        assertTrue(entries.any { it.contains("streamtape.com") })
    }

    private fun embedEntriesLikeXChina(html: String, pageUrl: String): List<String> {
        val seen = LinkedHashSet<String>()
        val doc = Html.parse(html)
        for (node in doc.select("iframe[src], iframe[data-src]")) {
            val raw = node.attr("src").ifBlank { node.attr("data-src") }.trim()
            val abs = absolutize(pageUrl, raw)
            if (abs.startsWith("http")) seen.add(abs)
        }
        val embedHosts = listOf(
            "upload18.", "helvid.", "dood", "streamtape.", "earnvid", "vidhide", "voe.",
            "playmogo.", "/play/index/", "/embed/",
        )
        for (node in doc.select("a[href]")) {
            val abs = absolutize(pageUrl, node.attr("href"))
            if (!abs.startsWith("http")) continue
            val lower = abs.lowercase()
            if (embedHosts.any { lower.contains(it) }) seen.add(abs)
        }
        return seen.toList()
    }
}