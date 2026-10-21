package com.chengzi.btmax.ui.onboarding

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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.btmax.ui.theme.FullBarsTheme
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 5

/** 参数来源选择。 */
enum class SourceMode(val title: String, val description: String) {
    AUTO_FULL("自动拉满（推荐）", "连上耳机就用这台设备已知的最强参数，全程无感。"),
    RECORD_WIZARD("录制向导", "没有现成模板时，抓一次当前参数，存成这台耳机的专属模板。"),
}

/** 特权载体类型。 */
enum class CarrierKind(val displayName: String) {
    SHIZUKU("Shizuku"),
    STELLAR("Stellar"),
}

/** 单个载体的三态：已安装 / 运行中 / 已授权。 */
data class CarrierUiState(
    val kind: CarrierKind,
    val installed: Boolean = false,
    val running: Boolean = false,
    val granted: Boolean = false,
) {
    /** 三个灯全绿即视为就绪。 */
    val ready: Boolean get() = installed && running && granted
}

data class OnboardingUiState(
    val shizuku: CarrierUiState = CarrierUiState(CarrierKind.SHIZUKU),
    val stellar: CarrierUiState = CarrierUiState(CarrierKind.STELLAR),
    val headsetConnected: Boolean = false,
    val headsetName: String? = null,
    val sourceMode: SourceMode = SourceMode.AUTO_FULL,
    val autostartEnabled: Boolean = false,
    val batteryWhitelistEnabled: Boolean = false,
)

/** 引导页全部动作上抛，本层无逻辑。 */
sealed interface OnboardingAction {
    data object Back : OnboardingAction
    data object Next : OnboardingAction
    data object Finish : OnboardingAction

    data class InstallCarrier(val kind: CarrierKind) : OnboardingAction
    data class OpenCarrierGuide(val kind: CarrierKind) : OnboardingAction
    data class RequestCarrierPermission(val kind: CarrierKind) : OnboardingAction

    data object RefreshConnection : OnboardingAction

    data class SelectSource(val mode: SourceMode) : OnboardingAction

    data object OpenAutostartSettings : OnboardingAction
    data object OpenBatteryWhitelist : OnboardingAction

    data object OpenRecordWizard : OnboardingAction
}

