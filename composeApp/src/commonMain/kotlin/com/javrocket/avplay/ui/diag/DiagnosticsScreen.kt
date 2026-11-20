package com.javrocket.avplay.ui.diag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.javrocket.avplay.diag.DiagLayer
import com.javrocket.avplay.diag.DiagnosticsReport
import com.javrocket.avplay.diag.DiagnosticsService
import com.javrocket.avplay.diag.SiteDiagnostic
import kotlinx.coroutines.launch

/**
 * 诊断页：一键跑完所有站点，按"故障层"给出结论，并可导出报告文件。
 *
 * 设计原则：**结论先行**。用户打开这一页想知道的是"现在到底哪儿坏了、我该做什么"，
 * 所以顶部是一句人话总结 + 分层统计，下面才是逐站明细。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    diagnostics: DiagnosticsService,
    onBack: () -> Unit,
) {
    var report by remember { mutableStateOf<DiagnosticsReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var exportedPath by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun runTest() {
        running = true
        exportedPath = null
        scope.launch {
            report = diagnostics.run()
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("站点诊断") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { runTest() }, enabled = !running) {
                    Text(if (report == null) "开始诊断" else "重新诊断")
                }
                OutlinedButton(
                    onClick = { exportedPath = report?.let { diagnostics.export(it) } },
                    enabled = report != null && !running,
                ) { Text("导出报告") }
            }

            if (running) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text("正在逐个站点跑真实首页请求（约 10~40 秒）…")
                }
            }

            exportedPath?.let {
                Text("报告已导出：$it", style = MaterialTheme.typography.bodySmall)
            }

            val r = report
            if (r != null && !running) {
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(r.summary(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "总耗时 ${r.totalMs}ms · ${r.platform} · 可用 ${r.okCount}/${r.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val layers = r.items.map { it.layer }.distinct().filter { it != DiagLayer.OK }
                    if (layers.isNotEmpty()) {
                        Text(
                            "问题集中在：" + layers.joinToString("、") { it.label },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(r.items) { item -> DiagnosticRow(item) }
                }
            } else if (r == null && !running) {
                Text(
                    "诊断会真实请求每个站点的首页，据此判断问题出在网络、反爬、接口还是解析层。" +
                        "比只 ping 域名更有用 —— 能连上但用不了的情况，只有跑真实请求才看得出来。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DiagnosticRow(item: SiteDiagnostic) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    item.layer.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier
                        .background(layerColor(item.layer), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(
                "${item.elapsedMs}ms · 条目 ${item.itemCount} · ${item.baseUrl}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (item.detail.isNotBlank()) {
                Text(item.detail, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "建议：" + item.layer.advice,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun layerColor(layer: DiagLayer): Color = when (layer) {
    DiagLayer.OK -> Color(0xFF2E7D32)
    DiagLayer.ANTI_BOT -> Color(0xFFEF6C00)
    DiagLayer.NETWORK -> Color(0xFF1565C0)
    DiagLayer.PROTOCOL -> Color(0xFF6A1B9A)
    DiagLayer.PARSE -> Color(0xFFC62828)
    DiagLayer.UNKNOWN -> Color(0xFF546E7A)
}
