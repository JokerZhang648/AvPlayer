package com.javrocket.avplay.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.AppGraph
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.huangguo.HuangGuoSource
import com.javrocket.avplay.ui.challenge.ChallengeSheet
import com.javrocket.avplay.ui.detail.DetailScreen
import com.javrocket.avplay.ui.home.HomeScreen
import kotlinx.coroutines.launch

/**
 * 全 App 唯一的根 Composable。Android 的 MainActivity 与 iOS 的 MainViewController
 * 都调这一个函数 —— “一套 UI 跑两端”的落点。
 *
 * Phase 3 起多了一层“人机验证”浮层：任何页面撞到 CF 都能把 URL 抛回这里，
 * 由根节点统一弹出 WebView（页面自己不需要知道 WebView 怎么实现）。
 */
@Composable
fun App(graph: AppGraph = remember { AppGraph() }) {
    MaterialTheme {
        Surface {
            var ready by remember { mutableStateOf(false) }
            var challengeUrl by remember { mutableStateOf<String?>(null) }
            // 过完验证后自增，页面用它当 reload key —— 相当于“强制重新请求一次”
            var reloadTick by remember { mutableIntStateOf(0) }

            // UA 必须在建 HttpClient 之前取到，所以这里门控一次
            LaunchedEffect(Unit) {
                graph.bootstrap()
                ready = true
            }

            if (!ready) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val sources = graph.sources()
                val homeSources = remember(sources) { sources.filter { it.id != SiteIds.HUANGGUO } }
                val huangguoSource = remember(sources) {
                    sources.filterIsInstance<HuangGuoSource>().firstOrNull()
                }
                var mainTab by remember { mutableStateOf(MainTab.Home) }
                var siteId by remember(homeSources) {
                    mutableStateOf(homeSources.firstOrNull()?.id ?: sources.first().id)
                }
                var opened by remember { mutableStateOf<VideoBrief?>(null) }
                // 正在播放的地址；非空时进播放页（播放页自带返回）
                var playing by remember { mutableStateOf<com.javrocket.avplay.model.PlaybackInfo?>(null) }
                // 全局浮层：同步页 / 诊断页。用单个枚举字符串而不是两个布尔，
                // 是为了从结构上保证"两者不会同时打开"。
                var overlay by remember { mutableStateOf<String?>(null) }

                val source = when (mainTab) {
                    MainTab.HuangGuo -> huangguoSource ?: sources.first()
                    MainTab.Home -> homeSources.firstOrNull { it.id == siteId }
                        ?: homeSources.firstOrNull()
                        ?: sources.first()
                }
                val current = opened
                val scope = rememberCoroutineScope()
                var resolvingPlayback by remember { mutableStateOf(false) }
                val huangguoDirectPlay = source is HuangGuoSource
                val showBottomNav = playing == null && current == null

                fun openBrief(brief: VideoBrief) {
                    if (source is HuangGuoSource) {
                        resolvingPlayback = true
                        scope.launch {
                            val maxEp = brief.episodeCount?.coerceAtLeast(1) ?: 99
                            val resume = graph.library.resumeOf(source.id, brief.id)
                            val (ep, _) = resume?.resumeEpisodeAndPosition(maxEp) ?: (1 to 0L)
                            source.playback(brief.id, ep).fold(
                                onSuccess = { info ->
                                    opened = brief
                                    playing = info
                                    resolvingPlayback = false
                                },
                                onFailure = { err ->
                                    resolvingPlayback = false
                                    val cf = err as? AppError.CloudflareChallenge
                                    if (cf != null) {
                                        challengeUrl = cf.url
                                    } else {
                                        opened = brief
                                    }
                                },
                            )
                        }
                    } else {
                        opened = brief
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        bottomBar = {
                            if (showBottomNav) {
                                MainBottomBar(
                                    selected = mainTab,
                                    onSelect = { tab ->
                                        if (tab == mainTab) return@MainBottomBar
                                        mainTab = tab
                                        opened = null
                                        playing = null
                                        when (tab) {
                                            MainTab.HuangGuo -> Unit
                                            MainTab.Home -> {
                                                if (siteId == SiteIds.HUANGGUO || homeSources.none { it.id == siteId }) {
                                                    siteId = homeSources.firstOrNull()?.id ?: siteId
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding)) {
                            val active = playing
                            if (active != null && current != null) {
                                com.javrocket.avplay.ui.player.PlayerScreen(
                                    source = source,
                                    brief = current,
                                    initial = active,
                                    library = graph.library,
                                    portraitFullscreen = huangguoDirectPlay,
                                    onBack = {
                                        playing = null
                                        if (huangguoDirectPlay) opened = null
                                    },
                                    onNeedChallenge = { challengeUrl = it },
                                )
                            } else if (current == null) {
                                if (mainTab == MainTab.Home) {
                                    SiteBar(
                                        sources = homeSources,
                                        selectedId = source.id,
                                        onSelect = { siteId = it },
                                    )
                                }
                                HomeScreen(
                                    source = source,
                                    imageLoader = graph.imageLoader,
                                    reloadTick = reloadTick,
                                    library = graph.library,
                                    onOpen = ::openBrief,
                                    onNeedChallenge = { challengeUrl = it },
                                    onOpenSync = { overlay = OVERLAY_SYNC },
                                    onOpenDiagnostics = { overlay = OVERLAY_DIAG },
                                )
                            } else {
                                DetailScreen(
                                    source = source,
                                    brief = current,
                                    imageLoader = graph.imageLoader,
                                    library = graph.library,
                                    reloadTick = reloadTick,
                                    onBack = { opened = null },
                                    onNeedChallenge = { challengeUrl = it },
                                    onPlay = { playing = it },
                                )
                            }
                        }
                    }

                    // 浮层必须盖在内容之上、验证浮层之下：同步/诊断里也可能撞到 CF，
                    // 那时需要 ChallengeSheet 还能弹在最上层。
                    val ov = overlay
                    if (ov != null) {
                        Surface(Modifier.fillMaxSize()) {
                            when (ov) {
                                OVERLAY_SYNC -> com.javrocket.avplay.ui.sync.SyncScreen(
                                    sync = graph.sync,
                                    onBack = { overlay = null },
                                )

                                OVERLAY_DIAG -> com.javrocket.avplay.ui.diag.DiagnosticsScreen(
                                    diagnostics = graph.diagnostics,
                                    onBack = { overlay = null },
                                )

                                else -> Unit
                            }
                        }
                    }

                    if (resolvingPlayback) {
                        Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    ChallengeSheet(
                        visible = challengeUrl != null,
                        initialUrl = challengeUrl.orEmpty(),
                        onDismiss = { challengeUrl = null },
                        onVerified = {
                            challengeUrl = null
                            reloadTick++
                        },
                    )
                }
            }
        }
    }
}

private const val OVERLAY_SYNC = "sync"
private const val OVERLAY_DIAG = "diag"

private val NavAccent = Color(0xFFD68A94)

private enum class MainTab { Home, HuangGuo }

@Composable
private fun MainBottomBar(
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == MainTab.Home,
            onClick = { onSelect(MainTab.Home) },
            icon = {
                Text(
                    "⌂",
                    fontSize = 22.sp,
                    color = if (selected == MainTab.Home) NavAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            label = { Text("首页") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = NavAccent,
                selectedTextColor = NavAccent,
                indicatorColor = NavAccent.copy(alpha = 0.12f),
            ),
        )
        NavigationBarItem(
            selected = selected == MainTab.HuangGuo,
            onClick = { onSelect(MainTab.HuangGuo) },
            icon = {
                Text(
                    "▣",
                    fontSize = 20.sp,
                    color = if (selected == MainTab.HuangGuo) NavAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            label = { Text("黄果短剧") },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = NavAccent,
                selectedTextColor = NavAccent,
                indicatorColor = NavAccent.copy(alpha = 0.12f),
            ),
        )
    }
}

/**
 * 站点切换条。
 *
 * 站点从 3 个涨到 6 个后，固定 Row 会把英文名挤成省略号甚至互相重叠，
 * 所以改成横向可滚动 —— 手机上"能划到"比"全塞进一屏"更重要（后者会让每个 tab 只剩 1~2 个字）。
 */
@Composable
private fun SiteBar(
    sources: List<ContentSource>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        sources.forEach { s ->
            TextButton(onClick = { onSelect(s.id) }) {
                Text(
                    text = s.displayName,
                    fontWeight = if (s.id == selectedId) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
