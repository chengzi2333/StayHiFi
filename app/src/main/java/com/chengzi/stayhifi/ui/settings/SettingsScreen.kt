package com.chengzi.stayhifi.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.BuildConfig
import com.chengzi.stayhifi.shell.ProviderKind
import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.ui.common.RootStatusCard
import com.chengzi.stayhifi.ui.common.SubPageTopBar
import com.chengzi.stayhifi.ui.common.noWindowInsets
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import com.chengzi.stayhifi.util.QqGroup

data class SettingsUiState(
    /** 「自动拉满」总开关（Task 50 从首页迁入）。 */
    val autoApplyEnabled: Boolean = true,
    /** 拉满结果通知。 */
    val notifyResult: Boolean = false,
    /** 常驻通知显示参数详情（事件驱动 + 30s 轮询）。 */
    val notifyPersistent: Boolean = false,
    /** QS 磁贴可用。 */
    val qsTile: Boolean = false,
    /** 电池白名单状态（展示用）。 */
    val batteryWhitelisted: Boolean = false,
    /** Task 38：更新检查进行中（按钮 loading 态）。 */
    val checkingUpdate: Boolean = false,
    /** Task 38：检查结果内联提示（已是最新/检查失败），非 null 时弹 snackbar。 */
    val updateMessage: String? = null,
    /** Task D11：Coordinator 持久选择 / 生效通道（首读前为 null，按默认 Shizuku 展示）。 */
    val selectedProvider: ProviderKind? = null,
    val activeProvider: ProviderKind? = null,
    /** Task D11：Root 可观察快照（Root 状态/最近错误数据源，初值与冷启动一致）。 */
    val root: RootSnapshot = RootSnapshot(
        status = RootStatus.UNVERIFIED,
        granted = null,
        shellStatus = RootShellStatus.UNKNOWN,
        observedReason = "cold start",
    ),
    /** Task D11：切换事务/授权请求进行中——禁用重复操作（§11.3 单飞/有界切换）。 */
    val providerSwitching: Boolean = false,
    /** Task D11：Root 区展开（EXTRA_OPEN_ROOT_SETTINGS 修复入口/点击 Root 卡）。 */
    val rootSectionExpanded: Boolean = false,
    /** UX 收敛：修复入口 deep link 的单调计数——每次 EXTRA_OPEN_ROOT_SETTINGS 自增，
     *  设置页据此打开「特权方式」二级页（rootSectionExpanded 布尔在重复触发时不变化，
     *  无法再次驱动 LaunchedEffect）。 */
    val rootOpenSeq: Int = 0,
)

/** 设置页全部动作上抛，本层无逻辑。 */
sealed interface SettingsAction {
    data class SetAutoApply(val enabled: Boolean) : SettingsAction
    data class SetNotifyResult(val enabled: Boolean) : SettingsAction
    data class SetNotifyPersistent(val enabled: Boolean) : SettingsAction
    data class SetQsTile(val enabled: Boolean) : SettingsAction
    data object OpenAutostart : SettingsAction
    data object OpenBatteryWhitelist : SettingsAction
    data object OpenBackgroundPower : SettingsAction
    data object OpenLogs : SettingsAction
    /** Task 38：应用内检查 GitHub Releases 新版本（不再跳浏览器）。 */
    data object CheckUpdate : SettingsAction
    /** Task 38：检查结果 snackbar 展示完毕，清掉提示。 */
    data object DismissUpdateMessage : SettingsAction
    data object CopyQqGroupNumber : SettingsAction
    data object OpenQqGroup : SettingsAction
    /** Task D11：设置页「特权方式」选择——Shizuku/Stellar 立即提交切换事务；Root 只展开 Root 区（纯 UI 态）。 */
    data class SelectProvider(val kind: ProviderKind) : SettingsAction
    /** Task D11：Root 卡唯一授权入口——显式点击才触发前台验证+提交（§11.2/§11.4.6）。 */
    data object RequestRootAuthorization : SettingsAction
}

