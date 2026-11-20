package com.javrocket.avplay.player

import com.javrocket.avplay.net.platformLog
import com.javrocket.avplay.platform.epochMillis
import io.ktor.http.URLBuilder
import io.ktor.http.Url

/**
 * 播放请求规则 —— 还原反编译里的 player/PlaybackRequestRule 系列。
 *
 * 核心设计：“规则只在匹配的 host+path 上生效”。
 * 不这么做的后果是：把播放凭据（token/cookie）泄漏给广告域或第三方 CDN，既失效又不安全。
 */
data class PlaybackRequestMatcher(
    val host: String? = null,
    val pathPrefix: String? = null,
) {
    fun matches(url: Url): Boolean {
        host?.let { if (!url.host.equals(it, ignoreCase = true)) return false }
        pathPrefix?.let { if (!url.encodedPath.startsWith(it)) return false }
        return true
    }
}

sealed class PlaybackRequestRule {
    abstract val credentialKey: String
    abstract val matcher: PlaybackRequestMatcher

    /** 把凭据值塞进某个请求头（典型：Referer、Authorization、自定义 X-Token）。 */
    data class SetHeader(
        override val credentialKey: String,
        val headerName: String,
        override val matcher: PlaybackRequestMatcher = PlaybackRequestMatcher(),
    ) : PlaybackRequestRule()

    /** 把凭据值追加成查询参数（典型：清单的 ?hb= 心跳、分片的 ?token=）。 */
    data class AddQueryParameter(
        override val credentialKey: String,
        val parameterName: String,
        override val matcher: PlaybackRequestMatcher = PlaybackRequestMatcher(),
    ) : PlaybackRequestRule()
}

data class PlaybackCredentialSnapshot(
    val values: Map<String, String> = emptyMap(),
    val generation: Long = 0,
    val expiresAtMillis: Long? = null,
) {
    fun expiresSoon(nowMillis: Long = epochMillis(), windowMillis: Long = 60_000): Boolean =
        expiresAtMillis?.let { it - nowMillis <= windowMillis } ?: false
}

enum class PlaybackCredentialRefreshReason { EXPIRING, UNAUTHORIZED }

sealed class PlaybackCredentialRefreshResult {
    data class Updated(val snapshot: PlaybackCredentialSnapshot) : PlaybackCredentialRefreshResult()
    data object Failed : PlaybackCredentialRefreshResult()
    data object Unsupported : PlaybackCredentialRefreshResult()
}

/**
 * 凭据提供者 —— 对应反编译里的 player/PlaybackCredentialProvider。
 * 三元刷新结果（Updated / Failed / Unsupported）让“静态 mp4 这种不支持刷新的线路”也能表达。
 */
interface PlaybackCredentialProvider {
    fun snapshot(): PlaybackCredentialSnapshot
    suspend fun refresh(
        reason: PlaybackCredentialRefreshReason,
        currentGeneration: Long,
    ): PlaybackCredentialRefreshResult
}

data class PlaybackTransport(
    val defaultHeaders: Map<String, String> = emptyMap(),
    val requestRules: List<PlaybackRequestRule> = emptyList(),
    val credentialProvider: PlaybackCredentialProvider? = null,
)

data class PlaybackGatewayRequest(
    val url: String,
    val headers: Map<String, String>,
    val generation: Long,
)

/**
 * 组装“真正要发出去的播放请求” —— 还原反编译里的
 * player/PlaybackGatewayKt#gatewayRequest。
 *
 * 这个函数留在 commonMain：Android 用 OkHttp 拦截器调它，iOS 用
 * AVAssetResourceLoaderDelegate 调它，两端行为完全一致。
 */
suspend fun gatewayRequest(
    transport: PlaybackTransport,
    url: String,
    headers: Map<String, String> = emptyMap(),
): PlaybackGatewayRequest {
    val provider = transport.credentialProvider

    // 规则与凭据都没有 → 原样放行，零开销
    if (transport.requestRules.isEmpty() && provider == null) {
        return PlaybackGatewayRequest(url, headers, 0)
    }

    var snapshot = provider?.snapshot()
    val generation = snapshot?.generation ?: 0L

    // 即将过期 → 主动刷新（失败就继续用旧值，不阻断播放）
    if (provider != null && snapshot != null && snapshot.expiresSoon()) {
        when (val result = provider.refresh(PlaybackCredentialRefreshReason.EXPIRING, generation)) {
            is PlaybackCredentialRefreshResult.Updated -> snapshot = result.snapshot
            PlaybackCredentialRefreshResult.Failed -> platformLog("Playback", "credential refresh failed (expiring)")
            PlaybackCredentialRefreshResult.Unsupported -> platformLog("Playback", "credential refresh unsupported")
        }
    }

    val builder = URLBuilder(url)

    val mergedHeaders = LinkedHashMap<String, String>()
    mergedHeaders.putAll(transport.defaultHeaders)
    mergedHeaders.putAll(headers)

    if (provider != null && snapshot != null) {
        for (rule in transport.requestRules) {
            if (!rule.matcher.matches(builder.build())) continue

            val value = snapshot.values[rule.credentialKey]?.takeIf { it.isNotBlank() } ?: continue

            when (rule) {
                is PlaybackRequestRule.SetHeader -> mergedHeaders[rule.headerName] = value
                is PlaybackRequestRule.AddQueryParameter -> {
                    if (!builder.parameters.contains(rule.parameterName)) {
                        builder.parameters.append(rule.parameterName, value)
                    }
                }
            }
        }
    }

    return PlaybackGatewayRequest(
        url = builder.buildString(),
        headers = mergedHeaders,
        generation = snapshot?.generation ?: 0L,
    )
}
