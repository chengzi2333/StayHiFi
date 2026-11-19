package com.chengzi.stayhifi.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.BuildConfig
import com.chengzi.stayhifi.R
import com.chengzi.stayhifi.shell.ProviderKind
import com.chengzi.stayhifi.shell.RootShellStatus
import com.chengzi.stayhifi.shell.RootSnapshot
import com.chengzi.stayhifi.shell.RootStatus
import com.chengzi.stayhifi.ui.common.RootStatusCard
import com.chengzi.stayhifi.ui.common.SubPageTopBar
import com.chengzi.stayhifi.ui.common.noWindowInsets
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import com.chengzi.stayhifi.util.QqGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    /** 「自动拉满」总开关（Task 50 从首页迁入）。 */
    val autoApplyEnabled: Boolean = true,
    /** 拉满结果通知。 */
    val notifyResult: Boolean = false,
    /** 常驻通知显示参数详情（事件驱动 + 30s 轮询）。 */
    val notifyPersistent: Boolean = false,
    /** QS 磁贴可用。 */
    val qsTile: Boolean = false,
    /** v1.2.5：后台隐藏——最近任务里不显示本 App 卡片（仿 GKD）。 */
    val hideFromRecents: Boolean = false,
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
    /** v1.2.5：后台隐藏开关（开启前已在 UI 层做过二次确认）。 */
    data class SetHideFromRecents(val enabled: Boolean) : SettingsAction
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
/** 设置页二级页面。 */
enum class SettingsPage { MAIN, PROVIDER, KEEP_ALIVE, NOTIFY, ABOUT, DISCLAIMER, DONATE, REALTIME_INFO }

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(SettingsPage.MAIN) }
    // 返回目标：从「关于」进三级页时回「关于」，而不是直接跳回设置主页。
    var backTo by remember { mutableStateOf(SettingsPage.MAIN) }
    // 过渡方向：进下一级从右侧推入，返回从左侧推回。
    var forward by remember { mutableStateOf(true) }
    fun open(target: SettingsPage) {
        forward = true
        backTo = page
        page = target
    }
    fun back() {
        forward = false
        page = backTo
        backTo = SettingsPage.MAIN
    }
    // 修复入口（rootOpenSeq 自增）直达「权限通道」二级页；布尔在重复触发时不变化，故用计数驱动。
    LaunchedEffect(state.rootOpenSeq) {
        if (state.rootOpenSeq > 0) {
            forward = true
            backTo = SettingsPage.MAIN
            page = SettingsPage.PROVIDER
        }
    }
    Scaffold(
        modifier = modifier,
        // 已在外层 Scaffold 的内容区内，再吃一次状态栏内边距会把标题整体压下去。
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
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
        val contentModifier = Modifier.padding(innerPadding)
        // 返回统一在容器层拦截：页面切换正在动画时也只有一个 BackHandler 生效。
        BackHandler(enabled = page != SettingsPage.MAIN) { back() }
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val dir = if (forward) 1 else -1
                (
                    slideInHorizontally(animationSpec = tween(260)) { width -> width * dir } +
                        fadeIn(animationSpec = tween(180))
                    ) togetherWith (
                    slideOutHorizontally(animationSpec = tween(260)) { width -> -width * dir } +
                        fadeOut(animationSpec = tween(180))
                    )
            },
            modifier = Modifier.fillMaxSize(),
            label = "settingsPage",
        ) { current ->
            when (current) {
                SettingsPage.MAIN -> MainSettingsList(
                    state = state,
                    onAction = onAction,
                    onOpen = { open(it) },
                    modifier = contentModifier,
                )
                SettingsPage.PROVIDER -> ProviderPage(
                    state = state,
                    onAction = onAction,
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.KEEP_ALIVE -> KeepAlivePage(
                    onAction = onAction,
                    state = state,
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.NOTIFY -> NotifyPage(
                    state = state,
                    onAction = onAction,
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.ABOUT -> AboutPage(
                    state = state,
                    onAction = onAction,
                    onOpen = { open(it) },
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.DISCLAIMER -> DisclaimerPage(
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.DONATE -> DonatePage(
                    onBack = { back() },
                    modifier = contentModifier,
                )
                SettingsPage.REALTIME_INFO -> RealtimeInfoPage(
                    onBack = { back() },
                    modifier = contentModifier,
                )
            }
        }
    }
}

/**
 * 设置主列表：扁平行 + 分组标签（GKD 同款），不再一卡一行。
 * 高频开关留在一级，其余收进二级页。
 */
@Composable
private fun MainSettingsList(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hideConfirmOpen by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
        )

        GroupLabel("常规")
        SwitchSettingRow(
            title = "自动拉满",
            subtitle = "连上设备就调到最高音质",
            checked = state.autoApplyEnabled,
            onChecked = { onAction(SettingsAction.SetAutoApply(it)) },
        )
        // 后台隐藏提到一级：这是用户会反复开关的运行时偏好，不该藏在二级页里。
        SwitchSettingRow(
            title = "后台隐藏",
            subtitle = "最近任务里不显示本 App 卡片",
            checked = state.hideFromRecents,
            onChecked = { enabled ->
                if (enabled) hideConfirmOpen = true
                else onAction(SettingsAction.SetHideFromRecents(false))
            },
        )
        SwitchSettingRow(
            title = "快捷设置磁贴",
            checked = state.qsTile,
            divider = false,
            onChecked = { onAction(SettingsAction.SetQsTile(it)) },
        )

        GroupLabel("权限与保活")
        NavSettingRow(
            title = "权限通道",
            subtitle = if (state.selectedProvider == ProviderKind.ROOT) {
                "Root（实验）"
            } else {
                "ADB（Shizuku / Stellar）"
            },
        ) { onOpen(SettingsPage.PROVIDER) }
        NavSettingRow(title = "后台保活", divider = false) { onOpen(SettingsPage.KEEP_ALIVE) }

        GroupLabel("其他")
        NavSettingRow(title = "通知") { onOpen(SettingsPage.NOTIFY) }
        NavSettingRow(
            title = "关于",
            subtitle = "v${BuildConfig.VERSION_NAME}",
            divider = false,
        ) { onOpen(SettingsPage.ABOUT) }
    }

    if (hideConfirmOpen) {
        AlertDialog(
            onDismissRequest = { hideConfirmOpen = false },
            title = { Text("开启后台隐藏？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("隐藏后可能无法再给卡片加锁，建议先加锁再隐藏。")
                    Text(
                        text = "只影响任务卡片：常驻运行、通知和桌面图标都不变。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        hideConfirmOpen = false
                        onAction(SettingsAction.SetHideFromRecents(true))
                    },
                ) { Text("继续") }
            },
            dismissButton = {
                TextButton(onClick = { hideConfirmOpen = false }) { Text("取消") }
            },
        )
    }
}

/** 后台保活二级页：只留三项系统跳转。 */
@Composable
private fun KeepAlivePage(
    onAction: (SettingsAction) -> Unit,
    state: SettingsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SubPageTopBar(title = "后台保活", onBack = onBack, windowInsets = noWindowInsets)
        Spacer(Modifier.size(8.dp))
        NavSettingRow(title = "自启动权限") { onAction(SettingsAction.OpenAutostart) }
        NavSettingRow(
            title = "电池白名单",
            subtitle = if (state.batteryWhitelisted) "已加入" else null,
        ) { onAction(SettingsAction.OpenBatteryWhitelist) }
        NavSettingRow(
            title = "后台高耗电",
            subtitle = "允许后台高耗电，并在最近任务里锁定卡片",
            divider = false,
        ) { onAction(SettingsAction.OpenBackgroundPower) }
    }
}

