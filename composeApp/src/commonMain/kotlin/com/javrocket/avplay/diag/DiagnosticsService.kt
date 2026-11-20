package com.javrocket.avplay.diag

import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.dumpDirPath
import com.javrocket.avplay.core.dumpTextFile
import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.net.platformUserAgent
import com.javrocket.avplay.platform.epochMillis
import com.javrocket.avplay.platform.platformName
import com.javrocket.avplay.service.ContentSource
import kotlinx.coroutines.delay

/**
 * 诊断的"层" —— 这是诊断页最有价值的部分。
 *
 * 用户/AI 看到"首页失败"时最常犯的错是把所有失败当成同一类问题：
 * 网络不通、被 CF 拦、接口 500、页面改版解析不到，四种原因的修法完全不同。
 * 所以这里把异常归到层，并在页面上直接显示"该修哪一层"。
 */
enum class DiagLayer(val label: String, val advice: String) {
    OK("正常", "可用"),
    NETWORK("网络层", "检查网络/代理；换出口 IP 或等超时恢复"),
    ANTI_BOT("反爬层", "在 App 内点「打开验证页」过一次人机验证"),
    PROTOCOL("协议层", "站点接口变了或被限流；看 HTTP 状态码与日志"),
    PARSE("解析层", "站点改版；对照 dump 目录里的 HTML 原文改选择器"),
    UNKNOWN("未知", "看导出的诊断日志"),
}

/** 单站结果。 */
data class SiteDiagnostic(
    val siteId: String,
    val name: String,
    val baseUrl: String,
    val layer: DiagLayer,
    val ok: Boolean,
    val elapsedMs: Long,
    val itemCount: Int = 0,
    val detail: String = "",
) {
    fun line(): String =
        "[$layer] $name  ${elapsedMs}ms  条目=$itemCount  ${baseUrl}\n    ${detail.ifBlank { layer.advice }}"
}

data class DiagnosticsReport(
    val startedAtMs: Long,
    val totalMs: Long,
    val userAgent: String = "",
    val platform: String = "",
    val items: List<SiteDiagnostic> = emptyList(),
) {
    val okCount: Int get() = items.count { it.ok }
    val challengeCount: Int get() = items.count { it.layer == DiagLayer.ANTI_BOT }

    /** 给"一句话结论"：现在到底能不能用、卡在哪。 */
    fun summary(): String = when {
        items.isEmpty() -> "没有可诊断的站点"
        okCount == items.size -> "全部 ${items.size} 个站点连通"
        okCount == 0 && challengeCount == items.size -> "全部站点都被人机验证拦住"
        okCount == 0 -> "全部站点不可用：${items.first().layer.label}问题最突出"
        else -> "$okCount/${items.size} 个站点连通" +
            if (challengeCount > 0) "，$challengeCount 个需要过人机验证" else ""
    }
}

/**
 * 诊断服务：对每个站点跑一次真实 home()，按异常类型分层。
 *
 * 为什么不"ping 一下就完事"：站点能不能用取决于"整条链路"（网络 → 反爬 → 接口 → 解析），
 * 只 ping 域名会得到"能连上但完全用不了"的假阳性。所以这里跑的是**真实业务调用**。
 *
 * 代价是每站一次完整首页请求（约 1~8 秒），6 个站点顺序跑最多 ~40 秒 —— 可以接受，
 * 而且站点之间顺序执行，避免同时打满带宽让结果互相干扰。
 */
class DiagnosticsService(
    private val sourcesProvider: () -> List<ContentSource>,
) {

    suspend fun run(): DiagnosticsReport {
        val started = epochMillis()
        val ua = runCatching { platformUserAgent() }.getOrDefault("")
        val items = ArrayList<SiteDiagnostic>()

        for (source in sourcesProvider()) {
            val t0 = epochMillis()
            var layer = DiagLayer.OK
            var detail = ""
            var count = 0
            var ok = false

            source.home().fold(
                onSuccess = { feed ->
                    count = feed.sections.sumOf { it.items.size }
                    ok = count > 0
                    if (!ok) {
                        layer = DiagLayer.PARSE
                        detail = "接口正常但没有条目（站点改版或地区限制）"
                    }
                },
                onFailure = { e ->
                    layer = classify(e)
                    detail = e.message ?: e::class.simpleName.orEmpty()
                    // 站点间留一点间隔：连续猛打容易被风控，也会把"限流"误判成"站点坏了"
                    if (layer == DiagLayer.ANTI_BOT) delay(SITE_GAP_MS)
                },
            )

            val elapsed = epochMillis() - t0
            items += SiteDiagnostic(
                siteId = source.id,
                name = source.displayName,
                baseUrl = source.baseUrl,
                layer = layer,
                ok = ok,
                elapsedMs = elapsed,
                itemCount = count,
                detail = detail,
            )
            platformLog("Diag", "site=${source.id} layer=$layer ok=$ok ${elapsed}ms items=$count")
        }

        val report = DiagnosticsReport(
            startedAtMs = started,
            totalMs = epochMillis() - started,
            userAgent = ua,
            platform = platformName(),
            items = items,
        )
        platformLog("Diag", "report ${report.summary()}")
        return report
    }

    private fun classify(e: Throwable): DiagLayer = when (e) {
        is AppError.CloudflareChallenge, is AppError.SiteBlocked -> DiagLayer.ANTI_BOT
        is AppError.Http -> if (e.status == 403) DiagLayer.ANTI_BOT else DiagLayer.PROTOCOL
        is AppError.IpForbidden -> DiagLayer.ANTI_BOT
        is AppError.Network, is AppError.Timeout -> DiagLayer.NETWORK
        is AppError.Parse -> DiagLayer.PARSE
        is AppError.AuthenticationRequired -> DiagLayer.ANTI_BOT
        else -> {
            // 引擎抛的原始异常（DNS/SSL/连接被拒）都归网络层
            val name = e::class.simpleName.orEmpty()
            if (name.contains("Timeout", true) || name.contains("Connect", true) ||
                name.contains("UnknownHost", true) || name.contains("SSL", true)
            ) {
                DiagLayer.NETWORK
            } else {
                DiagLayer.UNKNOWN
            }
        }
    }

    /**
     * 导出诊断报告到 dump 目录，返回文件路径（UI 上要让用户看得到这个路径）。
     *
     * 报告里带上 UA / 平台 / 每站耗时与分层结论 —— 这三样是复现问题时最先要问的东西，
     * 自动带上就省掉一轮来回。
     */
    fun export(report: DiagnosticsReport): String? {
        val text = buildString {
            appendLine("AvPlay 诊断报告")
            appendLine("时间: ${formatTime(report.startedAtMs)}")
            appendLine("平台: ${report.platform}")
            appendLine("UA: ${report.userAgent}")
            appendLine("耗时: ${report.totalMs}ms")
            appendLine("结论: ${report.summary()}")
            appendLine()
            appendLine("==== 各站点 ====")
            report.items.forEach { item ->
                appendLine(item.line())
                appendLine("    建议: ${item.layer.advice}")
                appendLine()
            }
            appendLine("==== dump 目录 ====")
            appendLine(dumpDirPath())
        }
        return dumpTextFile("diag_${report.startedAtMs}.txt", text)
    }

    companion object {
        private const val SITE_GAP_MS = 2_000L

        private fun formatTime(ms: Long): String {
            // 只做诊断展示，不做时区换算精度要求；用简单可读格式
            val sec = ms / 1000
            val h = (sec / 3600) % 24
            val m = (sec / 60) % 60
            val s = sec % 60
            return "%02d:%02d:%02d (epoch ${ms})".format(h, m, s)
        }
    }
}
