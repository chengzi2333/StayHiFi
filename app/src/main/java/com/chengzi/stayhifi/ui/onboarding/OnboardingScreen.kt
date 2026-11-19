package com.chengzi.stayhifi.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.shell.ProviderKind
import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.ui.common.RootStatusCard
import com.chengzi.stayhifi.ui.nav.StayHiFiIcons
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// UX 收敛：「参数来源」页撤出主路径（诊断工具不与自动拉满并列），引导 5→4 页；
// 记录当前参数的入口移到设备页（空态按钮 + 设备卡「重新记录」）。
private const val PAGE_COUNT = 4

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
    /** Task D10：教程内的通道选择（纯 UI 态，默认 Shizuku/Stellar 组）。仅选择/查看不写 DataStore；
     *  Root 只有「请求 Root 授权」验证+提交成功后才经 Coordinator 持久化（§11.2/§11.3）。 */
    val providerChoice: ProviderKind = ProviderKind.SHIZUKU,
    /** Coordinator 持久选择 / 生效通道（首读前为 null）；Root 门槛要求二者均为 ROOT。 */
    val selectedProvider: ProviderKind? = null,
    val activeProvider: ProviderKind? = null,
    /** Root 可观察快照（Root 状态卡数据源，初值与 RootTransport 冷启动一致）。 */
    val root: RootSnapshot = RootSnapshot(
        status = RootStatus.UNVERIFIED,
        granted = null,
        shellStatus = RootShellStatus.UNKNOWN,
        observedReason = "cold start",
    ),
    /** Root 前台授权/切换事务进行中：禁用重复点击（§11.3 单飞/有界切换）。 */
    val providerOperationInFlight: Boolean = false,
    val headsetConnected: Boolean = false,
    val headsetName: String? = null,
    val autostartEnabled: Boolean = false,
    val batteryWhitelistEnabled: Boolean = false,
)

/**
 * Task D10：载体页「下一步」门槛（设计 §11.2）。
 *  - Shizuku/Stellar 组沿用「任一载体三灯就绪」；
 *  - Root 组只有 持久选择=生效通道=ROOT 且 RootSnapshot=READY 才放行——
 *    Shizuku ready 不放行；candidate 健康（transport READY）但尚未 commit 也不放行。
 */
fun carrierPageReady(state: OnboardingUiState): Boolean = when (state.providerChoice) {
    ProviderKind.SHIZUKU -> state.shizuku.ready || state.stellar.ready
    ProviderKind.ROOT ->
        state.selectedProvider == ProviderKind.ROOT &&
            state.activeProvider == ProviderKind.ROOT &&
            state.root.status == RootStatus.READY
}

/** 引导页全部动作上抛，本层无逻辑。 */
sealed interface OnboardingAction {
    data object Back : OnboardingAction
    data object Next : OnboardingAction
    data object Finish : OnboardingAction

    data class OpenCarrierGuide(val kind: CarrierKind) : OnboardingAction
    data class RequestCarrierPermission(val kind: CarrierKind) : OnboardingAction

    /** Task D10：教程内切换通道选择（纯 UI 态，不写 DataStore；改选同时作废进行中 attempt）。 */
    data class SelectProvider(val kind: ProviderKind) : OnboardingAction
    /** Task D10：Root 卡唯一授权入口——显式点击才触发前台验证+提交（§11.2/§11.4.6）。 */
    data object RequestRootAuthorization : OnboardingAction
    /** Task D10：离页/改选时作废进行中的前台 Root attempt，迟到成功不得提交（§11.4.6）。 */
    data object CancelRootAuthorization : OnboardingAction

    data object RefreshConnection : OnboardingAction

    data object OpenAutostartSettings : OnboardingAction
    data object OpenBatteryWhitelist : OnboardingAction
    /** 第三道闸：后台高耗电（+ 卡片锁定在文案里引导，系统无 API 可查/可跳）。 */
    data object OpenBackgroundPower : OnboardingAction
}

