package com.javrocket.avplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.AppGraph
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.huangguo.HuangGuoSource
import com.javrocket.avplay.store.HistoryEntry
import com.javrocket.avplay.ui.challenge.ChallengeSheet
import com.javrocket.avplay.ui.history.BrowseHistoryScreen
import com.javrocket.avplay.ui.history.historyEntryToBrief
import com.javrocket.avplay.service.xchina.XChinaDetailParser
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.ui.detail.DetailScreen
import com.javrocket.avplay.ui.detail.ModelBrowseScreen
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
                val homeSources = remember(sources) {
                    sources.filter { it.id == SiteIds.XCHINA }
                }
                val huangguoSource = remember(sources) {
                    sources.filterIsInstance<HuangGuoSource>().firstOrNull()
                }
                var mainTab by remember { mutableStateOf(MainTab.Home) }
                var siteId by remember(homeSources) {
                    mutableStateOf(homeSources.firstOrNull()?.id ?: SiteIds.XCHINA)
                }
                var opened by remember { mutableStateOf<VideoBrief?>(null) }
                // 正在播放的地址；非空时进播放页（播放页自带返回）
                var playing by remember { mutableStateOf<com.javrocket.avplay.model.PlaybackInfo?>(null) }
                // 全局浮层：同步页 / 诊断页。用单个枚举字符串而不是两个布尔，
                // 是为了从结构上保证"两者不会同时打开"。
                var overlay by remember { mutableStateOf<String?>(null) }
                var modelBrowse by remember { mutableStateOf<ModelBrowseTarget?>(null) }

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
                val showBottomNav = playing == null && current == null && modelBrowse == null

                fun openFromHistory(entry: HistoryEntry) {
                    overlay = null
                    val brief = historyEntryToBrief(entry)
                    val target = sources.firstOrNull { it.id == entry.siteId } ?: return
                    if (target is HuangGuoSource) {
                        mainTab = MainTab.HuangGuo
                        resolvingPlayback = true
                        scope.launch {
                            val maxEp = 99
                            val (ep, _) = entry.resumeEpisodeAndPosition(maxEp)
                            target.playback(brief.id, ep).fold(
                                onSuccess = { info ->
                                    opened = brief
                                    playing = info
                                    resolvingPlayback = false
                                },
                                onFailure = { err ->
                                    resolvingPlayback = false
                                    val cf = err as? AppError.CloudflareChallenge
                                    if (cf != null) challengeUrl = cf.url else opened = brief
                                },
                            )
                        }
                    } else {
                        mainTab = MainTab.Home
                        if (entry.siteId == SiteIds.XCHINA) siteId = SiteIds.XCHINA
                        opened = brief
                    }
                }

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

                val bottomBarHeight = rememberMainBottomBarHeight()
                Box(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(bottom = if (showBottomNav) bottomBarHeight else 0.dp),
                    ) {
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
                        } else if (modelBrowse != null && source is XChinaSource) {
                            val target = modelBrowse!!
                            ModelBrowseScreen(
                                source = source,
                                modelId = target.modelId,
                                title = target.title,
                                imageLoader = graph.imageLoader,
                                reloadTick = reloadTick,
                                onBack = { modelBrowse = null },
                                onOpen = { brief ->
                                    modelBrowse = null
                                    opened = brief
                                },
                                onNeedChallenge = { challengeUrl = it },
                            )
                        } else if (current == null) {
                            HomeScreen(
                                source = source,
                                imageLoader = graph.imageLoader,
                                reloadTick = reloadTick,
                                library = graph.library,
                                onOpen = ::openBrief,
                                onNeedChallenge = { challengeUrl = it },
                                onOpenSync = { overlay = OVERLAY_SYNC },
                                onOpenDiagnostics = { overlay = OVERLAY_DIAG },
                                onOpenHistory = { overlay = OVERLAY_HISTORY },
                                homeSources = if (mainTab == MainTab.Home) homeSources else emptyList(),
                                onSelectSource = if (mainTab == MainTab.Home && homeSources.size > 1) {
                                    { id -> siteId = id }
                                } else {
                                    null
                                },
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
                                onOpenActor = if (source is XChinaSource) {
                                    { actor ->
                                        val modelId = XChinaDetailParser.modelIdFromLink(actor.link)
                                        if (modelId != null) {
                                            modelBrowse = ModelBrowseTarget(modelId, actor.value)
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    if (showBottomNav) {
                        MainBottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            selected = mainTab,
                            onSelect = { tab ->
                                if (tab == mainTab) return@MainBottomBar
                                mainTab = tab
                                opened = null
                                playing = null
                                modelBrowse = null
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

                                OVERLAY_HISTORY -> BrowseHistoryScreen(
                                    library = graph.library,
                                    imageLoader = graph.imageLoader,
                                    sources = sources,
                                    onBack = { overlay = null },
                                    onOpenEntry = ::openFromHistory,
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
private const val OVERLAY_HISTORY = "history"

private data class ModelBrowseTarget(val modelId: String, val title: String)

private val NavAccent = Color(0xFFD68A94)

private enum class MainTab { Home, HuangGuo }

/** 图标 + 文案一行，不含系统手势条区域 */
private val BottomBarTabsHeight = 50.dp

@Composable
private fun rememberMainBottomBarHeight(): Dp {
    val systemNav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return BottomBarTabsHeight + systemNav + 1.dp
}

@Composable
private fun MainBottomBar(
    modifier: Modifier = Modifier,
    selected: MainTab,
    onSelect: (MainTab) -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        Row(
            Modifier
                .fillMaxWidth()
                .height(BottomBarTabsHeight)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactNavItem(
                selected = selected == MainTab.Home,
                icon = "⌂",
                label = "首页",
                onClick = { onSelect(MainTab.Home) },
            )
            CompactNavItem(
                selected = selected == MainTab.HuangGuo,
                icon = "▣",
                label = "黄果短剧",
                onClick = { onSelect(MainTab.HuangGuo) },
            )
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun RowScope.CompactNavItem(
    selected: Boolean,
    icon: String,
    label: String,
    onClick: () -> Unit,
) {
    val inactive = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(if (selected) NavAccent.copy(alpha = 0.14f) else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = icon,
                fontSize = 16.sp,
                color = if (selected) NavAccent else inactive,
            )
        }
        Text(
            text = label,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) NavAccent else inactive,
        )
    }
}

