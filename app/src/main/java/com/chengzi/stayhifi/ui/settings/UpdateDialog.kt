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
import androidx.compose.runtime.remember
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
 *
 * Task 39：结构重组（参照 FlClash checkUpdateResultHandle/_releaseSpan）——
 * 标题只留「发现新版本」，版本号大字突出；更新说明单独成可滚动块；
 * 进度区钉在滚动块下方、不随滚动，任何时候可见。
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
        // Task 39：版本号移出标题，正文里用大字样式单独突出
        title = { Text("发现新版本") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Task 39：版本号大字突出（FlClash 用 title2 级别，这里用 titleMedium）
                Text(
                    text = state.tag,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (state.notes.isNotBlank()) {
                    // Task 40：更新说明走轻量 Markdown 渲染（粗体/标题/链接/行内代码），
                    // 不再原样贴 raw Markdown（`**粗体**` 曾显示成字面星号）
                    val notes = remember(state.notes) { renderMarkdownLite(state.notes) }
                    // Task 39：更新说明单独成可滚动块，超长说明不再把进度区顶到折叠线以下
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                // Task 39：进度区钉在滚动块下方、不随滚动；错误文案与进度互斥共用此位
                when {
                    state.error != null -> Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.downloading -> {
                        val percent = state.progressPercent
                        when {
                            // Task 39：点「立即更新」后到第一个字节到达前的静默期，
                            // 立即给「准备下载…」反馈（MainActivity 置 downloading 时进度归零即落入此分支）
                            percent == null && state.downloadedMb <= 0f -> {
                                LinearProgressIndicator()
                                Text(
                                    text = "准备下载…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            percent != null -> {
                                LinearProgressIndicator(progress = { percent / 100f })
                                Text(
                                    text = "下载中…$percent%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            else -> {
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
