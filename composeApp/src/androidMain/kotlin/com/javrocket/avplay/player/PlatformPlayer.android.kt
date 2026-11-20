package com.javrocket.avplay.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
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
    private var pollJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 供 PlayerSurface 使用（同一 sourceSet 内可见）。 */
    internal fun rawPlayer(): ExoPlayer? = exo

    actual fun prepare(url: String, mimeType: String, headers: Map<String, String>) {
        val ctx = AndroidAppContext.get()
        if (ctx == null) {
            _snapshot.value = PlayerSnapshot(status = PlayerStatus.ERROR, errorMessage = "Context 未初始化")
            return
        }

        releasePlayerOnly()

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

        val player = ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                platformLog("Playback", "state=$playbackState")
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

        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                val p = exo ?: break
                val duration = p.duration.takeIf { it > 0 } ?: 0L
                _snapshot.update { it.copy(positionMs = p.currentPosition, durationMs = duration) }
                delay(500)
            }
        }
    }

    actual fun play() {
        exo?.playWhenReady = true
    }

    actual fun pause() {
        exo?.playWhenReady = false
    }

    actual fun seekTo(positionMs: Long) {
        exo?.seekTo(positionMs)
    }

    actual fun release() {
        pollJob?.cancel()
        pollJob = null
        releasePlayerOnly()
        _snapshot.value = PlayerSnapshot()
    }

    private fun releasePlayerOnly() {
        exo?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        exo = null
    }
}

@Composable
actual fun PlayerSurface(player: PlatformPlayer, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                // 用自己的控制条（共享 UI），关掉 ExoPlayer 自带的，避免两套控件打架
                useController = false
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            }
        },
        update = { view -> view.player = player.rawPlayer() },
    )
}
