package com.chengzi.stayhifi.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.codec.DeviceProfile
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme

/** 设备页单条档案的 UI 模型。 */
data class DeviceItemUi(
    val mac: String,
    val name: String,
    /** 参数摘要，形如 "LHDC·192kHz·24bit"。 */
    val configSummary: String,
    val source: DeviceProfile.Source,
    /** 单设备自动应用开关。 */
    val autoApply: Boolean,
)

data class DevicesUiState(
    val devices: List<DeviceItemUi> = emptyList(),
)

/** 设备页全部动作上抛，本层无逻辑。 */
sealed interface DevicesAction {
    data class SetAutoApply(val mac: String, val enabled: Boolean) : DevicesAction
    /** 「重录」：复用 RecordWizard 流程重新抓取这台设备的参数。 */
    data class ReRecord(val mac: String) : DevicesAction
}

/**
 * 设备档案页：名称 / MAC / 参数摘要 / 来源徽章 / 单设备 autoApply 开关 / 重录按钮。
 */
@Composable
fun DevicesScreen(
    state: DevicesUiState,
    onAction: (DevicesAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "设备档案",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "连上已知设备时按档案参数自动拉满",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.padding(8.dp))
            if (state.devices.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "还没有设备档案",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "连接耳机后通过 Onboarding 或录制向导创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.devices, key = { it.mac }) { item ->
                        DeviceCard(item, onAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(item: DeviceItemUi, onAction: (DevicesAction) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                SourceBadge(item.source)
            }
            Text(
                text = item.mac,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = item.configSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "自动应用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "关闭后这台设备不再自动写入",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = item.autoApply,
                    onCheckedChange = { onAction(DevicesAction.SetAutoApply(item.mac, it)) },
                )
                TextButton(
                    onClick = { onAction(DevicesAction.ReRecord(item.mac)) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("重录")
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(source: DeviceProfile.Source) {
    val (label, container, content) = when (source) {
        DeviceProfile.Source.CAPTURED ->
            Triple("录制", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        DeviceProfile.Source.PRESET ->
            Triple("预设", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
        DeviceProfile.Source.AUTO ->
            Triple("手选", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = content,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Preview(showBackground = true, name = "设备页-浅色")
@Composable
private fun DevicesScreenPreviewLight() {
    StayHiFiTheme(dynamicColor = false) {
        DevicesScreen(
            state = DevicesUiState(
                listOf(
                    DeviceItemUi(
                        mac = "38:F8:89:01:23:45",
                        name = "OPPO Enco X3",
                        configSummary = "LHDC·192kHz·24bit",
                        source = DeviceProfile.Source.PRESET,
                        autoApply = true,
                    ),
                    DeviceItemUi(
                        mac = "AA:BB:CC:DD:EE:FF",
                        name = "Sony WH-1000XM5",
                        configSummary = "LDAC·96kHz·24bit",
                        source = DeviceProfile.Source.CAPTURED,
                        autoApply = false,
                    ),
                ),
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "设备页-深色-空")
@Composable
private fun DevicesScreenPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        DevicesScreen(state = DevicesUiState(), onAction = {})
    }
}
