package com.javrocket.avplay.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.core.AppError
import com.javrocket.avplay.model.VideoBrief
import com.javrocket.avplay.service.xchina.XChinaSource
import com.javrocket.avplay.ui.media.ImageLoader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun XChinaSearchScreen(
    source: XChinaSource,
    imageLoader: ImageLoader,
    reloadTick: Int,
    onBack: () -> Unit,
    onOpen: (VideoBrief) -> Unit,
    onNeedChallenge: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<VideoBrief>?>(null) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    var retryKey by remember { mutableIntStateOf(0) }

    fun runSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        submittedQuery = q
    }

    LaunchedEffect(submittedQuery, reloadTick, retryKey) {
        val q = submittedQuery ?: return@LaunchedEffect
        items = null
        error = null
        source.search(q, page = 1).fold(
            onSuccess = { page -> items = page.items },
            onFailure = { error = it },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("如：麻豆") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                )
                Button(onClick = { runSearch() }) { Text("搜索") }
            }

            when {
                submittedQuery == null -> {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "输入关键词后搜索",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                error != null -> {
                    val challenge = error as? AppError.CloudflareChallenge
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(error?.message ?: "搜索失败")
                            if (challenge != null) {
                                OutlinedButton(onClick = { onNeedChallenge(challenge.url) }) {
                                    Text("打开验证页")
                                }
                            }
                            OutlinedButton(onClick = { retryKey++ }) { Text("重试") }
                        }
                    }
                }

                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                items!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
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
}