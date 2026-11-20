package com.javrocket.avplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.player.PlatformPlayer
import com.javrocket.avplay.player.PlayerStatus
import com.javrocket.avplay.player.PlayerSurface
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.store.HistoryEntry
import com.javrocket.avplay.store.LibraryStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放页 —— 覆盖 Phase 4 的三条验收：
 *
 * 1. **能播 m3u8**：ExoPlayer 的 HLS 支持 + 自定义请求头（Android 已实测）；
 * 2. **401 后自动刷新凭据重试一次**：播放器报 HTTP 401/403 时，重新调 `source.playback()`
 *    拿新地址（很多站点的播放票据是短期签名，过期就是 403），只重试一次，避免死循环；
 * 3. **续播**：打开时从历史位置接上（看过 95% 以上则从头放，见 HistoryEntry.resumePositionMs）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    source: ContentSource,
    brief: VideoBrief,
    initial: PlaybackInfo,
    library: LibraryStore,
    onBack: () -> Unit,
    onNeedChallenge: (String) -> Unit,
) {
    val player = remember { PlatformPlayer() }
    val snapshot by player.snapshot.collectAsState()
    val scope = rememberCoroutineScope()

    var current by remember { mutableStateOf(initial) }
    var retriedAuth by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    // 拖动进度条时用本地值，松手才真正 seek —— 否则拖动过程会被回写的位置打断
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }

    // 换地址就重建播放器（换集不串台的保证：prepare 内部会销毁旧 ExoPlayer）
    LaunchedEffect(current.url) {
        player.prepare(current.url, current.mimeType, current.headers)
        val resume = library.resumeOf(source.id, brief.id)
        val startAt = resume?.resumePositionMs() ?: 0L
        if (startAt > 3000) player.seekTo(startAt)
        player.play()
    }

    // 401/403 自动重试：重新解析一次播放地址
    player.onHttpError = { code ->
        if (!retriedAuth && (code == 401 || code == 403)) {
            retriedAuth = true
            authError = "播放凭据被拒绝（HTTP $code），正在重新获取地址…"
            scope.launch {
                source.playback(brief.id).fold(
                    onSuccess = { info ->
                        authError = null
                        current = info
                    },
                    onFailure = { e ->
                        val cf = e as? AppError.CloudflareChallenge
                        authError = if (cf != null) {
                            "需要人机验证，请打开验证页后再试"
                        } else {
                            e.message ?: "重新获取播放地址失败"
                        }
                        if (cf != null) onNeedChallenge(cf.url)
                    },
                )
            }
        }
    }

    // 每 5 秒落一次进度：比"退出时记一次"可靠 —— 进程被杀时也不会丢
    LaunchedEffect(player) {
        while (true) {
            delay(5000)
            val s = player.snapshot.value
            if (s.positionMs > 0) {
                library.record(
                    HistoryEntry(
                        siteId = source.id,
                        videoId = brief.id,
                        title = brief.title,
                        cover = brief.cover,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                    ),
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            // 退出时立刻落一次，避免最多丢 5 秒进度
            val s = player.snapshot.value
            if (s.positionMs > 0) {
                library.record(
                    HistoryEntry(
                        siteId = source.id,
                        videoId = brief.id,
                        title = brief.title,
                        cover = brief.cover,
                        positionMs = s.positionMs,
                        durationMs = s.durationMs,
                    ),
                )
            }
            player.release()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(brief.title.ifBlank { brief.id }, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                PlayerSurface(player = player, modifier = Modifier.fillMaxSize())
            }

            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = {
                        if (snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
                    }) {
                        Text(
                            when (snapshot.status) {
                                PlayerStatus.PLAYING -> "暂停"
                                PlayerStatus.BUFFERING -> "缓冲中…"
                                else -> "播放"
                            },
                        )
                    }
                    Text(
                        text = "${fmt(snapshot.positionMs)} / ${fmt(snapshot.durationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }

                val duration = snapshot.durationMs
                if (duration > 0) {
                    Slider(
                        value = if (dragging) dragValue else (snapshot.positionMs.toFloat() / duration),
                        onValueChange = { v ->
                            dragging = true
                            dragValue = v
                        },
                        onValueChangeFinished = {
                            dragging = false
                            player.seekTo((dragValue * duration).toLong())
                        },
                    )
                }

                val status = snapshot.status
                if (status == PlayerStatus.ERROR) {
                    Text(
                        text = snapshot.errorMessage ?: "播放失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                authError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                OutlinedButton(
                    onClick = {
                        retriedAuth = false
                        player.prepare(current.url, current.mimeType, current.headers)
                        player.play()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重新加载") }

                Text("地址：${current.url.take(90)}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
