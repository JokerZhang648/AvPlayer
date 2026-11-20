package com.javrocket.avplay.core

/**
 * 全局错误类型学。
 *
 * 对应反编译里的 6 个具名异常 + ErrorKt.toException 的映射逻辑
 * （AuthenticationRequiredException / PremiumRequiredException / IPForbiddenException /
 *   CopyrightForbiddenException / CloudflareChallengeException / SiteAccessBlockedException）。
 *
 * 设计原则：不用 -1/false 表达失败，失败一律是具名类型，UI 按类型决定展示哪个页面。
 */
sealed class AppError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 需要登录。带“失败时的登录版本号”，用于登录成功后自动重放（见 LoginRetrySession）。 */
    class AuthenticationRequired(val loginVersion: Long = 0) : AppError("需要登录")

    /** 需要订阅（原实现对应业务码 CONTENT_NEED_VIP = 10001）。 */
    data object PremiumRequired : AppError("该内容需要订阅")

    /** 当前出口 IP 被站点拒绝。 */
    class IpForbidden(val ip: String? = null) : AppError("当前网络被站点拒绝访问")

    /** 内容因版权要求下架（原实现对应业务码 CONTENT_COPYRIGHT_FORBIDDEN = 10002）。 */
    data object CopyrightForbidden : AppError("内容因版权要求已下架")

    /** 撞上 Cloudflare 人机校验。带上触发它的 URL，交给挑战页处理。 */
    class CloudflareChallenge(val url: String) : AppError("需要人机验证：$url")

    /** 站点整体拒绝访问。 */
    data object SiteBlocked : AppError("站点拒绝访问")

    /** 操作超时。operation 是操作名（如 "homePage"），直接进日志。 */
    class Timeout(val operation: String, val timeoutMs: Long) : AppError("操作超时：$operation (${timeoutMs}ms)")

    /** HTTP 层错误，文案与原实现保持一致。 */
    class Http(val status: Int, message: String) : AppError(message)

    /** 网络/传输层异常。 */
    class Network(cause: Throwable) : AppError(cause.message ?: "网络错误", cause)

    /** 解析失败（站点改版最常见）。 */
    class Parse(message: String) : AppError(message)

    companion object {
        /** 状态码 → 中文文案，直接沿用原实现 ErrorKt 里的硬编码映射。 */
        fun ofHttpStatus(status: Int): AppError = when (status) {
            403 -> Http(status, "服务器拒绝访问，请稍后重试")
            408 -> Http(status, "网络连接超时，请检查后重试")
            500 -> Http(status, "服务器发生错误，请稍后重试")
            502 -> Http(status, "服务器连接失败，请稍后再试")
            503 -> Http(status, "服务器正忙，请稍后再访问")
            504 -> Http(status, "连接服务器超时，请稍后重试")
            else -> Http(status, "请求失败（$status）")
        }
    }
}

/**
 * 登录成功版本号 —— 对应反编译里的 service/AuthenticationRetrySession。
 *
 * 机制：请求失败时记下“当时的版本号”；用户登录成功后版本号自增，
 * 于是同一个失败请求可以安全地自动重放一次（既不会无限重试，也不用用户手动刷新）。
 */
class LoginRetrySession {
    private var version: Long = 0

    val currentVersion: Long get() = version

    fun recordLoginSuccess(): Long {
        version += 1
        return version
    }

    fun canRetryAfterLogin(failure: Throwable): Boolean =
        failure is AppError.AuthenticationRequired && version > failure.loginVersion
}
