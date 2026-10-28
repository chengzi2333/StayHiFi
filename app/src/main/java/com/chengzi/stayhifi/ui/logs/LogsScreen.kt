package com.chengzi.stayhifi.ui.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme

data class LogsUiState(
    /** 已按时间倒序的日志行。 */
    val lines: List<String> = emptyList(),
)

/** 日志页动作：上抛，本层无逻辑。 */
sealed interface LogsAction {
    data object Copy : LogsAction
    data object CopyFeedback : LogsAction
    data object Clear : LogsAction
    data object Back : LogsAction
}

/**
 * 运行日志页：[com.chengzi.stayhifi.LogStore] 快照倒序展示 + 复制 + 清空。
 * 顶部提示「日志仅保存在本机内存」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    state: LogsUiState,
    onAction: (LogsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("运行日志") },
                navigationIcon = {
                    IconButton(onClick = { onAction(LogsAction.Back) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Text(
                    text = "日志仅保存在本机内存，应用退出后清空，不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(state.lines) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Task B7：三个动作（复制日志/复制反馈日志/清空）放可横向滑动的 Row，
            // 小屏宁可滑动也不裁掉动作。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${state.lines.size} 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { onAction(LogsAction.Copy) },
                    enabled = state.lines.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("复制日志")
                }
                TextButton(
                    onClick = { onAction(LogsAction.CopyFeedback) },
                    enabled = state.lines.isNotEmpty(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("复制反馈日志")
                }
                TextButton(
                    onClick = { onAction(LogsAction.Clear) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("清空")
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "日志页-浅色")
@Composable
private fun LogsScreenPreviewLight() {
    StayHiFiTheme(dynamicColor = false) {
        LogsScreen(
            state = LogsUiState(
                listOf(
                    "[14:02:11] 已保存录制档案: OPPO Enco X3 (38:F8:89:01:23:45) LHDC·192kHz·24bit",
                    "[14:01:58] 读回一致 ✓ final=LHDC·192kHz·24bit",
                    "[14:01:57] 写入候选: LHDC·192kHz·24bit",
                    "[14:01:55] 设备已连接: OPPO Enco X3 (38:F8:89:01:23:45)",
                ),
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "日志页-深色-空")
@Composable
private fun LogsScreenPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        LogsScreen(state = LogsUiState(), onAction = {})
    }
}
