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
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.model.VideoSection
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.HuangGuoService
import com.javrocket.avplay.service.huangguo.HuangGuoSource
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
    homeSources: List<ContentSource> = emptyList(),
    onSelectSource: ((String) -> Unit)? = null,
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
        )
        return
    }

    var state by remember { mutableStateOf<HomeUiState>(HomeUiState.Loading) }
    var retryKey by remember { mutableIntStateOf(0) }
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
                    val continueList = library.continueWatching(siteId = source.id)
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        // 续播入口放在最上面：用户回到 App 最想做的事就是"接着看"
                        if (continueList.isNotEmpty()) {
                            item(key = "header-continue") {
                                Text(
                                    text = "继续观看",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                            items(items = continueList, key = { "cont-${it.siteId}-${it.videoId}" }) { entry ->
                                ContinueRow(
                                    entry = entry,
                                    imageLoader = imageLoader,
                                    coverHeaders = coverHeaders,
                                    onClick = {
                                        onOpen(
                                            VideoBrief(
                                                id = entry.videoId,
                                                title = entry.title,
                                                cover = entry.cover,
                                                sourceSite = entry.siteId,
                                            ),
                                        )
                                    },
                                )
                            }
                        }

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

/** 继续观看行：带进度条和"看到哪"的时间，比普通列表多一条进度信息。 */
@Composable
private fun ContinueRow(
    entry: com.javrocket.avplay.store.HistoryEntry,
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
            RemoteThumb(loader = imageLoader, url = entry.cover, headers = coverHeaders)
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title.ifBlank { entry.videoId },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val posSec = entry.positionMs / 1000
                val durSec = entry.durationMs / 1000
                Text(
                    text = "看到 %02d:%02d%s".format(
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
