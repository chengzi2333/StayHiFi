package com.chengzi.stayhifi.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus

/**
 * Root 状态卡（首次教程与设置页共用，UX 收敛后唯一实现）：
 * 一级只放一行人话状态 + 「请求 Root 授权」按钮 + 「查看详情」；
 * 可用性/授权/服务连接/最近错误（含 observedReason 原文）收进详情弹窗，
 * 不在卡片上直出诊断字段（事实与推测分列的口径不变，§11.6）。
 */
@Composable
fun RootStatusCard(
    root: RootSnapshot,
    busy: Boolean,
    onRequestAuthorization: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = root.status == RootStatus.READY
    var detailVisible by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ready) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Root 状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (ready) {
                    Text(
                        text = "已就绪",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = rootStatusLine(root),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!ready) {
                    Button(
                        onClick = onRequestAuthorization,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(if (busy) "正在验证…" else "请求 Root 授权")
                    }
                }
                TextButton(
                    onClick = { detailVisible = true },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("查看详情")
                }
            }
        }
    }

    if (detailVisible) {
        AlertDialog(
            onDismissRequest = { detailVisible = false },
            confirmButton = {
                TextButton(onClick = { detailVisible = false }) { Text("关闭") }
            },
            title = { Text("Root 状态详情") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RootFactRow("Root 可用性", rootAvailabilityText(root))
                    RootFactRow("授权状态", rootGrantText(root))
                    RootFactRow("服务连接", rootServiceText(root))
                    RootFactRow("最近错误", rootLastErrorText(root))
                }
            },
        )
    }
}

/** 卡片唯一一行状态（人话）；诊断明细只在详情弹窗。 */
internal fun rootStatusLine(root: RootSnapshot): String = when {
    root.status == RootStatus.READY -> "已连接"
    root.status == RootStatus.REQUESTING || root.status == RootStatus.BINDING -> "正在连接…"
    root.granted == false -> "未获得 Root 授权（可能被拒绝，或设备未 Root）"
    root.status == RootStatus.NEEDS_FOREGROUND_REVALIDATION -> "需要重新验证，点下方按钮授权"
    root.status == RootStatus.SHELL_FAILURE || root.status == RootStatus.AUTH_TIMEOUT ||
        root.status == RootStatus.BIND_NULL || root.status == RootStatus.BIND_TIMEOUT ||
        root.status == RootStatus.BIND_FAILED || root.status == RootStatus.DISCONNECTED ||
        root.status == RootStatus.NOT_GRANTED -> "未就绪，点「查看详情」了解原因"
    else -> "尚未验证，点下方按钮授权"
}

@Composable
private fun RootFactRow(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Root 可用性（事实）：是否拿到过 root shell；libsu 默认 shell 可能回落普通 sh（§11.4.1）。 */
private fun rootAvailabilityText(root: RootSnapshot): String = when (root.shellStatus) {
    RootShellStatus.ROOT_SHELL -> "可用（已获取 Root shell）"
    RootShellStatus.NON_ROOT_SHELL -> "不可用（当前 shell 非 Root）"
    RootShellStatus.UNKNOWN -> "尚未验证"
}

/** 授权状态（可观察三态，§11.4.2）：无法区分「用户拒绝/没有 su/管理器不兼容」，如实合并表述。 */
private fun rootGrantText(root: RootSnapshot): String = when (root.granted) {
    true -> "已授权"
    false -> "未获得 Root 权限（可能被拒绝，或设备没有可用 Root）"
    null -> "尚未验证"
}

private fun rootServiceText(root: RootSnapshot): String = when (root.status) {
    RootStatus.READY -> "已连接并通过健康检查"
    RootStatus.BINDING -> "连接中…"
    RootStatus.BIND_NULL, RootStatus.BIND_TIMEOUT, RootStatus.BIND_FAILED -> "连接失败"
    RootStatus.DISCONNECTED -> "已断开"
    else -> "未连接"
}

/** 最近错误：只写可观察事实；推测原因（reasonHint）按 §11.6 另列，不与事实混写。 */
private fun rootLastErrorText(root: RootSnapshot): String {
    val isError = when (root.status) {
        RootStatus.NOT_GRANTED, RootStatus.SHELL_FAILURE, RootStatus.AUTH_TIMEOUT,
        RootStatus.BIND_NULL, RootStatus.BIND_TIMEOUT, RootStatus.BIND_FAILED,
        RootStatus.DISCONNECTED, RootStatus.NEEDS_FOREGROUND_REVALIDATION,
        -> true
        else -> false
    }
    if (!isError) return "无"
    val fact = root.observedReason.ifBlank { root.status.name }
    return root.reasonHint?.let { "$fact\n推测原因：$it" } ?: fact
}
