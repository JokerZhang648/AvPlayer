package com.javrocket.avplay.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放状态 —— 两端统一语义。
 *
 * 原实现里播放状态散在 PlayerListener 回调里（每个回调各改一处 UI）；
 * 这里收敛成一个不可变快照，UI 只订阅一个 StateFlow，不会出现“进度条和按钮状态打架”。
 */
enum class PlayerStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

data class PlayerSnapshot(
    val status: PlayerStatus = PlayerStatus.IDLE,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 视频帧宽高（解码后），用于按分辨率适配画面区域。 */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    /** HTTP 层错误码（401/403/404…），非 HTTP 错误为 null。 */
    val httpCode: Int? = null,
    val errorMessage: String? = null,
)

fun PlayerSnapshot.videoAspectRatio(): Float {
    if (videoWidth > 0 && videoHeight > 0) return videoWidth / videoHeight.toFloat()
    return 16f / 9f
}

/**
 * 平台播放器契约 —— 只有 5 个动作 + 1 个状态流。
 *
 * 为什么把接口压这么窄：两端播放器差异巨大（ExoPlayer vs AVPlayer），
 * 但业务只需要"给地址能播、能给头、能报错码"。把差异关在 actual 实现里，
 * 共享层才能写出唯一一份播放逻辑（401 重试、换集守卫、续播）。
 */
expect class PlatformPlayer() {
    val snapshot: StateFlow<PlayerSnapshot>

    /** HTTP 失败回调（供上层做凭据刷新重试）。回调在播放器线程，切换状态请回主线程。 */
    var onHttpError: ((Int) -> Unit)?

    fun prepare(url: String, mimeType: String, headers: Map<String, String>)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setPlaybackSpeed(speed: Float)
    /** true：完整显示不裁切（横屏全屏）；false：铺满区域可裁切。 */
    fun setFitVideoInView(fit: Boolean)
    fun release()
}

/** 视频画面。Android 是 PlayerView，iOS 是 AVPlayerLayer 包出来的 UIView。 */
@Composable
expect fun PlayerSurface(
    player: PlatformPlayer,
    modifier: Modifier,
    /** 竖屏全屏：视频铺满屏幕，黑边由播放器缩放处理。 */
    fillPortraitScreen: Boolean = false,
    /** 与 [PlatformPlayer.setFitVideoInView] 联动，按视频比例留黑边。 */
    fitWithinBounds: Boolean = false,
)
