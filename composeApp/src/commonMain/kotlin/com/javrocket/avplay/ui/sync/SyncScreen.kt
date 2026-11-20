package com.javrocket.avplay.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.sync.SyncService
import com.javrocket.avplay.sync.WebDavConfig
import kotlinx.coroutines.launch

/**
 * WebDAV 同步页。
 *
 * 只做三件事：填地址、测连接、手动双向同步。刻意不做"后台自动同步"——
 * 同步是覆盖远端文件的操作，让它悄悄在后台跑，出问题（比如两台设备的旧数据互相打架）
 * 时用户根本无从察觉。手动触发 = 每一步都有人背书。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(
    sync: SyncService,
    onBack: () -> Unit,
) {
    val config by sync.config.collectAsState()
    val lastResult by sync.lastResult.collectAsState()

    var url by remember(config) { mutableStateOf(config.url) }
    var user by remember(config) { mutableStateOf(config.username) }
    var pass by remember(config) { mutableStateOf(config.password) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist() = sync.saveConfig(WebDavConfig(url = url, username = user, password = pass))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebDAV 同步") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "填 WebDAV 目录地址即可（会自动追加文件名 avplay-backup.json）。" +
                    "支持 Nextcloud / ownCloud / 群晖 / 坚果云 等标准 WebDAV。",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.jianguoyun.com/dav/avplay") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("密码 / 应用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    persist()
                    busy = true
                    isError = false
                    message = "正在测试连接…"
                    scope.launch {
                        sync.testConnection().fold(
                            onSuccess = { message = it; isError = false },
                            onFailure = { message = it.message ?: "连接失败"; isError = true },
                        )
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存并测试连接") }

            Button(
                onClick = {
                    persist()
                    busy = true
                    isError = false
                    message = "正在同步…"
                    scope.launch {
                        sync.sync().fold(
                            onSuccess = { message = it.message; isError = false },
                            onFailure = { message = it.message ?: "同步失败"; isError = true },
                        )
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("立即同步（下载合并 → 上传）") }

            if (busy) CircularProgressIndicator()

            if (message.isNotBlank()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (isError) "失败" else "结果",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (lastResult.isNotBlank()) {
                Text("上次同步：$lastResult", style = MaterialTheme.typography.bodySmall)
            }

            Text(
                "说明：合并规则为「历史按最后观看时间取新、收藏取并集、站点域名本地优先」。\n" +
                    "换设备时在同一地址上点「立即同步」即可把对方的记录拉下来。\n" +
                    "密码只保存在本机，不会写进同步文件。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
