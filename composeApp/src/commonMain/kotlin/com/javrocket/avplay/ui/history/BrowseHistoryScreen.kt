package com.javrocket.avplay.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.SiteIds
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.ContentSource
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.HuangGuoService
import com.javrocket.avplay.store.HistoryEntry
import com.javrocket.avplay.store.LibraryStore
import com.javrocket.avplay.ui.home.ContinueRow
import com.javrocket.avplay.ui.media.ImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseHistoryScreen(
    library: LibraryStore,
    imageLoader: ImageLoader,
    sources: List<ContentSource>,
    onBack: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Unit,
) {
    val history by library.history.collectAsState()
    val items = remember(history) { history.sortedByDescending { it.updatedAt } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("历史浏览") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无观看记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items, key = { "${it.siteId}-${it.videoId}-${it.updatedAt}" }) { entry ->
                    val headers = remember(entry.siteId, sources) {
                        coverHeadersFor(entry.siteId, sources)
                    }
                    ContinueRow(
                        entry = entry,
                        imageLoader = imageLoader,
                        coverHeaders = headers,
                        siteLabel = siteDisplayName(entry.siteId),
                        onClick = { onOpenEntry(entry) },
                    )
                }
            }
        }
    }
}

private fun siteDisplayName(siteId: String): String = when (siteId) {
    SiteIds.HUANGGUO -> "黄果短剧"
    SiteIds.XCHINA -> "XChina"
    SiteIds.JAVLIBRARY -> "JavLibrary"
    SiteIds.JAVDB -> "JavDB"
    SiteIds.AVDB -> "AVDB"
    SiteIds.AV01 -> "AV01"
    else -> siteId
}

private fun coverHeadersFor(siteId: String, sources: List<ContentSource>): Map<String, String> {
    if (siteId != HuangGuoService.SITE_ID) return emptyMap()
    val base = sources.firstOrNull { it.id == siteId }?.baseUrl ?: return emptyMap()
    return HuangGuoImageCrypto.imageRequestHeaders(base)
}

fun historyEntryToBrief(entry: HistoryEntry): VideoBrief =
    VideoBrief(
        id = entry.videoId,
        title = entry.title,
        cover = entry.cover,
        sourceSite = entry.siteId,
    )