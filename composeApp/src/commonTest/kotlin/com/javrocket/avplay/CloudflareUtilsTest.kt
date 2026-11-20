package com.javrocket.avplay

import com.javrocket.avplay.service.CloudflareUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareUtilsTest {

    private val challengePage = """
        <html><head><title>Just a moment...</title></head>
        <body>
          <div id="challenge-form"></div>
          <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/chl_page/v1"></script>
        </body></html>
    """.trimIndent()

    private val blockedPage = """
        <html><body>
          <h1>Sorry, you have been blocked</h3>
          <p>Why have I been blocked?</p>
          <p>Cloudflare Ray ID: 8f2a1c3d4e5f</p>
          <p>Performance &amp; security by Cloudflare</p>
        </body></html>
    """.trimIndent()

    private val normalPage = """{"code":0,"data":{"list":[{"id":"1","title":"hello"}]}}"""

    @Test
    fun detects_challenge_page() {
        assertNotNull(CloudflareUtils.challengeReason(challengePage))
        assertTrue(CloudflareUtils.isChallenge(challengePage))
    }

    @Test
    fun detects_blocked_page() {
        assertTrue(CloudflareUtils.isAccessBlocked(blockedPage))
        // 被封页不应再被判成“挑战页”，否则会误导用户去点验证
        assertFalse(CloudflareUtils.isChallenge(blockedPage))
    }

    @Test
    fun normal_json_is_not_challenge() {
        assertNull(CloudflareUtils.challengeReason(normalPage))
    }

    @Test
    fun cookie_names_are_the_three_key_ones() {
        assertEquals(listOf("cf_clearance", "__cf_bm", "_cfuvid"), CloudflareUtils.CF_COOKIE_NAMES)
    }
}
