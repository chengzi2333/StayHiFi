package com.chengzi.stayhifi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.BuildConfig
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme

data class SettingsUiState(
    /** 拉满结果通知。 */
    val notifyResult: Boolean = false,
    /** 常驻通知显示参数详情（事件驱动 + 30s 轮询）。 */
    val notifyPersistent: Boolean = false,
    /** QS 磁贴可用。 */
    val qsTile: Boolean = false,
    /** 电池白名单状态（展示用）。 */
    val batteryWhitelisted: Boolean = false,
)

/** 设置页全部动作上抛，本层无逻辑。 */
sealed interface SettingsAction {
    data class SetNotifyResult(val enabled: Boolean) : SettingsAction
    data class SetNotifyPersistent(val enabled: Boolean) : SettingsAction
    data class SetQsTile(val enabled: Boolean) : SettingsAction
    data object OpenAutostart : SettingsAction
    data object OpenBatteryWhitelist : SettingsAction
    data object OpenBackgroundPower : SettingsAction
    data object OpenLogs : SettingsAction
    /** 浏览器跳转 GitHub Releases 检查更新。 */
    data object CheckUpdate : SettingsAction
}

/**
 * 设置页：三个通知/磁贴开关 + 保活引导 + 运行日志入口 + 关于。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            SectionTitle("通知")
            SwitchRow(
                title = "拉满结果通知",
                subtitle = "写入成功 / 失败时弹一条通知",
                checked = state.notifyResult,
                onChecked = { onAction(SettingsAction.SetNotifyResult(it)) },
            )
            SwitchRow(
                title = "常驻通知显示参数详情",
                subtitle = "常驻通知正文实时显示当前音质参数（轻微耗电）",
                checked = state.notifyPersistent,
                onChecked = { onAction(SettingsAction.SetNotifyPersistent(it)) },
            )

            SectionTitle("快捷方式")
            SwitchRow(
                title = "QS 磁贴",
                subtitle = "下拉快捷面板一键拉满",
                checked = state.qsTile,
                onChecked = { onAction(SettingsAction.SetQsTile(it)) },
            )

            SectionTitle("后台保活")
            Card(
                onClick = { onAction(SettingsAction.OpenAutostart) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "自启动权限",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "去开启",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Card(
                onClick = { onAction(SettingsAction.OpenBatteryWhitelist) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "电池白名单",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = if (state.batteryWhitelisted) "已加入" else "去加入",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (state.batteryWhitelisted) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }

            Card(
                onClick = { onAction(SettingsAction.OpenBackgroundPower) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "后台高耗电",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "允许后台高耗电；并在最近任务里锁定卡片防划卡清理",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "去设置",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            SectionTitle("诊断")
            OutlinedButton(
                onClick = { onAction(SettingsAction.OpenLogs) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("运行日志")
            }

            SectionTitle("关于")
            Card(
                onClick = { onAction(SettingsAction.CheckUpdate) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "检查更新",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = "当前 v${BuildConfig.VERSION_NAME} · 前往 GitHub Releases",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "去查看",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "StayHiFi 音质常驻",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "GPL-3.0 开源软件。与 Shizuku、Stellar、OPPO、vivo 无任何隶属关系；" +
                            "所有数据仅保存在本机，零数据采集、零网络上传。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Preview(showBackground = true, name = "设置页-浅色")
@Composable
private fun SettingsScreenPreviewLight() {
    StayHiFiTheme(dynamicColor = false) {
        SettingsScreen(
            state = SettingsUiState(
                notifyResult = true,
                notifyPersistent = false,
                qsTile = true,
                batteryWhitelisted = true,
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "设置页-深色")
@Composable
private fun SettingsScreenPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        SettingsScreen(state = SettingsUiState(), onAction = {})
    }
}
