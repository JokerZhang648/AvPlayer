package com.javrocket.avplay.service

import com.javrocket.avplay.net.platformLog

/**
 * Cloudflare 挑战判定 —— 逐条还原反编译里的 service/CloudflareUtils。
 *
 * 原实现用 Ksoup 解析 DOM 做元素级判断（#challenge-form / .cf-browser-verification 等）。
 * Phase 1 这里先用等价的正则/字符串规则覆盖同样的特征；等接入 Ksoup 后
 * 可以把 challengeReason 里的 DOM 规则替换成真正的元素查询（见 TODO）。
 *
 * 命中原因会写进日志，这就是原 App“诊断页能看到为什么判成 CF”的原因。
 */
object CloudflareUtils {

    /** 三个关键 Cookie：放行票据 / bot 管理 / 设备指纹。挑战页成功后要把它们回灌到 HTTP 层。 */
    val CF_COOKIE_NAMES: List<String> = listOf("cf_clearance", "__cf_bm", "_cfuvid")

    /**
     * “被封”判定：必须同时命中“拒绝文案”和“CF 指纹”，避免把普通报错页误判。
     */
    fun isAccessBlocked(body: String): Boolean {
        if (body.isBlank() || !body.trimStart().startsWith("<")) return false
        val lower = body.lowercase()

        val blockedMessage = lower.contains("sorry, you have been blocked") ||
            (lower.contains("you are unable to access") && lower.contains("why have i been blocked?"))

        val fingerprint = lower.contains("cloudflare ray id:") ||
            lower.contains("performance & security by cloudflare") ||
            lower.contains("cf-error-details")

        return blockedMessage && fingerprint
    }

    fun isChallenge(body: String): Boolean = challengeReason(body) != null

    /**
     * 返回命中的规则名；未命中返回 null。
     * 规则名与反编译原实现完全一致，方便和旧日志对照。
     */
    fun challengeReason(body: String): String? {
        if (body.isBlank()) return null
        if (!body.trimStart().startsWith("<")) return null
        if (isAccessBlocked(body)) return null

        val lower = body.lowercase()

        if (lower.contains("_cf_chl_opt")) return logRule(body, "_cf_chl_opt")
        if (lower.contains("one more step") && lower.contains("security check")) {
            return logRule(body, "one_more_step_security_check")
        }
        if (lower.contains("ray id:") && lower.contains("cloudflare")) {
            return logRule(body, "ray_id_cloudflare")
        }
        if (lower.contains("just a moment") && lower.contains("/cdn-cgi/")) {
            return logRule(body, "just_a_moment_cdn_cgi")
        }
        if (lower.contains("/cdn-cgi/l/chk_")) return logRule(body, "cdn_cgi_chk")

        // 标题级判断（原实现：document.title() + body.text()）
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.getOrNull(1)
            ?.lowercase()
            .orEmpty()
        if (title.contains("just a moment") || title.contains("attention required")) {
            if (lower.contains("/cdn-cgi/") || lower.contains("cloudflare")) {
                return logRule(body, "title_cloudflare_hint")
            }
        }

        // TODO(Phase 3): 换成 Ksoup 的 document.getElementById / select，与原实现逐字对齐
        if (lower.contains("id=\"challenge-form\"") || lower.contains("id='challenge-form'")) {
            return logRule(body, "element_challenge_form")
        }
        if (lower.contains("id=\"cf-please-wait\"") || lower.contains("id='cf-please-wait'")) {
            return logRule(body, "element_cf_please_wait")
        }
        if (lower.contains("id=\"challenge-running\"") || lower.contains("id='challenge-running'")) {
            return logRule(body, "element_challenge_running")
        }
        if (lower.contains("g-recaptcha") && lower.contains("/cdn-cgi/")) {
            return logRule(body, "element_g_recaptcha_cdn_cgi")
        }
        if (lower.contains("cf-browser-verification")) {
            return logRule(body, "class_cf_browser_verification")
        }
        if (lower.contains("cf-error-overview")) {
            return logRule(body, "class_cf_error_overview")
        }

        // script 级判断（原实现遍历 document.select("script")，读 src 与 innerHtml）
        Regex("(?is)<script[^>]*src=\"[^\"]*\"")
            .findAll(body)
            .forEach { m ->
                val src = m.value.lowercase()
                if (src.contains("/cdn-cgi/l/chk_")) return logRule(body, "script_src_cdn_cgi_chk")
                if (src.contains("/cdn-cgi/challenge-platform/") && hasChallengeText(lower)) {
                    return logRule(body, "script_src_challenge_platform_with_text")
                }
            }
        Regex("""(?is)<script[^>]*>(.*?)</script>""")
            .findAll(body)
            .forEach { m ->
                val html = m.groupValues.getOrNull(1)?.lowercase().orEmpty()
                if (html.contains("/cdn-cgi/challenge-platform/") && hasChallengeText(lower)) {
                    return logRule(body, "script_content_challenge_platform_with_text")
                }
            }

        // meta refresh：CF 常用“刷新跳转”做挑战
        Regex("""(?is)<meta[^>]*http-equiv=["']?refresh["']?[^>]*>""").find(body)?.let {
            if (lower.contains("cloudflare")) return logRule(body, "meta_refresh_cloudflare")
        }

        return null
    }

    private fun hasChallengeText(lower: String): Boolean =
        lower.contains("one more step") ||
            lower.contains("security check") ||
            lower.contains("checking your browser") ||
            lower.contains("verify you are human") ||
            lower.contains("just a moment") ||
            lower.contains("ray id:")

    private fun logRule(body: String, reason: String): String {
        platformLog("CF", "challenge detected reason=$reason ${htmlSummary(body)}")
        return reason
    }

    private fun htmlSummary(body: String): String {
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)
            ?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(60)
            .orEmpty()
        return "htmlLength=${body.length} title=$title"
    }
}
