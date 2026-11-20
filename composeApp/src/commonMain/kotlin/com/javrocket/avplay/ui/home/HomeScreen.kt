package com.javrocket.avplay.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.model.ValueLink
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.HuangGuoService
import com.javrocket.avplay.service.huangguo.HuangGuoSource
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.ui.media.ImageLoader
import com.javrocket.avplay.ui.media.RemoteThumb

sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Ready(val sections: List<VideoSection>) : HomeUiState

    /**
     * 失败一定带原始异常，而不是字符串 —— 因为 UI 要按类型分流：
     * CF 挑战要弹验证页，登录要跳登录，其余才是“重试”。
     * 反编译原实现里 UI 也是拿具名异常判定的（ErrorKt.toException）。
     */
    data class Failed(val error: Throwable) : HomeUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    source: ContentSource,
    imageLoader: ImageLoader,
    reloadTick: Int,
    library: com.javrocket.avplay.store.LibraryStore,
    onOpen: (VideoBrief) -> Unit,
    onNeedChallenge: (String) -> Unit,
    onOpenSync: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    homeSources: List<ContentSource> = emptyList(),
    onSelectSource: ((String) -> Unit)? = null,
    onOpenActor: ((ValueLink) -> Unit)? = null,
) {
    if (source is HuangGuoSource) {
        HuangGuoHomeScreen(
            source = source,
            imageLoader = imageLoader,
            reloadTick = reloadTick,
            onOpen = onOpen,
            onNeedChallenge = onNeedChallenge,
            onOpenSync = onOpenSync,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenHistory = onOpenHistory,
        )
        return
    }

    var state by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var retryKey by remember { mutableIntStateOf(0) }
    var homeTab by remember { mutableStateOf(XChinaHomeTab.HOT) }
    var showSearch by remember { mutableStateOf(false) }
    val xchinaSource = source as? XChinaSource

    if (showSearch && xchinaSource != null) {
        XChinaSearchScreen(
            source = xchinaSource,
            imageLoader = imageLoader,
            reloadTick = reloadTick,
            onBack = { showSearch = false },
            onOpen = onOpen,
            onNeedChallenge = onNeedChallenge,
            onOpenActor = onOpenActor,
        )
        return
    }
    val coverHeaders = remember(source.id, source.baseUrl) {
        if (source.id == HuangGuoService.SITE_ID) {
            HuangGuoImageCrypto.imageRequestHeaders(source.baseUrl)
        } else {
            emptyMap()
        }
    }

    LaunchedEffect(source.id, retryKey, reloadTick) {
        state = HomeUiState.Loading
        val result = source.home()
        result.fold(
            onSuccess = { feed ->
                // 首页解析结果的形状（诊断用）：区块数 + 第一项的关键字段
                val first = feed.sections.firstOrNull()?.items?.firstOrNull()
                com.javrocket.avplay.net.platformLog(
                    "UI",
                    "home sections=${feed.sections.size} " +
                        "first=id:${first?.id} cover:${first?.cover} title:${first?.title?.take(12)}",
                )
                state = HomeUiState.Ready(feed.sections)
            },
            onFailure = { state = HomeUiState.Failed(it) },
        )    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AvPlay") },
                // 同步与诊断放在顶栏：这两个都是"全局"能力，不属于某个站点，
                // 挂在站点详情/首页内容里会让人以为是站点功能。
                actions = {
                    if (source.id == SiteIds.XCHINA) {
                        TextButton(onClick = { showSearch = true }) { Text("搜索") }
                    }
                    TextButton(onClick = onOpenHistory) { Text("历史浏览") }
                    TextButton(onClick = onOpenSync) { Text("同步") }
                    TextButton(onClick = onOpenDiagnostics) { Text("诊断") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (homeSources.isNotEmpty() && onSelectSource != null) {
                HomeSiteBar(
                    sources = homeSources,
                    selectedId = source.id,
                    onSelect = onSelectSource,
                )
            }
            if (source.id == SiteIds.XCHINA) {
                XChinaHomeTabRow(
                    selected = homeTab,
                    onSelect = { homeTab = it },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                HomeUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                is HomeUiState.Failed -> {
                    val challenge = s.error as? AppError.CloudflareChallenge
                    val needLogin = s.error is AppError.AuthenticationRequired
                    Column(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = s.error.message ?: "加载失败",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (challenge != null) {
                            Text(
                                "站点要求人机验证。点下面按钮打开网页验证一次，验证通过后会自动继续加载。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (needLogin) {
                            Text(
                                "该站点需要登录。登录一次后 Cookie 会共享给请求层。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                        }
                    }
                }

                is HomeUiState.Ready -> {
                    val posterGrid = source.id == SiteIds.XCHINA
                    if (posterGrid && homeTab == XChinaHomeTab.HOT) {
                        val gridItems = s.sections.flatMap { it.items }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(
                                items = gridItems,
                                key = { "hot-${it.id}" },
                            ) { item ->
                                XChinaWidePosterCard(
                                    item = item,
                                    imageLoader = imageLoader,
                                    coverHeaders = coverHeaders,
                                    onClick = { onOpen(item) },
                                    onOpenActor = null,
                                )
                            }
                        }
                    } else if (posterGrid) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("敬请期待", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                            s.sections.forEach { section ->
                                item(key = "header-${section.title}") {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                    )
                                }
                                items(items = section.items, key = { "${section.title}-${it.id}" }) { item ->
                                    VideoRow(
                                        item = item,
                                        imageLoader = imageLoader,
                                        coverHeaders = coverHeaders,
                                        onClick = { onOpen(item) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

private enum class XChinaHomeTab(val label: String) {
    HOT("近期热门"),
}

@Composable
private fun XChinaHomeTabRow(
    selected: XChinaHomeTab,
    onSelect: (XChinaHomeTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        XChinaHomeTab.entries.forEach { tab ->
            TextButton(onClick = { onSelect(tab) }) {
                Text(
                    text = tab.label,
                    fontWeight = if (tab == selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (tab == selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

@Composable
private fun HomeSiteBar(
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

@Composable
private fun VideoRow(
    item: VideoBrief,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String>,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteThumb(loader = imageLoader, url = item.cover, headers = coverHeaders)
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title.ifBlank { item.id },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = listOfNotNull(
                    item.code?.takeIf { it.isNotBlank() },
                    item.duration?.takeIf { it.isNotBlank() },
                    item.rating?.let { "★ $it" },
                ).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** 历史/续播行：带进度条和来源站点标签。 */
@Composable
internal fun ContinueRow(
    entry: com.javrocket.avplay.store.HistoryEntry,
    imageLoader: ImageLoader,
    coverHeaders: Map<String, String>,
    siteLabel: String? = null,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RemoteThumb(loader = imageLoader, url = entry.cover, headers = coverHeaders)
            Column(Modifier.weight(1f)) {
                if (!siteLabel.isNullOrBlank()) {
                    Text(
                        text = siteLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = entry.title.ifBlank { entry.videoId },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val posSec = entry.positionMs / 1000
                val durSec = entry.durationMs / 1000
                val epHint = if (entry.episodeIndex > 1) "第${entry.episodeIndex}集 · " else ""
                Text(
                    text = epHint + "看到 %02d:%02d%s".format(
                        posSec / 60,
                        posSec % 60,
                        if (durSec > 0) " / %02d:%02d".format(durSec / 60, durSec % 60) else "",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.durationMs > 0) {
                    LinearProgressIndicator(
                        progress = { entry.progressRatio },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
