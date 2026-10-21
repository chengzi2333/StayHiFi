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
import com.chengzi.stayhifi.ui.theme.FullBarsTheme

data class HomeUiState(
    /** 耳机名；null 表示当前未连接耳机。 */
    val headsetName: String? = null,
    /** 当前参数摘要，形如 "LHDC·192kHz·24bit"；null 表示未知。 */
    val configSummary: String? = null,
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

            StatusCard(state)

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
                FullBarsBadge(fullBars = state.fullBars)
            }
            Text(
                text = state.configSummary ?: "连接后显示当前音质参数",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = if (state.configSummary != null) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun FullBarsBadge(fullBars: Boolean) {
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
    FullBarsTheme(dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                headsetName = "OPPO Enco X3",
                configSummary = "LHDC·192kHz·24bit",
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
    FullBarsTheme(darkTheme = true, dynamicColor = false) {
        HomeScreen(
            state = HomeUiState(
                headsetName = "OPPO Enco X3",
                configSummary = "SBC·44.1kHz·16bit",
                fullBars = false,
                autoApplyEnabled = false,
                carrierAlert = "Shizuku 未运行，点我修复",
            ),
            onAction = {},
        )
    }
}
