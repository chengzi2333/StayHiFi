package com.chengzi.stayhifi.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.chengzi.stayhifi.ui.nav.StayHiFiIcons
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import com.chengzi.stayhifi.util.DeviceNames

data class HomeUiState(
    /** 耳机名；null 表示当前未连接耳机。 */
    val headsetName: String? = null,
    /**
     * 编码器名；null 表示未知。
     * Task 44：优先为 dumpsys 段落头精确名（如 "LHDC V5"），读不到回落 Masks 映射名（如 "LHDC"）。
     */
    val codec: String? = null,
    /** 采样率（如 "192kHz"）；null 表示未知。 */
    val rate: String? = null,
    /** 位深（如 "24bit"）；null 表示未知。 */
    val bits: String? = null,
    /** 码率质量档（如 "1000kbps"/"自适应"）；null 表示未识别。 */
    val quality: String? = null,
    /**
     * Task 43/44：传输层实况码率（kbps，如 400），来自 dumpsys 的 "A2DP <codec> State:" 段。
     * 仅 vivo 栈实证可读；其他栈/未连接/未流式读不到 → null，实际传输卡显示「—」（诚实原则）。
     */
    val realtimeKbps: Int? = null,
    /** Task 44：传输层调度模式原文（如 "ABR"/"HIGH"）；null 表示读不到。 */
    val realtimeQualityMode: String? = null,
    /** 是否已达本机最高音质输出（Task 27 两态：与 ApplyWorker 同一份 deriveTarget 判定）。 */
    val fullBars: Boolean = false,
    /** 手动拉满进行中（Task 31）：按钮进入处理态，ApplyWorker 完成/失败后回弹。 */
    val applying: Boolean = false,
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
                // top=32dp：状态栏下方呼吸空间（对照 Stellar 首页）；bottom 维持 16dp。
                .padding(horizontal = 20.dp)
                .padding(top = 32.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 品牌大字（方案 A：纯文字，无图标/副标），深浅色随 onSurface 自动适配。
            Text(
                text = "StayHiFi",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            state.carrierAlert?.let { alert ->
                CarrierAlertBanner(text = alert, onClick = { onAction(HomeAction.FixCarrier) })
            }

            StatusCard(state)

            // Task 44：实际传输独立成卡（用户拍板），与上方「设置参数」明确区隔；
            // 未连接耳机时整卡隐藏，保持首屏简洁。
            if (state.headsetName != null) {
                RealtimeCard(state)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动拉满", style = MaterialTheme.typography.titleMedium)
                    Text(
                        // Task 42：澄清这是总开关（管所有设备），与设备页的单设备开关区分开。
                        text = "总开关：关闭后所有设备连上都不再自动拉满",
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
                enabled = state.headsetName != null && !state.applying,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) {
                if (state.applying) {
                    // Task 31 加载态：处理中禁用按钮，小号转圈 + 文案，完成信号回弹由 MainActivity 给。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Text(
                            text = "拉满中…",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                } else {
                    Text(
                        // 两态文案（Task 27）：无对比行、无"最高/最强参数"字样；"满血"仅徽标保留。
                        text = if (state.fullBars) "重新拉满" else "立即拉满",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
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
private fun StatusCard(state: HomeUiState) {
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
            // 机型行（Task 28）：面客机型名 + Android 版（SystemProperties 市场名键链）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = StayHiFiIcons.Smartphone,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = DeviceNames.marketModelWithAndroid(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusLine(state)
            DetailGrid(state)
        }
    }
}

/** 状态卡唯一的状态文案：两态（Task 27）——真话文案，明细参数看下方 2×3 明细格。 */
@Composable
private fun StatusLine(state: HomeUiState) {
    val text = when {
        state.headsetName == null -> "等待耳机连接"
        state.fullBars -> "已达本机最高音质输出"
        else -> "未拉满 · 点击重试"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
    )
}

/**
 * 明细格：编码器 / 采样率 / 位深 / 码率（2×2）。
 * Task 44：「实际传输」移出本格独立成卡（见 RealtimeCard），本格回到 2×2。
 */
@Composable
private fun DetailGrid(state: HomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailRow("编码器" to state.codec, "采样率" to state.rate)
        DetailRow("位深" to state.bits, "码率" to state.quality)
    }
}

/**
 * Task 44：实际传输独立卡（用户中途拍板，替代原「第六格/全宽长条」方案）。
 * 结构：标题行「实际传输」+ 大字值（如 400 kbps）；有数据时调度状态行
 * （ABR 必须带「自适应」三字——用户要求，别人不认识 ABR 缩写）；
 * 解释行说明「为什么这么设置」。读不到数据时值显示「—」，解释行换成诚实说明
 * （目前仅 vivo 机型实测可读）。用默认卡面色，与上方 primaryContainer 状态卡区隔。
 */
@Composable
private fun RealtimeCard(state: HomeUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "实际传输",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = state.realtimeKbps?.let { "$it kbps" } ?: "—",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            val qm = state.realtimeQualityMode
            if (state.realtimeKbps != null && qm != null) {
                Text(
                    text = if (qm.contains("ABR", ignoreCase = true)) {
                        "自适应调度（ABR）中"
                    } else {
                        "调度模式：$qm"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = if (state.realtimeKbps != null) {
                    // 用户要求的解释行：说清楚为什么这么设置
                    "纸面参数已拉满；实际码率由系统蓝牙栈按信号环境实时调度"
                } else {
                    "本机栈不暴露实时传输数据（目前仅 vivo 机型实测可读）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DetailRow(left: Pair<String, String?>, right: Pair<String, String?>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DetailCell(label = left.first, value = left.second, modifier = Modifier.weight(1f))
        DetailCell(label = right.first, value = right.second, modifier = Modifier.weight(1f))
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
                codec = "LHDC V5",
                rate = "192kHz",
                bits = "24bit",
                quality = "1000kbps",
                realtimeKbps = 400,
                realtimeQualityMode = "ABR",
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
                fullBars = false,
                autoApplyEnabled = false,
                carrierAlert = "Shizuku 未运行，点我修复",
            ),
            onAction = {},
        )
    }
}
