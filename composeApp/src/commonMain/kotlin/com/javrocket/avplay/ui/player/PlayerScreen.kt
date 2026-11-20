package com.javrocket.avplay.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.javrocket.avplay.ui.SystemBackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.platform.setImmersiveFullscreen
import com.javrocket.avplay.platform.setLandscapePlaybackFullscreen
import com.javrocket.avplay.model.Episode
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoComment
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.player.PlatformPlayer
import com.javrocket.avplay.player.PlayerSnapshot
import com.javrocket.avplay.player.PlayerStatus
import com.javrocket.avplay.player.PlayerSurface
import com.javrocket.avplay.player.videoAspectRatio
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
    /** 黄果短剧：竖屏铺满、隐藏导航栏，单击画面暂停/继续。 */
    portraitFullscreen: Boolean = false,
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

    val maxEpisodesHint = brief.episodeCount?.coerceAtLeast(1) ?: 1
    var episodeIndex by remember(brief.id) {
        mutableIntStateOf(
            library.resumeOf(source.id, brief.id)
                ?.resumeEpisodeAndPosition(maxEpisodesHint)
                ?.first
                ?: 1,
        )
    }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var episodeLine by remember { mutableStateOf(brief.subtitle) }
    var showEpisodeSheet by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1f) }
    var switchingEpisode by remember { mutableStateOf(false) }
    val totalEpisodes = remember(episodes, brief.episodeCount) {
        when {
            episodes.isNotEmpty() -> episodes.maxOf { it.index }
            brief.episodeCount != null && brief.episodeCount!! > 0 -> brief.episodeCount!!
            else -> 1
        }
    }

    LaunchedEffect(brief.id, portraitFullscreen) {
        if (!portraitFullscreen) return@LaunchedEffect
        source.detail(brief.id).onSuccess { detail ->
            episodes = detail.episodes
            episodeLine = detail.description.takeIf { it.isNotBlank() } ?: brief.subtitle
        }
    }

    LaunchedEffect(playbackSpeed) {
        player.setPlaybackSpeed(playbackSpeed)
    }

    var consumedEndUrl by remember { mutableStateOf<String?>(null) }

    fun persistProgress(positionMs: Long = player.snapshot.value.positionMs) {
        val s = player.snapshot.value
        val pos = if (positionMs > 0) positionMs else s.positionMs
        if (pos <= 0 && s.durationMs <= 0) return
        library.record(
            HistoryEntry(
                siteId = source.id,
                videoId = brief.id,
                title = brief.title,
                cover = brief.cover,
                positionMs = pos,
                durationMs = s.durationMs,
                episodeIndex = episodeIndex,
            ),
        )
    }

    fun switchEpisode(ep: Int) {
        if (ep == episodeIndex) {
            showEpisodeSheet = false
            return
        }
        persistProgress()
        scope.launch {
            switchingEpisode = true
            showEpisodeSheet = false
            retriedAuth = false
            authError = null
            source.playback(brief.id, ep).fold(
                onSuccess = { info ->
                    current = info
                    episodeIndex = ep
                    switchingEpisode = false
                    library.record(
                        HistoryEntry(
                            siteId = source.id,
                            videoId = brief.id,
                            title = brief.title,
                            cover = brief.cover,
                            positionMs = 0,
                            durationMs = 0,
                            episodeIndex = ep,
                        ),
                    )
                },
                onFailure = {
                    switchingEpisode = false
                    authError = it.message ?: "切换集数失败"
                },
            )
        }
    }

    fun advanceToNextEpisodeIfNeeded(forceNearEnd: Boolean = false) {
        if (!portraitFullscreen) return
        if (consumedEndUrl == current.url) return
        val snap = player.snapshot.value
        val dur = snap.durationMs
        val pos = snap.positionMs
        val ended = snap.status == PlayerStatus.ENDED
        val nearComplete = forceNearEnd || (dur > 3000 && pos >= dur - 2000)
        if (!ended && !nearComplete) return
        if (episodeIndex >= totalEpisodes) {
            consumedEndUrl = current.url
            persistProgress(dur.coerceAtLeast(pos))
            return
        }
        consumedEndUrl = current.url
        persistProgress(dur.coerceAtLeast(pos))
        val next = episodeIndex + 1
        library.record(
            HistoryEntry(
                siteId = source.id,
                videoId = brief.id,
                title = brief.title,
                cover = brief.cover,
                positionMs = 0,
                durationMs = 0,
                episodeIndex = next,
            ),
        )
        switchEpisode(next)
    }

    // 换地址就重建播放器（换集不串台的保证：prepare 内部会销毁旧 ExoPlayer）
    LaunchedEffect(current.url) {
        consumedEndUrl = null
        player.prepare(current.url, current.mimeType, current.headers)
        val resume = library.resumeOf(source.id, brief.id)
        val maxEp = totalEpisodes.coerceAtLeast(1)
        val (resumeEp, resumePos) = resume?.resumeEpisodeAndPosition(maxEp) ?: (episodeIndex to 0L)
        val startAt = if (resumeEp == episodeIndex) resumePos else 0L
        if (startAt > 3000) player.seekTo(startAt)
        if (portraitFullscreen) player.setPlaybackSpeed(playbackSpeed)
        player.play()
    }

    LaunchedEffect(
        snapshot.status,
        snapshot.positionMs,
        snapshot.durationMs,
        current.url,
        episodeIndex,
        totalEpisodes,
        portraitFullscreen,
    ) {
        if (!portraitFullscreen) return@LaunchedEffect
        if (consumedEndUrl == current.url) return@LaunchedEffect
        val dur = snapshot.durationMs
        val pos = snapshot.positionMs
        val ended = snapshot.status == PlayerStatus.ENDED
        val nearComplete = dur > 3000 && pos >= dur - 2000
        if (!ended && !nearComplete) return@LaunchedEffect
        // HLS 片尾常会卡在 BUFFERING 等下一片段，不应阻止自动连播
        if (!ended && !nearComplete && snapshot.status == PlayerStatus.BUFFERING) return@LaunchedEffect
        delay(600)
        val s2 = player.snapshot.value
        val stillDone = s2.status == PlayerStatus.ENDED ||
            (s2.durationMs > 3000 && s2.positionMs >= s2.durationMs - 2000)
        if (!stillDone) return@LaunchedEffect
        advanceToNextEpisodeIfNeeded(forceNearEnd = true)
    }

    // 401/403 自动重试：重新解析一次播放地址
    player.onHttpError = { code ->
        if (!retriedAuth && (code == 401 || code == 403)) {
            retriedAuth = true
            authError = "播放凭据被拒绝（HTTP $code），正在重新获取地址…"
            scope.launch {
                source.playback(brief.id, episodeIndex).fold(
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

    // 每 3 秒落一次进度，退出时再补一次
    LaunchedEffect(player, episodeIndex) {
        while (true) {
            delay(3000)
            val s = player.snapshot.value
            if (s.positionMs > 0 && s.status != PlayerStatus.ENDED) {
                persistProgress()
            }
            if (portraitFullscreen && episodeIndex < totalEpisodes) {
                advanceToNextEpisodeIfNeeded()
            }
        }
    }

    DisposableEffect(portraitFullscreen) {
        if (portraitFullscreen) setImmersiveFullscreen(true)
        onDispose {
            if (portraitFullscreen) setImmersiveFullscreen(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            setLandscapePlaybackFullscreen(false)
            persistProgress()
            player.release()
        }
    }

    if (portraitFullscreen) {
        HuangGuoPortraitPlayer(
            player = player,
            playbackUrl = current.url,
            snapshot = snapshot,
            brief = brief,
            episodeIndex = episodeIndex,
            totalEpisodes = totalEpisodes,
            episodeLine = episodeLine,
            playbackSpeed = playbackSpeed,
            episodes = episodes,
            switchingEpisode = switchingEpisode,
            showEpisodeSheet = showEpisodeSheet,
            dragging = dragging,
            dragValue = dragValue,
            onBack = onBack,
            onTogglePlay = {
                if (snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
            },
            onSelectSpeed = { playbackSpeed = it },
            onOpenEpisodes = { showEpisodeSheet = true },
            onDismissEpisodes = { showEpisodeSheet = false },
            onSelectEpisode = { switchEpisode(it) },
            onDragProgress = { v ->
                dragging = true
                dragValue = v
            },
            onDragProgressFinished = { v ->
                dragging = false
                val duration = snapshot.durationMs
                if (duration > 0) {
                    val pos = (v * duration).toLong()
                    player.seekTo(pos)
                    persistProgress(pos)
                }
            },
        )
        return
    }

    var showSpeedMenu by remember { mutableStateOf(false) }
    var landscapeFullscreen by remember { mutableStateOf(false) }
    val tapVideo = remember { MutableInteractionSource() }
    val xchinaSource = source as? XChinaSource
    var comments by remember { mutableStateOf<List<VideoComment>?>(null) }
    var commentTotal by remember { mutableStateOf<Int?>(null) }
    var commentsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(brief.id, xchinaSource) {
        val x = xchinaSource ?: return@LaunchedEffect
        comments = null
        commentsError = null
        x.allComments(brief.id).fold(
            onSuccess = { (list, total) ->
                comments = list
                commentTotal = total
            },
            onFailure = { err ->
                comments = emptyList()
                commentsError = err.message
                val cf = err as? AppError.CloudflareChallenge
                if (cf != null) onNeedChallenge(cf.url)
            },
        )
    }

    DisposableEffect(landscapeFullscreen) {
        setLandscapePlaybackFullscreen(landscapeFullscreen)
        player.setFitVideoInView(landscapeFullscreen)
        onDispose {
            if (landscapeFullscreen) {
                setLandscapePlaybackFullscreen(false)
                player.setFitVideoInView(false)
            }
        }
    }

    if (landscapeFullscreen) {
        SystemBackHandler(enabled = true) { landscapeFullscreen = false }
        LandscapeFullscreenLayout(
            currentUrl = current.url,
            player = player,
            snapshot = snapshot,
            playbackSpeed = playbackSpeed,
            showSpeedMenu = showSpeedMenu,
            dragging = dragging,
            dragValue = dragValue,
            tapVideo = tapVideo,
            onExitFullscreen = { landscapeFullscreen = false },
            onTogglePlay = {
                if (snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
            },
            onOpenSpeedMenu = { showSpeedMenu = true },
            onDismissSpeedMenu = { showSpeedMenu = false },
            onSelectSpeed = { playbackSpeed = it },
            onDragProgress = { v ->
                dragging = true
                dragValue = v
            },
            onDragProgressFinished = {
                dragging = false
                val duration = snapshot.durationMs
                if (duration > 0) player.seekTo((dragValue * duration).toLong())
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(brief.title.ifBlank { brief.id }, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    Box {
                        TextButton(onClick = { showSpeedMenu = true }) {
                            Text("${formatSpeedLabel(playbackSpeed)} ▾")
                        }
                        DropdownMenu(
                            expanded = showSpeedMenu,
                            onDismissRequest = { showSpeedMenu = false },
                        ) {
                            PLAYER_SPEED_STEPS.forEach { speed ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            formatSpeedLabel(speed),
                                            fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        playbackSpeed = speed
                                        showSpeedMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            val videoRatio = snapshot.videoAspectRatio()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(videoRatio)
                    .background(Color.Black)
                    .clickable(interactionSource = tapVideo, indication = null) {
                        if (snapshot.status == PlayerStatus.PLAYING) player.pause() else player.play()
                    },
                contentAlignment = Alignment.Center,
            ) {
                VideoSurface(
                    player = player,
                    playbackUrl = current.url,
                    snapshot = snapshot,
                    modifier = Modifier.fillMaxSize(),
                    fitWithinBounds = true,
                    fillPortraitScreen = false,
                )
                if (snapshot.status == PlayerStatus.BUFFERING) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White,
                    )
                }
                TextButton(
                    onClick = { landscapeFullscreen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.45f)),
                ) {
                    Text("全屏", color = Color.White, fontSize = 13.sp)
                }
            }

            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val duration = snapshot.durationMs
                if (duration > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = fmt(if (dragging) (dragValue * duration).toLong() else snapshot.positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                        )
                        Slider(
                            modifier = Modifier.weight(1f),
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
                        Text(
                            text = fmt(duration),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                        )
                    }
                }

                if (snapshot.status == PlayerStatus.ERROR) {
                    Text(
                        text = snapshot.errorMessage ?: "播放失败",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                authError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (snapshot.status == PlayerStatus.ERROR || authError != null) {
                    OutlinedButton(
                        onClick = {
                            retriedAuth = false
                            authError = null
                            player.prepare(current.url, current.mimeType, current.headers)
                            player.play()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("重新加载") }
                }
            }

            if (xchinaSource != null) {
                when (val list = comments) {
                    null -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )

                    else -> {
                        val total = commentTotal ?: list.size
                        if (list.isNotEmpty() || total > 0) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                item(key = "comments-header") {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("评论 $total", style = MaterialTheme.typography.titleMedium)
                                        commentsError?.let {
                                            Text(
                                                it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                                items(list, key = { it.id }) { comment ->
                                    VideoCommentCard(comment)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(
    player: PlatformPlayer,
    playbackUrl: String,
    snapshot: PlayerSnapshot,
    modifier: Modifier = Modifier,
    fitWithinBounds: Boolean,
    fillPortraitScreen: Boolean = false,
) {
    val ratio = snapshot.videoAspectRatio()
    BoxWithConstraints(modifier) {
        val surfaceModifier = if (fitWithinBounds) {
            val containerRatio = maxWidth / maxHeight
            if (containerRatio > ratio) {
                Modifier
                    .height(maxHeight)
                    .aspectRatio(ratio)
                    .align(Alignment.Center)
            } else {
                Modifier
                    .width(maxWidth)
                    .aspectRatio(ratio)
                    .align(Alignment.Center)
            }
        } else {
            Modifier.fillMaxSize()
        }
        androidx.compose.runtime.key(playbackUrl) {
            PlayerSurface(
                player = player,
                modifier = surfaceModifier,
                fillPortraitScreen = fillPortraitScreen,
                fitWithinBounds = fitWithinBounds,
            )
        }
    }
}

@Composable
private fun LandscapeFullscreenLayout(
    currentUrl: String,
    player: PlatformPlayer,
    snapshot: com.javrocket.avplay.player.PlayerSnapshot,
    playbackSpeed: Float,
    showSpeedMenu: Boolean,
    dragging: Boolean,
    dragValue: Float,
    tapVideo: MutableInteractionSource,
    onExitFullscreen: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenSpeedMenu: () -> Unit,
    onDismissSpeedMenu: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onDragProgress: (Float) -> Unit,
    onDragProgressFinished: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = tapVideo, indication = null, onClick = onTogglePlay),
    ) {
        VideoSurface(
            player = player,
            playbackUrl = currentUrl,
            snapshot = snapshot,
            modifier = Modifier.fillMaxSize(),
            fitWithinBounds = true,
            fillPortraitScreen = false,
        )
        if (snapshot.status == PlayerStatus.BUFFERING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }
        TextButton(
            onClick = onExitFullscreen,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            Text("←", color = Color.White, fontSize = 22.sp)
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            TextButton(onClick = onOpenSpeedMenu) {
                Text("${formatSpeedLabel(playbackSpeed)} ▾", color = Color.White)
            }
            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = onDismissSpeedMenu) {
                PLAYER_SPEED_STEPS.forEach { speed ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                formatSpeedLabel(speed),
                                fontWeight = if (speed == playbackSpeed) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            onSelectSpeed(speed)
                            onDismissSpeedMenu()
                        },
                    )
                }
            }
        }
        val duration = snapshot.durationMs
        if (duration > 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fmt(if (dragging) (dragValue * duration).toLong() else snapshot.positionMs),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier.width(48.dp),
                )
                Slider(
                    modifier = Modifier.weight(1f),
                    value = if (dragging) dragValue else (snapshot.positionMs.toFloat() / duration),
                    onValueChange = onDragProgress,
                    onValueChangeFinished = onDragProgressFinished,
                )
                Text(
                    text = fmt(duration),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

private val PLAYER_SPEED_STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)

private fun formatSpeedLabel(speed: Float): String =
    if (speed == speed.toLong().toFloat()) "${speed.toLong()}x" else "${speed}x"

private fun fmt(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