/** 通知二级页。 */
@Composable
private fun NotifyPage(
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
    ) {
        SubPageTopBar(title = "通知", onBack = onBack, windowInsets = noWindowInsets)
        Spacer(Modifier.size(8.dp))
        SwitchSettingRow(
            title = "常驻通知显示参数详情",
            subtitle = "正文实时显示当前音质参数",
            checked = state.notifyPersistent,
            onChecked = { onAction(SettingsAction.SetNotifyPersistent(it)) },
        )
        SwitchSettingRow(
            title = "拉满结果提醒",
            subtitle = "每次拉满后额外弹一条通知",
            checked = state.notifyResult,
            divider = false,
            onChecked = { onAction(SettingsAction.SetNotifyResult(it)) },
        )
    }
}

/** 关于页：居中应用信息 + 检查更新 + 分组列表（FlClash / GKD 同款结构）。 */
@Composable
private fun AboutPage(
    state: SettingsUiState,
    onAction: (SettingsAction) -> Unit,
    onOpen: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SubPageTopBar(title = "关于", onBack = onBack, windowInsets = noWindowInsets)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // adaptive-icon 是 XML，painterResource 会崩；这里用「背景色 + 前景位图」复刻同一视觉效果。
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(colorResource(R.color.ic_launcher_background)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.size(14.dp))
            Text(
                text = "StayHiFi",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VersionPill("v${BuildConfig.VERSION_NAME}")
                VersionPill("GPL-3.0")
            }
            Spacer(Modifier.size(12.dp))
            Text(
                text = "把蓝牙音质调到设备能给的最好",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(18.dp))
            OutlinedButton(
                onClick = { onAction(SettingsAction.CheckUpdate) },
                enabled = !state.checkingUpdate,
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
            ) {
                if (state.checkingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(8.dp))
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(if (state.checkingUpdate) "检查中…" else "检查更新")
            }
        }

        NavSettingRow(title = "实际传输说明", divider = true) { onOpen(SettingsPage.REALTIME_INFO) }
        NavSettingRow(title = "开源协议与免责声明") { onOpen(SettingsPage.DISCLAIMER) }
        NavSettingRow(title = "运行日志") { onAction(SettingsAction.OpenLogs) }
        SettingRow(
            title = "反馈与交流",
            onClick = { onAction(SettingsAction.OpenQqGroup) },
            trailing = {
                Text(
                    text = "点击进Q群 ${QqGroup.GROUP_NUMBER}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
        )
        NavSettingRow(title = "捐赠支持", divider = false) { onOpen(SettingsPage.DONATE) }
    }
}

