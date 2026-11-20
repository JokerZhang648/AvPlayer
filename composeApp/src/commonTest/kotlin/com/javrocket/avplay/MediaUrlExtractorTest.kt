package com.javrocket.avplay

import com.javrocket.avplay.site.MediaUrlExtractor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUrlExtractorTest {

    @Test
    fun finds_m3u8_and_mp4() {
        val html = """
            <script>
              var src = "https://cdn.example.com/hls/index.m3u8";
              var back = 'https://cdn.example.com/fallback.mp4';
            </script>
        """.trimIndent()

        val urls = MediaUrlExtractor.findMediaUrls(html)
        assertEquals(2, urls.size)
        assertTrue(urls.any { it.endsWith("index.m3u8") })
        assertTrue(urls.any { it.endsWith("fallback.mp4") })
    }

    @Test
    fun drops_sample_videos_and_blob() {
        val html = """
            <img src="blob:https://page/123">
            <a href="https://test-videos.co.uk/vids/bigbuckbunny/mp4/h264/720/Big_Buck_Bunny_720_10s.mp4"></a>
            <video src="https://real.example.com/a.m3u8"></video>
        """.trimIndent()

        val urls = MediaUrlExtractor.findMediaUrls(html)
        assertEquals(1, urls.size, "示例视频与 blob 都应被过滤，实际: $urls")
        assertTrue(urls.first().contains("real.example.com"))
    }

    @Test
    fun ranks_m3u8_above_mp4() {
        val html = """
            <a href="https://a.example.com/x.mp4">mp4</a>
            <a href="https://b.example.com/y.m3u8">hls</a>
        """.trimIndent()

        val ranked = MediaUrlExtractor.rankedMediaUrls(html)
        assertTrue(ranked.first().endsWith(".m3u8"), "m3u8 应排在 mp4 前面，实际: $ranked")
    }

    @Test
    fun unescapes_js_escaped_slashes() {
        assertEquals("https://x.com/a", MediaUrlExtractor.unescapeUrl("https:\\/\\/x.com\\/a"))
    }

    @Test
    fun rejects_blob_scheme_directly() {
        assertFalse(MediaUrlExtractor.isPlayableMediaUrl("blob:https://page/abc"))
        assertTrue(MediaUrlExtractor.isPlayableMediaUrl("https://x.com/a.m3u8"))
    }

    /**
     * 真实站点（upload18.to 播放页）的形态：地址写在 `window.PLAYER_CONFIG` 里，
     * 签名参数用 `\u0026` 转义。若允许正则在反斜杠处截断，得到的会是"没有签名的半截地址"，
     * 播的时候必然 403 —— 所以这条用例守住"签名参数不能丢"。
     */
    @Test
    fun keeps_signature_when_json_escapes_ampersand() {
        val html = """
            <script>
              window.PLAYER_CONFIG = {"theme":"glass","m3u8":"https:\/\/helvid.com\/m\/L3Yv\/playlist.m3u8?e=1795170185\u0026h=rFzy0Tll9R2y"}
            </script>
        """.trimIndent()

        val urls = MediaUrlExtractor.findMediaUrls(html)
        assertEquals(1, urls.size, "应只解析出一个地址，实际: $urls")
        val url = urls.first()
        assertTrue(url.contains("e=1795170185"), "过期参数丢了: $url")
        assertTrue(url.contains("h=rFzy0Tll9R2y"), "签名参数被截断了: $url")
        assertFalse(url.contains("\\u0026"), "转义未还原: $url")
    }
}
