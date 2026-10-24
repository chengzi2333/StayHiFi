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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
     * Task 44：为 dumpsys 段落头精确名或 Masks 扩充映射名（均为 "LHDC V5" 级别精度）。
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
     * 仅 vivo 栈实证可读；其他栈/未连接/未流式读不到 → null。
     * Task 46：该值在 vivo LHDC 下是粘性值（停播仍报 400000），是否真在播放以
     * realtimeNotStreaming 为准（AudioManager.isMusicActive 即时判定，见 MainActivity）。
     */
    val realtimeKbps: Int? = null,
    /** Task 44：传输层调度模式原文（如 "ABR"/"HIGH"）；null 表示读不到。 */
    val realtimeQualityMode: String? = null,
    /**
     * Task 44/46：判定为未在流式传输 → 实际传输行显示「未在播放」。
     * 两条路径：AudioManager.isMusicActive=false（Task 46，即时主判定）；
     * 或栈明确回报 bitrate ≤ 0（LDAC 实证 -1，保留兼容）。
     * 与「本机暂不支持抓取」（realtimeKbps=null 且本字段 false → 「—」）区分，空态三分。
     */
    val realtimeNotStreaming: Boolean = false,
    /** 是否已达本机最高音质输出（Task 27 两态：与 ApplyWorker 同一份 deriveTarget 判定）。 */
    val fullBars: Boolean = false,
    /** 手动拉满进行中（Task 31）：按钮进入处理态，ApplyWorker 完成/失败后回弹。 */
    val applying: Boolean = false,
    /** 载体异常警示文案；null 表示一切正常。 */
    val carrierAlert: String? = null,
)

/** 首页全部动作上抛，本层无逻辑。 */
sealed interface HomeAction {
    data object ApplyNow : HomeAction
    /** 点击红色警示横幅去修复载体。 */
    data object FixCarrier : HomeAction
}

/**
 * 首页：顶部状态大卡 + 下半屏手动拉满按钮；自动拉满总开关在设置页。
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

            // Task 45：中间内容区独占剩余高度并可滚动——加卡片后内容超高，
            // 底部「重新拉满」按钮曾被挤出可视区只剩顶边（用户截图实证）。
            // 改为「按钮固定底部、内容区滚动」，任何内容高度/小屏/大字体下按钮完整可达。
            // （滚动 Column 不允许 weight 子项，故不能把根 Column 直接改 verticalScroll。）
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.carrierAlert?.let { alert ->
                    CarrierAlertBanner(text = alert, onClick = { onAction(HomeAction.FixCarrier) })
                }

                // Task 45 布局：实际传输并入状态卡（方案 C，用户拍板），独立 RealtimeCard 撤销。
                StatusCard(state)
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
            // Task 45：实际传输并入状态卡（方案 C，用户拍板）——分隔线以上全是「设置层」，
            // 以下一行是「传输层」；未连接耳机时该段整段不显示。
            if (state.headsetName != null) {
                RealtimeSection(state)
            }
        }
    }
}

/** 状态卡唯一的状态文案：两态（Task 27）——真话文案，明细参数看下方 2×2 明细格。 */
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
 * 明细格：编码器 / 采样率 / 位深 / 码率（2×2，设置层参数）。
 * Task 45：「实际传输」以分隔线行的形式并回本卡（见 RealtimeSection），不再独立成卡。
 */
@Composable
private fun DetailGrid(state: HomeUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailRow("编码器" to state.codec, "采样率" to state.rate)
        DetailRow("位深" to state.bits, "码率" to state.quality)
    }
}

/**
 * Task 45：实际传输行（方案 C）——分隔线 + 「实际传输」行 + 超小字解释行。
 * 空态三分（沿 Task 44 语义）：
 *   正常        → 「400 kbps · ABR（自适应）」（ABR 必须带「自适应」三字——别人不认识缩写）
 *   未在播放     → 「未在播放」（Task 46 isMusicActive 即时判定，停歌不再残留粘性码率）
 *   暂不支持抓取 → 「—（本机暂不支持抓取）」（Task 46 用户拍板的人话文案）
 * 解释行说明「为什么这么设置」；未连接耳机时本段由调用方整段跳过。
 */
@Composable
private fun RealtimeSection(state: HomeUiState) {
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "实际传输",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = when {
                    state.realtimeNotStreaming -> "未在播放"
                    state.realtimeKbps != null -> {
                        val qm = state.realtimeQualityMode
                        when {
                            qm != null && qm.contains("ABR", ignoreCase = true) ->
                                "${state.realtimeKbps} kbps · ABR（自适应）"
                            qm != null -> "${state.realtimeKbps} kbps · $qm"
                            else -> "${state.realtimeKbps} kbps"
                        }
                    }
                    else -> "—（本机暂不支持抓取）"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            // Task 46（用户中途追加）：只有抓取不支持时换成反馈指引，其余状态保留调度解释。
            text = if (!state.realtimeNotStreaming && state.realtimeKbps == null) {
                "这个机型的系统不开放实时码率读取，欢迎到设置-日志页把日志反馈给作者"
            } else {
                "部分 vivo LHDC 栈实测固定档仍走 ABR；其他系统样本不足，请对比设置码率与实际传输"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                carrierAlert = "Shizuku 未运行，点我修复",
            ),
            onAction = {},
        )
    }
}
