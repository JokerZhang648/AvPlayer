package com.javrocket.avplay.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.javrocket.avplay.core.AndroidAppContext
import com.javrocket.avplay.net.platformLog
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
import okhttp3.OkHttpClient

/**
 * Android 播放器 = ExoPlayer + OkHttp 数据源。
 *
 * 三个关键决定：
 * 1) **用 OkHttpDataSource 而不是 DefaultHttpDataSource** —— 我们要给每个分片请求追加
 *    Referer/UA/Cookie（站点防盗链），DefaultHttpDataSource 只能设静态头，做不到按请求定制。
 * 2) **每次 prepare 重建 ExoPlayer** —— 换集时旧 player 的 buffer、错误状态、音轨选择
 *    全都要清掉；复用是"换集串台"这类 bug 的根源。重建成本约几十毫秒，换来的是干净状态。
 * 3) **进度用轮询而不是回调** —— ExoPlayer 没有"位置变化"监听，官方也是建议轮询。
 */
actual class PlatformPlayer actual constructor() {

    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    actual val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    actual var onHttpError: ((Int) -> Unit)? = null

    private var exo: ExoPlayer? = null
    private var videoTextureView: TextureView? = null
    private var fitVideoInView = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 供 PlayerSurface 使用（同一 sourceSet 内可见）。 */
    internal fun rawPlayer(): ExoPlayer? = exo

    internal fun attachVideoTextureView(textureView: TextureView) {
        videoTextureView = textureView
        exo?.setVideoTextureView(textureView)
        applyVideoScalingMode()
    }

    private fun applyVideoScalingMode() {
        val mode = if (fitVideoInView) {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        } else {
            C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }
        exo?.videoScalingMode = mode
    }

    actual fun setFitVideoInView(fit: Boolean) {
        fitVideoInView = fit
        applyVideoScalingMode()
    }

    actual fun prepare(url: String, mimeType: String, headers: Map<String, String>) {
        val ctx = AndroidAppContext.get()
        if (ctx == null) {
            _snapshot.value = PlayerSnapshot(status = PlayerStatus.ERROR, errorMessage = "Context 未初始化")
            return
        }

        releasePlayerOnly()
        _snapshot.update { it.copy(videoWidth = 0, videoHeight = 0) }

        platformLog(
            "Playback",
            "prepare url=${url.take(110)} mime=$mimeType headers=${headers.keys.joinToString(",")}",
        )

        // 注意：User-Agent 必须只出现一次。
        // media3 的 OkHttpDataSource 里 setDefaultRequestProperties + setUserAgent 都会写 UA，
        // 若 headers 里也带 User-Agent 就会变成**两个 UA 头**，部分 CDN（实测 AV01 的
        // customers.iw01.xyz）直接回 403 —— 报错只会显示 "Source error / Response code: 403"，
        // 极难反推到"头重复"这个原因。
        val props = headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val resp = chain.proceed(chain.request())
                platformLog(
                    "Playback",
                    "http ${resp.code} ${chain.request().url}",
                )
                resp
            }
            .build()

        val httpFactory = OkHttpDataSource.Factory(client)
            // 每个分片请求都会带上这些头：Referer / Cookie 缺一个，CDN 就回 403
            .setDefaultRequestProperties(props)
            .setUserAgent(headers["User-Agent"])

        // DefaultDataSource 会把 file:// / content:// 交给对应 DataSource，其余走 httpFactory。
        // 必须有它：AV01 的播放列表是「已带 token 的本地 m3u8」，直接给 httpFactory 读不了 file://
        val dataSourceFactory = DefaultDataSource.Factory(ctx, httpFactory)

        val trackSelector = DefaultTrackSelector(ctx).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }

        val player = ExoPlayer.Builder(ctx)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .build()
        player.volume = 1f

        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val summary = tracks.groups.joinToString { g ->
                    "type=${g.type} selected=${g.isSelected} n=${g.length}"
                }
                platformLog("Playback", "tracks $summary")
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val w = videoSize.width
                val h = videoSize.height
                if (w > 0 && h > 0) {
                    val rot = videoSize.unappliedRotationDegrees % 360
                    val (vw, vh) = if (rot == 90 || rot == 270) h to w else w to h
                    _snapshot.update { it.copy(videoWidth = vw, videoHeight = vh) }
                    platformLog("Playback", "videoSize ${vw}x$vh rot=$rot")
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                platformLog("Playback", "state=$playbackState")
                if (playbackState == Player.STATE_READY) {
                    boostPlaybackAudio(ctx, player)
                }
                val status = when (playbackState) {
                    Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
                    Player.STATE_READY -> if (player.playWhenReady) PlayerStatus.PLAYING else PlayerStatus.PAUSED
                    Player.STATE_ENDED -> PlayerStatus.ENDED
                    else -> PlayerStatus.IDLE
                }
                _snapshot.update { it.copy(status = status, httpCode = null, errorMessage = null) }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _snapshot.update {
                    it.copy(status = if (isPlaying) PlayerStatus.PLAYING else PlayerStatus.PAUSED)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // ExoPlayer 把 HTTP 错误包在 cause 链里，必须剥到 HttpDataSource.InvalidResponseCodeException 才拿到真实状态码
                var httpCode: Int? = null
                var cursor: Throwable? = error.cause
                while (cursor != null) {
                    if (cursor is HttpDataSource.InvalidResponseCodeException) {
                        httpCode = cursor.responseCode
                        break
                    }
                    cursor = cursor.cause
                }
                platformLog("Playback", "player error code=$httpCode msg=${error.message}")
                _snapshot.update {
                    it.copy(
                        status = PlayerStatus.ERROR,
                        httpCode = httpCode,
                        errorMessage = error.message ?: "播放失败",
                    )
                }
                httpCode?.let { code -> onHttpError?.invoke(code) }
            }
        })

        val item = MediaItem.Builder().setUri(url).build()

        // 必须显式选 HlsMediaSource。
        // 坑：给 MediaItem 设 mimeType="application/vnd.apple.mpegurl" 会让 DefaultMediaSourceFactory
        // 走 Util.inferContentType(mimeType) —— 它只认 MimeTypes.APPLICATION_M3U8（"application/x-mpegURL"），
        // 认不出就返回 CONTENT_TYPE_OTHER → 用 ProgressiveMediaPeriod 当 mp4 解，报
        // "None of the available extractors ... could read the stream"。
        val isHls = mimeType.contains("mpegurl", ignoreCase = true) ||
            url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
        val mediaSource = if (isHls) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }

        exo = player
        player.setMediaSource(mediaSource)
        player.prepare()
        videoTextureView?.let { player.setVideoTextureView(it) }
        applyVideoScalingMode()

        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val p = exo ?: break
                val duration = p.duration.takeIf { it > 0 } ?: 0L
                val position = p.currentPosition
                val atEnd = duration > 3000 && position >= duration - 300
                val status = when {
                    p.playbackState == Player.STATE_ENDED -> PlayerStatus.ENDED
                    atEnd && (p.playbackState == Player.STATE_BUFFERING || !p.isPlaying) -> PlayerStatus.ENDED
                    p.isPlaying -> PlayerStatus.PLAYING
                    p.playbackState == Player.STATE_BUFFERING -> PlayerStatus.BUFFERING
                    else -> _snapshot.value.status
                }
                _snapshot.update {
                    it.copy(positionMs = position, durationMs = duration, status = status)
                }
                delay(500)
            }
        }
    }

    actual fun play() {
        exo?.apply {
            volume = 1f
            playWhenReady = true
        }
    }

    actual fun pause() {
        exo?.playWhenReady = false
    }

    actual fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
    }

    actual fun setPlaybackSpeed(speed: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        exo?.apply {
            setPlaybackSpeed(s)
            // 倍速也应保持正常音量；部分机型仅设 speed 会误判为「无声」
            volume = 1f
        }
    }

    actual fun release() {
        pollJob?.cancel()
        pollJob = null
        releasePlayerOnly()
        _snapshot.value = PlayerSnapshot()
    }

    private fun releasePlayerOnly() {
        releaseAudioFx()
        exo?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        exo = null
    }

    private fun releaseAudioFx() {
        runCatching { loudnessEnhancer?.release() }
        loudnessEnhancer = null
    }

    private fun isEmulator(): Boolean {
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val fingerprint = Build.FINGERPRINT.orEmpty()
        return model.contains("sdk_gphone", ignoreCase = true) ||
            model.contains("Emulator", ignoreCase = true) ||
            product.contains("sdk", ignoreCase = true) ||
            fingerprint.contains("generic", ignoreCase = true)
    }

  /** 模拟器经 QEMU 到电脑音箱的链路增益偏低，必要时用 LoudnessEnhancer 补一截。 */
    private fun boostPlaybackAudio(ctx: Context, player: ExoPlayer) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
        player.volume = 1f

        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET) return

        releaseAudioFx()
        val emulator = isEmulator()
        val gainMb = if (emulator) 1500 else 400
        runCatching {
            loudnessEnhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(gainMb)
                enabled = true
            }
            platformLog("Playback", "audio boost session=$sessionId gain=${gainMb}mB emulator=$emulator")
        }.onFailure {
            platformLog("Playback", "audio boost failed: ${it.message}")
        }
    }
}

@Composable
actual fun PlayerSurface(
    player: PlatformPlayer,
    modifier: Modifier,
    fillPortraitScreen: Boolean,
    fitWithinBounds: Boolean,
) {
    // 统一 TextureView：PlayerView 在 prepare 早于 Surface 附着时易「有声无画」（黄果竖屏已验证 TextureView）。
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextureView(ctx) },
        update = { textureView ->
            player.setFitVideoInView(fitWithinBounds)
            player.attachVideoTextureView(textureView)
        },
        onRelease = { textureView -> player.rawPlayer()?.clearVideoTextureView(textureView) },
    )
}
