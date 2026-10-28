package com.chengzi.stayhifi.ui.settings

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
 * 设置页：自动拉满总开关 + 通知/磁贴开关 + 保活引导 + 运行日志入口 + 关于。
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
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

            // Task D11（§11.2）：与首次教程同一「特权方式」入口——已升级用户无需清数据
            // 即可参与 Root 实验；「改用 Shizuku/Stellar」任何时候可手动选择，不要求 Root 当前可用。
            SectionTitle("特权方式")
            ProviderSelectCard(
                title = "Shizuku / Stellar（默认）",
                description = "借助 Shizuku 或 Stellar 获得系统权限，任选其一准备好即可。",
                selected = (state.selectedProvider ?: ProviderKind.SHIZUKU) == ProviderKind.SHIZUKU,
                enabled = !state.providerSwitching,
                onClick = { onAction(SettingsAction.SelectProvider(ProviderKind.SHIZUKU)) },
            )
            ProviderSelectCard(
                title = "Root（实验）",
                // spec §11.2 原文，不得改写
                description = "适用于已经 Root 的设备。仅提供系统权限通道，不会提高耳机或编码器能力上限。" +
                    "兼容性仍在收集反馈，可随时手动切回 Shizuku/Stellar。",
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
            // 已持久 Root（含撤权后保留选择）或经修复入口/点击展开时，显示 Root 独立状态与修复入口。
            if (state.selectedProvider == ProviderKind.ROOT || state.rootSectionExpanded) {
                SettingsRootStatusCard(state, onAction)
            }

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
                            text = "当前 v${BuildConfig.VERSION_NAME} · 应用内检查 GitHub 新版本",
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
                        text = "GPL-3.0 开源软件。与 Shizuku、Stellar、OPPO、vivo 无任何隶属关系；" +
                            "所有数据仅保存在本机，零数据采集、零网络上传。",
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

/**
 * Task D11：Root 独立状态卡（§11.2）——不硬套「已安装/运行中/已授权」三灯，
 * 展示 Root 可用性、授权状态、服务连接状态和最近错误（事实与推测分列）。
 * 已持久 Root 撤权后保留选择并在此显示修复入口，不静默回退；
 * 授权按钮仅显式动作可用，switching/requesting 期间禁用重复操作。
 */
@Composable
private fun SettingsRootStatusCard(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
) {
    val root = state.root
    val ready = root.status == RootStatus.READY
    val busy = state.providerSwitching ||
        root.status == RootStatus.REQUESTING || root.status == RootStatus.BINDING
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
            RootFactRow("Root 可用性", rootAvailabilityText(root))
            RootFactRow("授权状态", rootGrantText(root))
            RootFactRow("服务连接", rootServiceText(root))
            RootFactRow("最近错误", rootLastErrorText(root))
            if (!ready) {
                Button(
                    onClick = { onAction(SettingsAction.RequestRootAuthorization) },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(if (busy) "正在验证…" else "请求 Root 授权")
                }
            }
        }
    }
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