@Composable
private fun VersionPill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 免责声明 / 开源协议页（文档型页面，保留分段正文）。 */
@Composable
private fun DisclaimerPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageTopBar(title = "开源协议与免责声明", onBack = onBack, windowInsets = noWindowInsets)
        SectionTitle("软件性质")
        Text(
            text = "StayHiFi 是个人开发的开源工具，遵循 GPL-3.0 协议，免费且无广告、无内购。" +
                "它不是商业产品，与任何手机厂商、耳机品牌均无隶属、赞助或合作关系。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("它做了什么")
        Text(
            text = "通过你主动授权的系统通道（Shizuku / Stellar / Root），读取并写入蓝牙 A2DP 编码参数，" +
                "把采样率、位深、码率档调整到当前设备允许的最高值。全程不修改系统文件，也不联网上传任何数据。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("使用风险")
        Text(
            text = "写入的是系统级参数，个别机型可能出现播放异常、无声或连接不稳。出现问题时，可在设备页使用" +
                "「恢复默认参数」，或断开重连、重启蓝牙。是否使用由你自行判断并承担相应后果，" +
                "作者不对由此产生的直接或间接损失负责。\n" +
                "本工具不提升硬件的物理上限，能调到的最高档取决于耳机与手机共同支持的编码能力。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("授权与隐私")
        Text(
            text = "授权通道由 Shizuku / Stellar / Root 提供，权限始终掌握在你手里，随时可以撤销。" +
                "运行日志只保存在本机，仅在你主动复制或导出时离开设备。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("开源协议与商标")
        Text(
            text = "本项目以 GPL-3.0 许可开源，源码与发行包见 github.com/chengzi2333/StayHiFi。" +
                "你可以自由使用、修改与再分发，但需保留同样的开源许可。" +
                "Shizuku、Stellar 及各手机/耳机品牌名称与商标归其各自所有者，此处仅作说明用途。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "继续使用即表示你已阅读并理解以上内容。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 捐赠页：微信赞赏码（可长按保存到相册）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DonatePage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        SubPageTopBar(title = "捐赠支持", onBack = onBack, windowInsets = noWindowInsets)
        // 说明文字留在顶部（跟着标题读），只把图片 + 图注放到剩余空间里居中。
        Text(
            text = "StayHiFi 一直免费开源。如果它帮到了你，可以请大肥鱼吃点 token，金额随意。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
        Image(
            painter = painterResource(R.drawable.donate_wechat),
            contentDescription = "微信赞赏码，长按可保存",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .fillMaxWidth()
                // nodpi 位图的内在高是按像素给的，只给宽度会被 contentScale=Fit 按高度缩回去；
                // 用宽高比锁死，图片才会真正撑满可用宽度。
                .aspectRatio(781f / 848f)
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        scope.launch {
                            val saved = withContext(Dispatchers.IO) { saveDonationQr(context) }
                            Toast.makeText(
                                context,
                                if (saved) "已保存到相册" else "保存失败，可截图本页",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ),
        )
        Text(
            text = "可长按保存图片，或截图本页到微信赞赏",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        }
    }
}

/** 把赞赏码写进系统相册（API 29+ 走 MediaStore，无需存储权限）。 */
private fun saveDonationQr(context: android.content.Context): Boolean {
    return try {
        val bitmap = android.graphics.BitmapFactory.decodeResource(
            context.resources,
            R.drawable.donate_wechat,
        ) ?: return false
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "stayhifi-donate.jpg")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_PICTURES + "/StayHiFi",
            )
        }
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ) ?: return false
        val stream = context.contentResolver.openOutputStream(uri) ?: return false
        stream.use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }
        true
    } catch (t: Throwable) {
        false
    }
}