/**
 * 四页首次引导：欢迎 → 特权方式 → 连接耳机 → 保活设置。
 * 状态全部上抛，页面自身无状态、无业务逻辑。
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onAction: (OnboardingAction) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: Int = 0,
) {
    val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()

    // Task 36 Bug B：翻到第三步（page 2）自动检测一次，停留期间每 2.5s 轮询连接态；
    // 已连接或离开页面时 key 变化、协程自动取消即停。「刷新」按钮保留作兜底。
    val onConnectPage = pagerState.currentPage == 2
    LaunchedEffect(onConnectPage, state.headsetConnected) {
        if (onConnectPage && !state.headsetConnected) {
            onAction(OnboardingAction.RefreshConnection)
            while (true) {
                delay(2500)
                onAction(OnboardingAction.RefreshConnection)
            }
        }
    }

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
                    3 -> BatteryPage(state, onAction)
                }
            }

            val isLastPage = pagerState.currentPage == PAGE_COUNT - 1
            // 硬门槛：第 1 页按当前所选通道判定（Task D10，§11.2：Shizuku 组任一载体就绪；
            // Root 组需 selected=active=ROOT 且快照 READY），第 2 页要求耳机已连接；
            // 未达标时「下一步」置灰，但保留「稍后再说」逃生口，避免用户被卡死。
            val nextEnabled = when (pagerState.currentPage) {
                1 -> carrierPageReady(state)
                2 -> state.headsetConnected
                else -> true
            }
            OnboardingBottomBar(
                showBack = pagerState.currentPage > 0,
                isLastPage = isLastPage,
                currentPage = pagerState.currentPage,
                pageCount = PAGE_COUNT,
                nextEnabled = nextEnabled,
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
            text = "StayHiFi",
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
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        PageHeader(
            title = "选择授权方式",
            subtitle = "改耳机音质参数需要一次系统授权。两种方式挑一个，之后随时能改。",
        )
        // 内容组在剩余空间里垂直居中，避免上重下空的观感。
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
        // 一行两个模式卡：默认 ADB，选中哪张下面就展开哪张的授权卡（同一套交互）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModeCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                title = "ADB 模式",
                description = "免 Root，装个工具授权一次",
                selected = state.providerChoice == ProviderKind.SHIZUKU,
                onClick = { onAction(OnboardingAction.SelectProvider(ProviderKind.SHIZUKU)) },
            )
            ModeCard(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                title = "Root 模式",
                description = "已 Root 的设备可选，实验性",
                selected = state.providerChoice == ProviderKind.ROOT,
                onClick = { onAction(OnboardingAction.SelectProvider(ProviderKind.ROOT)) },
            )
        }
        // 两种模式共用一套结构：一张状态卡 + 一个主操作（ADB / Root 交互完全对齐）。
        when (state.providerChoice) {
            ProviderKind.SHIZUKU -> AdbPermissionCard(state, onAction)
            ProviderKind.ROOT -> RootStatusCardSection(state, onAction)
        }
        }
    }
}

/** 模式卡（一行两列共用）：标题 + 一行小字，选中态靠底色区分；两张卡等高。 */
@Composable
private fun ModeCard(
    modifier: Modifier = Modifier,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
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
            modifier = Modifier
                .fillMaxHeight()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** ADB 侧当前该操作哪个载体：就绪 > 运行中 > 已安装 > 默认 Shizuku（用于「去安装」）。 */
private fun adbTarget(state: OnboardingUiState): CarrierUiState = when {
    state.shizuku.ready -> state.shizuku
    state.stellar.ready -> state.stellar
    state.shizuku.running -> state.shizuku
    state.stellar.running -> state.stellar
    state.shizuku.installed -> state.shizuku
    state.stellar.installed -> state.stellar
    else -> state.shizuku
}

private fun carrierStateText(carrier: CarrierUiState): String = when {
    carrier.ready -> "已就绪"
    !carrier.installed -> "未安装"
    !carrier.running -> "未启动"
    else -> "未授权"
}

/**
 * ADB 授权卡：把原「Shizuku 卡 + Stellar 卡」合成一张——用户只需要知道「现在能不能用」，
 * 不需要分别理解两个工具的状态。结构与 Root 侧 [RootStatusCard] 一致：状态 + 一个主操作。
 */
@Composable
private fun AdbPermissionCard(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    val target = adbTarget(state)
    val ready = state.shizuku.ready || state.stellar.ready
    val anyInstalled = state.shizuku.installed || state.stellar.installed
    val statusText = when {
        ready -> "已就绪，可以继续"
        !anyInstalled -> "未检测到授权工具"
        !target.running -> "${target.kind.displayName} 已安装，还没启动"
        else -> "等待授权"
    }
    // 不提供任何安装渠道：工具由用户自行安装，这里只给「启动/授权」两个动作。
    val primaryAction: (@Composable () -> Unit)? = when {
        ready || !anyInstalled -> null
        !target.running -> {
            {
                Button(
                    onClick = { onAction(OnboardingAction.OpenCarrierGuide(target.kind)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("启动 Shizuku") }
            }
        }
        else -> {
            {
                Button(
                    onClick = { onAction(OnboardingAction.RequestCarrierPermission(target.kind)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text("请求授权") }
            }
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (ready) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                        ),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "Shizuku · ${carrierStateText(state.shizuku)}　" +
                    "Stellar · ${carrierStateText(state.stellar)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            primaryAction?.invoke()
            Text(
                text = "装好 Shizuku、Shizuku-next 或 Stellar 任意一个后回到这里，检测是自动的；" +
                    "授权弹窗显示 Shizuku 属正常（Stellar 是其兼容分支）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Task D10：Root 状态卡（§11.2）——共享实现见 [com.chengzi.stayhifi.ui.common.RootStatusCard]
 * （一行状态 + 授权按钮 + 查看详情弹窗）。教程侧额外保留：离开组合（离页/改选）即作废
 * 进行中的前台 attempt，迟到成功不得提交（§11.4.6）。
 */
@Composable
private fun RootStatusCardSection(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    DisposableEffect(Unit) {
        onDispose { onAction(OnboardingAction.CancelRootAuthorization) }
    }
    val busy = state.providerOperationInFlight ||
        state.root.status == RootStatus.REQUESTING || state.root.status == RootStatus.BINDING
    RootStatusCard(
        root = state.root,
        busy = busy,
        onRequestAuthorization = { onAction(OnboardingAction.RequestRootAuthorization) },
    )
}

@Composable
private fun ConnectPage(state: OnboardingUiState, onAction: (OnboardingAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        PageHeader(
            title = "连接你的蓝牙音频设备",
            subtitle = "耳机、音箱都行。打开设备并靠近手机，在系统蓝牙里连上它。",
        )
        // 视觉主体是设备本身：一个图标 + 设备名，状态只用一行字说明，不再叠口号。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(168.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.headsetConnected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = StayHiFiIcons.Headphones,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp),
                        tint = if (state.headsetConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(Modifier.size(28.dp))
                Text(
                    text = if (state.headsetConnected) {
                        state.headsetName ?: "已连接"
                    } else {
                        "等待连接"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (state.headsetConnected) {
                        "已连接"
                    } else {
                        "打开设备并靠近手机"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(16.dp))
                TextButton(
                    onClick = { onAction(OnboardingAction.RefreshConnection) },
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text("重新检测") }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "连不上？",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "确认设备在配对模式 · 检查是否还连着别的手机 · 已连上却没反应就点「重新检测」",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            description = "在系统设置里允许 StayHiFi 自启动。",
            done = state.autostartEnabled,
            actionLabel = "去设置",
            onAction = { onAction(OnboardingAction.OpenAutostartSettings) },
        )
        BatteryGuideItem(
            title = "加入电池白名单",
            description = "关闭对 StayHiFi 的电池优化，防止后台被限制。",
            done = state.batteryWhitelistEnabled,
            actionLabel = "去设置",
            onAction = { onAction(OnboardingAction.OpenBatteryWhitelist) },
        )
        // 第三道闸（真机实证：自启动+白名单全开仍会被划卡杀）。
        // 「后台高耗电」可尽力跳系统页；「卡片锁定」无公开 API，纯文案引导。
        BatteryGuideItem(
            title = "后台高耗电 + 卡片锁定",
            description = "允许后台高耗电，并在最近任务里锁定 StayHiFi，防止被清理。",
            done = false,
            actionLabel = "去设置",
            onAction = { onAction(OnboardingAction.OpenBackgroundPower) },
        )
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
    nextEnabled: Boolean,
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
            if (!nextEnabled) {
                // 逃生口：核心功能未就绪时仍允许跳过，防止用户被卡死。
                TextButton(
                    onClick = onNext,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("稍后再说") }
            }
            Button(
                onClick = onNext,
                enabled = nextEnabled,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(start = 8.dp),
            ) { Text(if (isLastPage) "完成" else "下一步") }
        }
    }
}

@Preview(showBackground = true, name = "引导页-载体未就绪（下一步置灰+逃生口）")
@Composable
private fun OnboardingScreenPreviewCarrierNotReady() {
    StayHiFiTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                shizuku = CarrierUiState(CarrierKind.SHIZUKU, installed = true, running = false, granted = false),
                stellar = CarrierUiState(CarrierKind.STELLAR, installed = false, running = false, granted = false),
            ),
            onAction = {},
            initialPage = 1,
        )
    }
}

@Preview(showBackground = true, name = "引导页-Root（实验）未授权")
@Composable
private fun OnboardingScreenPreviewRootChoice() {
    StayHiFiTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                providerChoice = ProviderKind.ROOT,
                root = RootSnapshot(
                    status = RootStatus.NOT_GRANTED,
                    granted = false,
                    shellStatus = RootShellStatus.UNKNOWN,
                    observedReason = "isAppGrantedRoot=false",
                ),
            ),
            onAction = {},
            initialPage = 1,
        )
    }
}

@Preview(showBackground = true, name = "引导页-就绪态")
@Composable
private fun OnboardingScreenPreviewReady() {
    StayHiFiTheme(dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                shizuku = CarrierUiState(CarrierKind.SHIZUKU, installed = true, running = true, granted = true),
                stellar = CarrierUiState(CarrierKind.STELLAR, installed = true, running = false, granted = false),
                headsetConnected = true,
                headsetName = "OPPO Enco X3",
            ),
            onAction = {},
            initialPage = 2,
        )
    }
}

@Preview(showBackground = true, name = "引导页-深色")
@Composable
private fun OnboardingScreenPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        OnboardingScreen(
            state = OnboardingUiState(
                headsetConnected = true,
                headsetName = "OPPO Enco X3",
                autostartEnabled = true,
            ),
            onAction = {},
        )
    }
}
