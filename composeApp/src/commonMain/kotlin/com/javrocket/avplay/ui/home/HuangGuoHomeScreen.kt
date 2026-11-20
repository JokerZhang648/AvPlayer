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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.huangguo.HuangGuoCategory
import com.javrocket.avplay.service.huangguo.HuangGuoImageCrypto
import com.javrocket.avplay.service.huangguo.HuangGuoSource
import com.javrocket.avplay.ui.media.ImageLoader
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 黄果首页 —— 双列封面网格 + 顶部分类 Tab，对齐原 App「黄果短剧」板块样式。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HuangGuoHomeScreen(
    source: HuangGuoSource,
    imageLoader: ImageLoader,
    reloadTick: Int,
    onOpen: (VideoBrief) -> Unit,
    onNeedChallenge: (String) -> Unit,
    onOpenSync: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
  var selectedTab by remember { mutableStateOf(HuangGuoCategory.AI_DUANJU) }
  var items by remember { mutableStateOf<List<VideoBrief>>(emptyList()) }
  var loading by remember { mutableStateOf(true) }
  var loadingMore by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<Throwable?>(null) }
  var page by remember { mutableIntStateOf(1) }
  var hasMore by remember { mutableStateOf(true) }
  var retryKey by remember { mutableIntStateOf(0) }

  val coverHeaders = remember(source.baseUrl) { HuangGuoImageCrypto.imageRequestHeaders(source.baseUrl) }

  LaunchedEffect(selectedTab, reloadTick, retryKey) {
    loadingMore = false
    loading = true
    error = null
    page = 1
    hasMore = !selectedTab.isFeaturedTab
    items = emptyList()
    try {
      when {
        selectedTab.isFeaturedTab -> {
          source.service.getFeaturedList().fold(
            onSuccess = { list ->
              items = list
              hasMore = false
            },
            onFailure = { error = it },
          )
        }
        selectedTab.isRankingTab -> {
          source.service.getRanking(selectedTab.slug, page = 1).fold(
            onSuccess = { p ->
              items = p.items
              hasMore = p.hasMore
            },
            onFailure = { error = it },
          )
        }
        else -> {
          source.service.getCategoryVideos(selectedTab.slug, page = 1).fold(
            onSuccess = { p ->
              items = p.items
              hasMore = p.hasMore
            },
            onFailure = { error = it },
          )
        }
      }
    } finally {
      loading = false
    }
  }

  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text("AvPlay") },
        actions = {
          TextButton(onClick = onOpenSync) { Text("同步") }
          TextButton(onClick = onOpenDiagnostics) { Text("诊断") }
        },
      )
    },
  ) { padding ->
    Column(Modifier.fillMaxSize().padding(padding)) {
      HuangGuoCategoryTabs(
        selected = selectedTab,
        onSelect = { selectedTab = it },
      )

      when {
        loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          CircularProgressIndicator()
        }

        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
          val e = error!!
          val challenge = e as? AppError.CloudflareChallenge
          Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(e.message ?: "加载失败", style = MaterialTheme.typography.bodyMedium)
            if (challenge != null) {
              TextButton(onClick = { onNeedChallenge(challenge.url) }) { Text("打开验证页") }
            }
            TextButton(onClick = { retryKey++ }) { Text("重试") }
          }
        }

        else -> {
          // 每个分类独立 gridState，切换 Tab 自然从顶部开始，且避免对未挂载列表 scrollToItem 卡死
          key(selectedTab.slug) {
            val gridState = rememberLazyGridState()
            LaunchedEffect(gridState) {
              snapshotFlow {
                val info = gridState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                Triple(last, items.size, gridState.canScrollForward)
              }
                .distinctUntilChanged()
                .collect { (lastVisible, dataCount, canScrollForward) ->
                  if (selectedTab.isFeaturedTab || loadingMore || !hasMore || dataCount == 0) return@collect
                  val nearEnd = lastVisible >= dataCount - 3
                  val needFillViewport = !canScrollForward
                  if (!nearEnd && !needFillViewport) return@collect

                  loadingMore = true
                  val next = page + 1
                  val pageResult = if (selectedTab.isRankingTab) {
                    source.service.getRanking(selectedTab.slug, page = next)
                  } else {
                    source.service.getCategoryVideos(selectedTab.slug, page = next)
                  }
                  pageResult.fold(
                    onSuccess = { p ->
                      if (p.items.isNotEmpty()) {
                        items = items + p.items
                        page = next
                        hasMore = p.hasMore && p.items.isNotEmpty()
                      } else {
                        hasMore = false
                      }
                    },
                    onFailure = {
                      hasMore = false
                    },
                  )
                  loadingMore = false
                }
            }
            LazyVerticalGrid(
              columns = GridCells.Fixed(2),
              state = gridState,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.fillMaxSize(),
            ) {
              itemsIndexed(items, key = { idx, it -> "${selectedTab.slug}-${idx}-${it.id}" }) { index, item ->
                VideoPosterGridCard(
                  item = item,
                  imageLoader = imageLoader,
                  coverHeaders = coverHeaders,
                  rank = if (selectedTab.isRankingTab) index + 1 else null,
                  onClick = { onOpen(item) },
                )
              }
              if (loadingMore) {
                item(span = { GridItemSpan(2) }) {
                  Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
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

@Composable
private fun HuangGuoCategoryTabs(
  selected: HuangGuoCategory,
  onSelect: (HuangGuoCategory) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(horizontal = 8.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    HuangGuoCategory.entries.forEach { tab ->
      val active = tab == selected
      Text(
        text = tab.label,
        modifier = Modifier
          .clip(RoundedCornerShape(8.dp))
          .clickable { onSelect(tab) }
          .padding(horizontal = 10.dp, vertical = 6.dp),
        color = if (active) Color(0xFFE91E63) else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontSize = 15.sp,
      )
    }
  }
}
