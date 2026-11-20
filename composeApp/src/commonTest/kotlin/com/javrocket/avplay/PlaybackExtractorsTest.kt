package com.javrocket.avplay

import com.javrocket.avplay.site.MediaUrlExtractor
import com.javrocket.avplay.site.aggregator.KnownPlayerExtractor
import com.javrocket.avplay.site.aggregator.LoadedPlaybackPage
import com.javrocket.avplay.site.aggregator.NestedFrameExtractor
import com.javrocket.avplay.site.aggregator.PlaybackTarget
import com.javrocket.avplay.site.aggregator.PlaybackTargetExtraction
import com.javrocket.avplay.site.aggregator.parsePlayerConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackExtractorsTest {

    private fun page(url: String, html: String) = LoadedPlaybackPage(
        requestedUrl = url,
        finalUrl = url,
        html = html,
    )

    private fun target(url: String) = PlaybackTarget(id = "HRD-331", title = "HRD-331", url = url, referrer = "")

    /**
     * 实测站点形态（2026-11 抓的真页）：AVDB 的 link_embed 页。
     *
     * 注意地址是 **无后缀** 的：路径 base64、靠 `?e=&h=` 签名，整页唯一出现 m3u8 的地方
     * 是 JSON 的键名。所以只按后缀抠链必然 0 命中 —— 这条用例锁住"按字段名取"。
     */
    private val upload18Html = """
        <html><head><script>
          window.PLAYER_CONFIG = {"theme":"glass","m3u8":"https:\/\/helvid.com\/m\/L3YvSFJELTMzMS9hN2pxZ080NEE0N0lzTHd0?e=1795170500\u0026h=xHZHfQDm29a_ijnDjcaxYDuwqCC3bmajugzdbx9EgVQ\u0026v=hrd-331","alternate720":"","nativeHlsPreferred":false};
        </script></head><body><div id="player"></div></body></html>
    """.trimIndent()

    @Test
    fun known_player_host_extracts_media_from_player_config() {
        val url = "https://upload18.to/play/index/hrd-331"
        val result = KnownPlayerExtractor.extract(target(url), page(url, upload18Html))
        assertTrue(result is PlaybackTargetExtraction.Media, "应直接抽出媒体地址，实际: $result")
        val media = (result as PlaybackTargetExtraction.Media).urls.first()
        assertTrue(media.startsWith("https://helvid.com/m/"), "地址没还原: $media")
        assertTrue(media.contains("e=1795170500"), "过期参数丢了: $media")
        assertTrue(media.contains("h=xHZHfQDm29a_ijnDjcaxYDuwqCC3bmajugzdbx9EgVQ"), "签名丢了: $media")
        assertFalse(media.contains("\\"), "转义未还原: $media")
    }

    /** 无后缀地址证明：仅靠 `.m3u8` 后缀的正则抠不出来，必须走配置字段。 */
    @Test
    fun bare_media_url_is_not_found_by_suffix_regex() {
        assertTrue(
            MediaUrlExtractor.findMediaUrls(upload18Html).isEmpty(),
            "这条用例是为了说明为什么需要 parsePlayerConfig",
        )
        assertTrue(parsePlayerConfig(upload18Html).isNotEmpty())
    }

    @Test
    fun nested_frame_falls_back_to_media_before_drilling() {
        val url = "https://unknown.example.com/play/abc"
        val result = NestedFrameExtractor.extract(target(url), page(url, upload18Html))
        assertTrue(result is PlaybackTargetExtraction.Media, "未知站点也应先试抠链，实际: $result")
    }

    @Test
    fun nested_frame_returns_iframes_when_no_media() {
        val url = "https://unknown.example.com/play/abc"
        val html = """<html><body><iframe src="//cdn.example.com/embed/1"></iframe></body></html>"""
        val result = NestedFrameExtractor.extract(target(url), page(url, html))
        assertTrue(result is PlaybackTargetExtraction.Next, "应返回下一跳，实际: $result")
        val next = (result as PlaybackTargetExtraction.Next).targets
        assertEquals(1, next.size)
        assertTrue(next.first().url.startsWith("https://cdn.example.com/embed/1"), "相对协议地址没补全: ${next.first().url}")
    }
}