/**
 * 设置页：按使用频率排序——自动化 / 通知 / 快捷方式 / 后台保活 / 特权方式（drill-in）/ 关于。
 * 「特权方式」两张选择卡与 Root 状态卡收进二级页（Stellar 式 drill-in），
 * 首屏不再被实验性通道的说明文字占据；修复 deep link 直达该二级页。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「特权方式」二级页为纯 UI 态；修复入口（rootOpenSeq 自增）自动打开。
    var providerPageOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.rootOpenSeq) {
        if (state.rootOpenSeq > 0) providerPageOpen = true
    }
    Scaffold(
        modifier = modifier,
        snackbarHost = {
            // Task 38：检查结果提示（已是最新 / 检查失败）
            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(state.updateMessage) {
                val msg = state.updateMessage
                if (msg != null) {
                    snackbarHostState.showSnackbar(msg)
                    onAction(SettingsAction.DismissUpdateMessage)
                }
            }
            SnackbarHost(hostState = snackbarHostState)
        },
    ) { innerPadding ->
        if (providerPageOpen) {
            BackHandler { providerPageOpen = false }
            ProviderPage(
                state = state,
                onAction = onAction,
                onBack = { providerPageOpen = false },
                modifier = Modifier.padding(innerPadding),
            )
        } else {
            MainSettingsList(
                state = state,
                onAction = onAction,
                onOpenProviderPage = { providerPageOpen = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun MainSettingsList(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpenProviderPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
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

        SectionTitle("自动化")
        SwitchRow(
            title = "自动拉满",
            subtitle = "总开关：关闭后所有设备连上都不再自动拉满",
            checked = state.autoApplyEnabled,
            onChecked = { onAction(SettingsAction.SetAutoApply(it)) },
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
            title = "快捷设置磁贴",
            subtitle = "下拉快捷面板一键拉满",
            checked = state.qsTile,
            onChecked = { onAction(SettingsAction.SetQsTile(it)) },
        )

        SectionTitle("后台保活")
        NavRow(title = "自启动权限", actionLabel = "去开启") {
            onAction(SettingsAction.OpenAutostart)
        }
        NavRow(
            title = "电池白名单",
            actionLabel = if (state.batteryWhitelisted) "已加入" else "去加入",
            actionActive = !state.batteryWhitelisted,
        ) {
            onAction(SettingsAction.OpenBatteryWhitelist)
        }
        NavRow(
            title = "后台高耗电",
            subtitle = "允许后台高耗电，并在最近任务里锁定卡片防清理",
            actionLabel = "去设置",
        ) {
            onAction(SettingsAction.OpenBackgroundPower)
        }

        // Task D11（§11.2）+ UX 收敛：与首次教程同一「特权方式」入口，收成一行 drill-in；
        // 已升级用户无需清数据即可参与 Root 实验；切换与 Root 授权在二级页完成。
        SectionTitle("特权方式")
        NavRow(
            title = "权限通道",
            subtitle = if (state.selectedProvider == ProviderKind.ROOT) {
                "Root（实验）"
            } else {
                "Shizuku / Stellar（推荐）"
            },
            actionLabel = "更改",
        ) {
            onOpenProviderPage()
        }

        SectionTitle("关于")
        NavRow(
            title = "运行日志",
            subtitle = "仅保存在本机，可复制反馈给作者",
            actionLabel = "查看",
        ) {
            onAction(SettingsAction.OpenLogs)
        }
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
                        text = "当前 v${BuildConfig.VERSION_NAME} · 检查 GitHub 新版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.checkingUpdate) {
                    // Task 38：检查中转圈
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        text = "检查",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                    text = "GPL-3.0 开源；与 Shizuku/Stellar/厂商无隶属关系；数据仅存本机，零上传。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "QQ 交流群：${QqGroup.GROUP_NUMBER}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { onAction(SettingsAction.CopyQqGroupNumber) }) {
                        Text("复制")
                    }
                    TextButton(onClick = { onAction(SettingsAction.OpenQqGroup) }) {
                        Text("打开 QQ")
                    }
                }
            }
        }
    }
}

/** 「特权方式」二级页：两张通道选择卡 + Root 状态卡（共享实现），返回回设置主列表。 */
@Composable
private fun ProviderPage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 与运行日志页同一返回箭头样式；本页已在外层 Scaffold content padding 之内，
        // TopAppBar 自带的状态栏内边距置零，避免重复叠加。
        SubPageTopBar(
            title = "特权方式",
            onBack = onBack,
            windowInsets = noWindowInsets,
        )
        ProviderSelectCard(
            title = "Shizuku / Stellar（推荐）",
            description = "安装任意一个并授权，即可获得调校权限。",
            selected = (state.selectedProvider ?: ProviderKind.SHIZUKU) == ProviderKind.SHIZUKU,
            enabled = !state.providerSwitching,
            onClick = { onAction(SettingsAction.SelectProvider(ProviderKind.SHIZUKU)) },
        )
        ProviderSelectCard(
            title = "Root（实验）",
            // spec §11.2 简洁事实句（UX 收敛后口径，三条边界齐全：实验/不提上限/可退回）
            description = "已 Root 的设备可选；不提升音质上限，随时可切回。",
            selected = state.selectedProvider == ProviderKind.ROOT,
            enabled = !state.providerSwitching,
            onClick = { onAction(SettingsAction.SelectProvider(ProviderKind.ROOT)) },
        )
        if (state.providerSwitching) {
            Text(
                text = "正在切换特权方式…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 已持久 Root（含撤权后保留选择）或经修复入口/点击展开时，显示 Root 状态与修复入口。
        if (state.selectedProvider == ProviderKind.ROOT || state.rootSectionExpanded) {
            RootStatusCard(
                root = state.root,
                busy = state.providerSwitching ||
                    state.root.status == RootStatus.REQUESTING ||
                    state.root.status == RootStatus.BINDING,
                onRequestAuthorization = { onAction(SettingsAction.RequestRootAuthorization) },
            )
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

/** 设置页导航行（卡片 + 标题/副标 + 尾部动作标签），统一「去开启/查看/更改」样式。 */
@Composable
private fun NavRow(
    title: String,
    actionLabel: String,
    subtitle: String? = null,
    actionActive: Boolean = true,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
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
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = if (actionActive) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/** Task D11：「特权方式」选择卡；切换事务进行中禁用重复点击（§11.3）。 */
@Composable
private fun ProviderSelectCard(
    title: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Text(
                        text = "已选",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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
