package com.javrocket.avplay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.ui.home.XChinaWidePosterCard
import com.javrocket.avplay.ui.media.ImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelBrowseScreen(
    source: XChinaSource,
    modelId: String,
    title: String,
    imageLoader: ImageLoader,
    reloadTick: Int,
    onBack: () -> Unit,
    onOpen: (VideoBrief) -> Unit,
    onNeedChallenge: (String) -> Unit,
) {
  var items by remember { mutableStateOf<List<VideoBrief>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(modelId, reloadTick, retryKey) {
        items = null
        error = null
        source.modelVideos(modelId).fold(
            onSuccess = { page -> items = page.items },
            onFailure = { error = it },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "模特" }, maxLines = 1) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        when {
            error != null -> {
                val challenge = error as? AppError.CloudflareChallenge
                Box(
                    Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(error?.message ?: "加载失败")
                        if (challenge != null) {
                            OutlinedButton(onClick = { onNeedChallenge(challenge.url) }) {
                                Text("打开验证页")
                            }
                        }
                        OutlinedButton(onClick = { retryKey++ }) { Text("重试") }
                    }
                }
            }

            items == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            items!!.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无视频", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items!!, key = { it.id }) { item ->
                    XChinaWidePosterCard(
                        item = item,
                        imageLoader = imageLoader,
                        coverHeaders = emptyMap(),
                        onClick = { onOpen(item) },
                    )
                }
            }
        }
    }
}