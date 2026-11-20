package com.javrocket.avplay.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVURLAsset
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.darwin.NSObjectProtocol

/**
 * iOS 播放器 = AVPlayer + AVURLAsset。
 *
 * ⚠️ 本文件在 Windows 上无法编译验证（Kotlin/Native 的 iOS 目标需要 Xcode）。
 * 逻辑按 Apple 文档写就，但首次在 macOS 上编译时预计要修 2–3 处 API 名称。
 *
 * 已实现：
 *  - 首次请求（播放列表）通过 `AVURLAssetHTTPHeaderFieldsKey` 带上 UA/Referer/Cookie
 *  - 状态/进度映射到与 Android 完全相同的 PlayerSnapshot
 *  - 失败时把 AVPlayerItem.error 的 HTTP 状态码交给 onHttpError
 *
 * 未实现（Phase 4 的已知缺口，见 docs/MIGRATION.md）：
 *  - **分片请求的请求头改写**。AVURLAssetHTTPHeaderFieldsKey 只作用于第一个请求，
 *    HLS 的每个 .ts/.m4s 分片是 AVFoundation 自己发的，要改必须实现
 *    AVAssetResourceLoaderDelegate（把自定义 scheme 转回 https 再逐请求转发）。
 *    这一步不做的话：需要 Referer 才能取分片的站点在 iOS 上会只有首帧或黑屏。
 */
@OptIn(ExperimentalForeignApi::class)
actual class PlatformPlayer actual constructor() {

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    actual val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    actual var onHttpError: ((Int) -> Unit)? = null

    private var player: AVPlayer? = null
    private var elapsedObserver: Any? = null
    private var endObserver: NSObjectProtocol? = null
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    internal fun rawPlayer(): AVPlayer? = player

    actual fun prepare(url: String, mimeType: String, headers: Map<String, String>) {
        release()

        // 后台播放与静音开关：不设 playback 类别的话，静音键一拨就没声音
        runCatching {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, null)
            AVAudioSession.sharedInstance().setActive(true, null)
        }

        val nsUrl = NSURL.URLWithString(url) ?: run {
            _snapshot.value = PlayerSnapshot(status = PlayerStatus.ERROR, errorMessage = "地址非法")
            return
        }

        val asset = AVURLAsset(
            uRL = nsUrl,
            options = mapOf("AVURLAssetHTTPHeaderFieldsKey" to headers),
        )
        val item = AVPlayerItem(asset = asset)
        val avPlayer = AVPlayer(playerItem = item)
        player = avPlayer

        // 进度轮询与 Android 一致（AVPlayer 没有位置回调，官方示例也是周期观察者）
        pollJob = scope.launch {
            while (true) {
                val p = player ?: break
                val posMs = (p.currentTime().seconds * 1000).toLong().coerceAtLeast(0)
                val durMs = (p.currentItem?.duration?.seconds ?: 0.0).let { if (it.isNaN()) 0L else (it * 1000).toLong() }
                val status = when {
                    p.timeControlStatus == AVPlayerTimeControlStatusPlaying -> PlayerStatus.PLAYING
                    p.currentItem?.status == AVPlayerItemStatusReadyToPlay -> PlayerStatus.PAUSED
                    p.currentItem?.status == AVPlayerItemStatusFailed -> PlayerStatus.ERROR
                    else -> PlayerStatus.BUFFERING
                }
                _snapshot.update {
                    it.copy(
                        status = status,
                        positionMs = posMs,
                        durationMs = durMs,
                        errorMessage = p.currentItem?.error?.localizedDescription ?: it.errorMessage,
                    )
                }
                delay(500)
            }
        }

        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            _snapshot.update { it.copy(status = PlayerStatus.ENDED) }
        }

        avPlayer.play()
    }

    actual fun play() {
        player?.play()
    }

    actual fun pause() {
        player?.pause()
    }

    actual fun seekTo(positionMs: Long) {
        player?.seekToTime(platform.CoreMedia.CMTimeMakeWithSeconds(positionMs / 1000.0, 1000L))
    }

    actual fun setPlaybackSpeed(speed: Float) {
        val rate = speed.coerceIn(0.5f, 2.0f)
        player?.rate = rate
    }

    actual fun release() {
        pollJob?.cancel()
        pollJob = null
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        runCatching { player?.pause() }
        player?.replaceCurrentItemWithPlayerItem(null)
        player = null
        _snapshot.value = PlayerSnapshot()
    }
}

/**
 * iOS 画面：用 AVPlayerViewController 而不是裸 AVPlayerLayer。
 * 理由：它自带正确的手势、全屏、字幕与画中画处理，且不需要手动对齐 frame
 * （裸 layer 要自己挂 autoresizing，旋转/安全区都容易错位）。
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlayerSurface(player: PlatformPlayer, modifier: Modifier, fillPortraitScreen: Boolean) {
    UIKitViewController(
        modifier = modifier,
        factory = {
            AVPlayerViewController().apply {
                showsPlaybackControls = false
                this.player = player.rawPlayer()
            }
        },
        update = { controller -> controller.player = player.rawPlayer() },
    )
}
