package com.chengzi.stayhifi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme

data class HomeUiState(
    /** 耳机名；null 表示当前未连接耳机。 */
    val headsetName: String? = null,
    /** 编码器名（如 "LHDC"）；null 表示未知。 */
    val codec: String? = null,
    /** 采样率（如 "192kHz"）；null 表示未知。 */
    val rate: String? = null,
    /** 位深（如 "24bit"）；null 表示未知。 */
    val bits: String? = null,
    /** 码率质量档（如 "1000kbps"/"自适应"）；null 表示未识别。 */
    val quality: String? = null,
    /** 是否已有该设备的参数档案（false 时提示去录制模板）。 */
    val hasProfile: Boolean = false,
    /** 是否已拉到已知最强参数（满血）。 */
    val fullBars: Boolean = false,
    /** 「自动拉满」总开关。 */
    val autoApplyEnabled: Boolean = true,
    /** 载体异常警示文案；null 表示一切正常。 */
    val carrierAlert: String? = null,
)

/** 首页全部动作上抛，本层无逻辑。 */
sealed interface HomeAction {
    data class SetAutoApply(val enabled: Boolean) : HomeAction
    data object ApplyNow : HomeAction
    /** 点击红色警示横幅去修复载体。 */
    data object FixCarrier : HomeAction
    /** 点击「码率档位未锁定」提示行跳设备页录制模板。 */
    data object GoRecord : HomeAction
}

/**
 * 首页：顶部状态大卡 + 自动拉满开关 + 下半屏主操作按钮。
 * 载体异常时顶部出现红色警示横幅。
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onAction: (HomeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.carrierAlert?.let { alert ->
                CarrierAlertBanner(text = alert, onClick = { onAction(HomeAction.FixCarrier) })
            }

            StatusCard(state, onGoRecord = { onAction(HomeAction.GoRecord) })

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动拉满", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "连上耳机时自动应用最强参数",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.autoApplyEnabled,
                    onCheckedChange = { onAction(HomeAction.SetAutoApply(it)) },
                )
            }

            Button(
                onClick = { onAction(HomeAction.ApplyNow) },
                enabled = state.headsetName != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                Text(
                    text = if (state.fullBars) "已是满血，再拉一次" else "立即满血",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun CarrierAlertBanner(text: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "点我修复",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StatusCard(state: HomeUiState, onGoRecord: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.headsetName ?: "未连接耳机",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                StayHiFiBadge(fullBars = state.fullBars)
            }
            StatusLine(state)
            DetailGrid(state)
            if (state.headsetName != null && !state.hasProfile) {
                NoProfileHint(onClick = onGoRecord)
            }
        }
    }
}

/** 状态卡唯一的状态文案：明细参数请看下方 2×2 明细格。 */
@Composable
private fun StatusLine(state: HomeUiState) {
    val text = when {
        state.headsetName == null -> "等待耳机连接"
        state.fullBars -> "已达最强参数"
        else -> "当前参数未拉满"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/** 2×2 明细格：编码器 / 采样率 / 位深 / 码率，各格 label + 值（未知显示 "—"）。 */
@Composable
private fun DetailGrid(state: HomeUiState) {
    val cells = listOf(
        "编码器" to state.codec,
        "采样率" to state.rate,
        "位深" to state.bits,
        "码率" to state.quality,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { (label, value) ->
                    DetailCell(label = label, value = value, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DetailCell(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 未锁定码率提示：无档案时引导去设备页录制模板。 */
@Composable
private fun NoProfileHint(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "码率档位未锁定，去设备页录制模板可拉满",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "去设备页",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun StayHiFiBadge(fullBars: Boolean) {
    val container = if (fullBars) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (fullBars) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(content),
        )
        Text(
            text = if (fullBars) "满血" else "未满血",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = content,
        )
    }
}

@Preview(showBackground = true, name = "首页-浅色-满血")
@Composable
private fun HomeScreenPreviewLight() {
    StayHiFiTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                headsetName = "OPPO Enco X3",
                codec = "LHDC",
                rate = "192kHz",
                bits = "24bit",
                quality = "1000kbps",
                hasProfile = true,
                fullBars = true,
                autoApplyEnabled = true,
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "首页-深色-异常")
@Composable
private fun HomeScreenPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                headsetName = "OPPO Enco X3",
                codec = "SBC",
                rate = "44.1kHz",
                bits = "16bit",
                quality = null,
                hasProfile = false,
                fullBars = false,
                autoApplyEnabled = false,
                carrierAlert = "Shizuku 未运行，点我修复",
            ),
            onAction = {},
        )
    }
}
