package com.javrocket.avplay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.model.PlaybackInfo
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoDetail
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.HuangGuoService
import com.javrocket.avplay.ui.media.ImageLoader
import com.javrocket.avplay.ui.media.RemoteImage
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(val detail: VideoDetail) : DetailUiState
    data class Failed(val error: Throwable) : DetailUiState
}

sealed interface PlaybackUiState {
    data object Idle : PlaybackUiState
    data object Loading : PlaybackUiState
    data class Ready(val info: PlaybackInfo) : PlaybackUiState
    data class Failed(val error: Throwable) : PlaybackUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    source: ContentSource,
    brief: VideoBrief,
    imageLoader: ImageLoader,
    library: com.javrocket.avplay.store.LibraryStore,
    reloadTick: Int,
    onBack: () -> Unit,
    onNeedChallenge: (String) -> Unit,
    onPlay: (PlaybackInfo) -> Unit,
    onOpenActor: ((ValueLink) -> Unit)? = null,
) {
    var state by remember { mutableStateOf<DetailUiState>(DetailUiState.Loading) }
    var playback by remember { mutableStateOf<PlaybackUiState>(PlaybackUiState.Idle) }
    var retryKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val coverHeaders = remember(source.id, source.baseUrl) {
        if (source.id == HuangGuoService.SITE_ID) {
            HuangGuoImageCrypto.imageRequestHeaders(source.baseUrl)
        } else {
            emptyMap()
        }
    }

    LaunchedEffect(source.id, brief.id, retryKey, reloadTick) {
        state = DetailUiState.Loading
        source.detail(brief.id).fold(
            onSuccess = { state = DetailUiState.Ready(it) },
            onFailure = { state = DetailUiState.Failed(it) },
        )
    }

    var pressedFavorite by remember { mutableStateOf(library.isFavorite(source.id, brief.id)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val t = (state as? DetailUiState.Ready)?.detail?.brief?.title
                        ?: brief.title.ifBlank { brief.id }
                    Text(t, maxLines = 1)
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = {
                        val entry = com.javrocket.avplay.store.HistoryEntry(
                            siteId = source.id,
                            videoId = brief.id,
                            title = brief.title,
                            cover = brief.cover,
                            positionMs = library.resumeOf(source.id, brief.id)?.positionMs ?: 0,
                            durationMs = library.resumeOf(source.id, brief.id)?.durationMs ?: 0,
                        )
                        pressedFavorite = library.toggleFavorite(entry)
                    }) { Text(if (pressedFavorite) "已收藏" else "收藏") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                DetailUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is DetailUiState.Failed -> {
                    val challenge = s.error as? AppError.CloudflareChallenge
                    // 部分站点（如 JavDB 的受限条目）对未登录用户直接跳登录页 —— 这不是解析失败，
                    // 解析器会抛 AuthenticationRequired，UI 要给的是"去登录"而不是"重试"。
                    val needLogin = s.error is AppError.AuthenticationRequired
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (needLogin) "该条目需要登录站点后才能查看。登录一次后 Cookie 会共享给请求层，不用再登。"
                            else s.error.message ?: "详情加载失败",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (challenge != null) {
                                Button(onClick = { onNeedChallenge(challenge.url) }) { Text("打开验证页") }
                            }
                            if (needLogin) {
                                Button(onClick = { onNeedChallenge("${source.baseUrl.trimEnd('/')}/login") }) {
                                    Text("打开登录页")
                                }
                            }
                            OutlinedButton(onClick = { retryKey++ }) { Text("重试") }
                            TextButton(onClick = onBack) { Text("返回列表") }
                        }
                    }
                }

                is DetailUiState.Ready -> {
                    val d = s.detail
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        // 封面：详情页给大一点，能看清是不是点对了片子
                        RemoteImage(
                            loader = imageLoader,
                            url = d.brief.cover ?: brief.cover,
                            modifier = Modifier.fillMaxWidth().height(220.dp),
                            headers = coverHeaders,
                        )

                        Text(
                            d.brief.title.ifBlank { brief.title.ifBlank { brief.id } },
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        val meta = listOfNotNull(
                            d.brief.code?.takeIf { it.isNotBlank() }?.let { "番号 $it" },
                            d.brief.duration?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) {
                            Text(meta, style = MaterialTheme.typography.bodySmall)
                        }

                        if (d.description.isNotBlank()) {
                            Text(d.description, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (d.tags.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                d.tags.take(6).forEach { tag ->
                                    Text("#${tag.value}", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }

                        if (d.actors.isNotEmpty()) {
                            ActorRow(
                                actors = d.actors,
                                clickable = source.id == SiteIds.XCHINA && onOpenActor != null,
                                onOpenActor = onOpenActor,
                            )
                        }

                        if (d.episodes.isNotEmpty()) {
                            Text("共 ${d.episodes.size} 集", style = MaterialTheme.typography.bodySmall)
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    playback = PlaybackUiState.Loading
                                    source.playback(d.brief.id).fold(
                                        onSuccess = { info ->
                                            playback = PlaybackUiState.Ready(info)
                                            // 解析成功直接进播放页，少一次点击
                                            onPlay(info)
                                        },
                                        onFailure = { playback = PlaybackUiState.Failed(it) },
                                    )
                                }
                            },
                            enabled = playback !is PlaybackUiState.Loading,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val resume = library.resumeOf(source.id, brief.id)
                            val label = when {
                                playback is PlaybackUiState.Loading -> "解析中…"
                                resume != null && resume.positionMs > 3000 -> "继续播放 ${fmtShort(resume.positionMs)}"
                                else -> "播放"
                            }
                            Text(label)
                        }

                        when (val p = playback) {
                            PlaybackUiState.Idle -> Unit
                            PlaybackUiState.Loading -> Text("正在解析…", style = MaterialTheme.typography.bodySmall)

                            is PlaybackUiState.Failed -> {
                                val challenge = p.error as? AppError.CloudflareChallenge
                                val needLogin = p.error is AppError.AuthenticationRequired
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        p.error.message ?: "播放地址解析失败",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (challenge != null) {
                                        OutlinedButton(onClick = { onNeedChallenge(challenge.url) }) {
                                            Text("打开验证页")
                                        }
                                    }
                                    if (needLogin) {
                                        OutlinedButton(
                                            onClick = { onNeedChallenge("${source.baseUrl.trimEnd('/')}/login") },
                                        ) { Text("打开登录页") }
                                    }
                                }
                            }

                            is PlaybackUiState.Ready -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActorRow(
    actors: List<ValueLink>,
    clickable: Boolean,
    onOpenActor: ((ValueLink) -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("演员：", style = MaterialTheme.typography.bodySmall)
        actors.forEachIndexed { index, actor ->
            if (index > 0) {
                Text("、", style = MaterialTheme.typography.bodySmall)
            }
            if (clickable && actor.link.isNotBlank()) {
                TextButton(
                    onClick = { onOpenActor?.invoke(actor) },
                    modifier = Modifier.widthIn(min = 1.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(actor.value, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(actor.value, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun fmtShort(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