/**
 * 五页首次引导：欢迎 → 载体检测 → 连接耳机 → 参数来源 → 保活设置。
 * 状态全部上抛，页面自身无状态、无业务逻辑。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> CarrierPage(state, onAction)
                    2 -> ConnectPage(state, onAction)
                    3 -> SourcePage(state, onAction)
                    4 -> BatteryPage(state, onAction)
                }
            }

            val isLastPage = pagerState.currentPage == PAGE_COUNT - 1
            OnboardingBottomBar(
                showBack = pagerState.currentPage > 0,
                isLastPage = isLastPage,
                currentPage = pagerState.currentPage,
                pageCount = PAGE_COUNT,
                onBack = {
                    if (pagerState.currentPage > 0) {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
                    } else {
                        onAction(OnboardingAction.Back)
                    }
                },
                onNext = {
                    if (isLastPage) {
                        onAction(OnboardingAction.Finish)
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                },
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(Modifier.weight(0.4f))
        // 痛点插画占位：后续替换为正式 Lottie/插画资源
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text("🎧", style = MaterialTheme.typography.displayLarge)
        }
        Text(
            text = "音质满格",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "耳机一连，音质满血",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "不用每次进开发者选项手动调参数，\n连上耳机就自动把音质拉到最高。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CarrierPage(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "选择特权方式",
            subtitle = "音质满格需要通过 Shizuku 或 Stellar 获得系统权限，任选其一准备好即可。",
        )
        CarrierCard(state.shizuku, onAction)
        CarrierCard(state.stellar, onAction)
    }
}

@Composable
private fun CarrierCard(
    carrier: CarrierUiState,
    onAction: (OnboardingAction) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (carrier.ready) {
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
                    text = carrier.kind.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (carrier.ready) {
                    StatusBadge(text = "已就绪", positive = true)
                }
            }
            CarrierStatusRow("已安装", carrier.installed)
            CarrierStatusRow("运行中", carrier.running)
            CarrierStatusRow("已授权", carrier.granted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!carrier.installed) {
                    Button(
                        onClick = { onAction(OnboardingAction.InstallCarrier(carrier.kind)) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("去安装") }
                } else {
                    if (!carrier.running) {
                        Button(
                            onClick = { onAction(OnboardingAction.OpenCarrierGuide(carrier.kind)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("启动指引") }
                    }
                    if (!carrier.granted) {
                        TextButton(
                            onClick = { onAction(OnboardingAction.RequestCarrierPermission(carrier.kind)) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("请求授权") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarrierStatusRow(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                ),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectPage(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "连接你的耳机",
            subtitle = "请戴上耳机，或打开耳机盒盖，让手机完成蓝牙连接。",
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.headsetConnected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.headsetConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                        ),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (state.headsetConnected) "已连接" else "等待连接…",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.headsetName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { onAction(OnboardingAction.RefreshConnection) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("刷新") }
            }
        }
    }
}

@Composable
private fun SourcePage(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "参数从哪里来？",
            subtitle = "选一个方式，之后随时可以改。",
        )
        SourceMode.entries.forEach { mode ->
            val selected = state.sourceMode == mode
            Card(
                onClick = { onAction(OnboardingAction.SelectSource(mode)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = mode.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.weight(1f))
                        if (selected) {
                            StatusBadge(text = "已选", positive = true)
                        }
                    }
                    Text(
                        text = mode.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BatteryPage(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader(
            title = "保持后台可用",
            subtitle = "避免系统杀后台，让自动拉满在每次连接时都能生效。",
        )
        BatteryGuideItem(
            title = "允许自启动",
            description = "在系统设置里允许音质满格自启动。",
            done = state.autostartEnabled,
            actionLabel = "去设置",
            onAction = { onAction(OnboardingAction.OpenAutostartSettings) },
        )
        BatteryGuideItem(
            title = "加入电池白名单",
            description = "关闭对音质满格的电池优化，防止后台被限制。",
            done = state.batteryWhitelistEnabled,
            actionLabel = "去设置",
            onAction = { onAction(OnboardingAction.OpenBatteryWhitelist) },
        )
        if (state.sourceMode == SourceMode.RECORD_WIZARD) {
            Button(
                onClick = { onAction(OnboardingAction.OpenRecordWizard) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) { Text("现在去录制参数模板") }
        }
    }
}

@Composable
private fun BatteryGuideItem(
    title: String,
    description: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (done) {
                StatusBadge(text = "已完成", positive = true)
            } else {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusBadge(text: String, positive: Boolean) {
    val bg = if (positive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.error
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.surface,
        )
    }
}

@Composable
private fun OnboardingBottomBar(
    showBack: Boolean,
    isLastPage: Boolean,
    currentPage: Int,
    pageCount: Int,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(pageCount) { index ->
                val selected = index == currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (selected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("上一步")
                }
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onNext,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 8.dp),
            ) { Text(if (isLastPage) "完成" else "下一步") }
        }
    }
}

@Preview(showBackground = true, name = "引导页-浅色")
@Composable
private fun OnboardingScreenPreviewLight() {
    FullBarsTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                shizuku = CarrierUiState(CarrierKind.SHIZUKU, installed = true, running = true, granted = true),
                stellar = CarrierUiState(CarrierKind.STELLAR, installed = true, running = false, granted = false),
            ),
            onAction = {},
        )
    }
}

@Preview(showBackground = true, name = "引导页-深色")
@Composable
private fun OnboardingScreenPreviewDark() {
    FullBarsTheme(darkTheme = true, dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                headsetConnected = true,
                headsetName = "OPPO Enco X3",
                sourceMode = SourceMode.RECORD_WIZARD,
                autostartEnabled = true,
            ),
            onAction = {},
        )
    }
}