/** 分组标签：小号、次要色、无背景（GKD 设置页同款）。 */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 2.dp, top = 16.dp, bottom = 2.dp),
    )
}

/**
 * 设置行（全项目统一样式）：标题 + 可选副标 + 尾部箭头/控件 + 行间 0.5dp 分隔线。
 * 尺寸规范见 AGENTS.md：行高 ≥56dp，尾部控件尺寸一致，不使用一卡一行的卡片式列表。
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    divider: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 2.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                trailing()
            } else if (onClick != null) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (divider) {
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
private fun NavSettingRow(
    title: String,
    subtitle: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) = SettingRow(title = title, subtitle = subtitle, onClick = onClick, divider = divider)

@Composable
private fun SwitchSettingRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    divider: Boolean = true,
    onChecked: (Boolean) -> Unit,
) = SettingRow(
    title = title,
    subtitle = subtitle,
    onClick = { onChecked(!checked) },
    divider = divider,
    trailing = { Switch(checked = checked, onCheckedChange = onChecked) },
)

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

/**
 * v1.2.4 分层说明页（用户拍板）：把「读不到实际传输」的已取证事实写透，
 * 让用户自助理解边界，而不是来问作者。内容只写有证据的机型结论，不推测。
 */
@Composable
private fun RealtimeInfoPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SubPageTopBar(
            title = "实际传输说明",
            onBack = onBack,
            windowInsets = noWindowInsets,
        )
        Text(
            text = "「实际传输」显示的是耳机此刻的真实传输码率。它只能来自手机系统的回报，App 无法绕过系统自行测量。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("为什么有的机型看不到")
        Text(
            text = "· vivo / iQOO：系统回报码率，可以正常显示。\n" +
                "· 红魔 / 努比亚：系统回报码率，可以正常显示（nubia Z80 Ultra 实测）。\n" +
                "· ColorOS（OPPO / 一加）搭配 LHDC：音频在 DSP 芯片里完成编码，蓝牙栈自身都接触不到码流，系统没有数据可报。这是系统设计，不是耳机坏了，也不是设置问题。（2026-11 真机取证）\n" +
                "· 部分小米机型：系统没有提供该编码器的回报数据段。\n" +
                "· ColorOS 搭配 LDAC 能否显示，仍在验证中。\n" +
                "· 荣耀：目前只有机型名映射，没有任何传输层实测，结论待定。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("读不到会影响音质吗")
        Text(
            text = "不会。这一行只是观测窗口；采样率、位深、码率档的设置照常生效。",
            style = MaterialTheme.typography.bodyMedium,
        )
        SectionTitle("想帮忙改进")
        Text(
            text = "如果你的机型与上面描述不符，欢迎到 设置 › 运行日志 复制日志反馈（酷安 @一般社畜橙子 / QQ 群 ${QqGroup.GROUP_NUMBER}），新机型证据会用于改进检测。",
            style = MaterialTheme.typography.bodyMedium,
        )
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
