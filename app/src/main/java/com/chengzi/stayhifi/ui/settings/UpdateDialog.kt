package com.chengzi.stayhifi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Task 38：更新弹窗 UI 状态。
 *
 * @param downloading 下载中（按钮禁用、显示进度）
 * @param progressPercent 0-100；Content-Length 缺失时为 null（显示已下载 MB）
 * @param downloadedMb 已下载量（MB，无总长度时展示用）
 * @param error 下载/拉起安装失败文案，非 null 时按钮变「重试」
 */
data class UpdateDialogUi(
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val downloading: Boolean = false,
    val progressPercent: Int? = null,
    val downloadedMb: Float = 0f,
    val error: String? = null,
)

/**
 * Task 38：更新弹窗——版本号 + 更新说明 + 下载进度 + 「立即更新/重试」「取消」。
 * 手动检查与自动检查共用同一个弹窗（MainActivity 根组合承载，不依附设置页）。
 */
@Composable
fun UpdateDialog(
    state: UpdateDialogUi,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        // 下载中屏蔽点击外部关闭，避免下载中弹窗消失但任务还在跑
        onDismissRequest = { if (!state.downloading) onDismiss() },
        title = { Text("发现新版本 ${state.tag}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.notes.isNotBlank()) {
                    Text(
                        text = state.notes,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when {
                    state.error != null -> Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.downloading -> {
                        val percent = state.progressPercent
                        if (percent != null) {
                            LinearProgressIndicator(progress = { percent / 100f })
                            Text(
                                text = "下载中…$percent%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            // 拿不到 Content-Length：显示已下载 MB
                            LinearProgressIndicator()
                            Text(
                                text = "下载中…已下载 " +
                                    String.format(Locale.getDefault(), "%.1f", state.downloadedMb) +
                                    " MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !state.downloading,
            ) {
                Text(if (state.error != null) "重试" else "立即更新")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
