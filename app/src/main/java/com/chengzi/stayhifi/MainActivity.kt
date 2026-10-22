package com.chengzi.stayhifi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlin.math.abs
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.DeviceProfile
import com.chengzi.stayhifi.codec.Masks
import com.chengzi.stayhifi.data.ProfileRepository
import com.chengzi.stayhifi.data.SettingsRepository
import com.chengzi.stayhifi.notify.QsTileService
import com.chengzi.stayhifi.shell.ShizukuShellProvider
import com.chengzi.stayhifi.trigger.ApplyPolicy
import com.chengzi.stayhifi.trigger.ApplyWorker
import com.chengzi.stayhifi.trigger.HeadsetEvents
import com.chengzi.stayhifi.trigger.ListenerService
import com.chengzi.stayhifi.ui.devices.DevicesAction
import com.chengzi.stayhifi.ui.devices.DevicesScreen
import com.chengzi.stayhifi.ui.devices.DevicesUiState
import com.chengzi.stayhifi.ui.devices.DeviceItemUi
import com.chengzi.stayhifi.ui.home.HomeAction
import com.chengzi.stayhifi.ui.home.HomeScreen
import com.chengzi.stayhifi.ui.home.HomeUiState
import com.chengzi.stayhifi.ui.logs.LogsAction
import com.chengzi.stayhifi.ui.logs.LogsScreen
import com.chengzi.stayhifi.ui.logs.LogsUiState
import com.chengzi.stayhifi.ui.nav.DockDestination
import com.chengzi.stayhifi.ui.nav.NavigationDock
import com.chengzi.stayhifi.ui.nav.StayHiFiIcons
import com.chengzi.stayhifi.ui.onboarding.CarrierKind
import com.chengzi.stayhifi.ui.onboarding.CarrierUiState
import com.chengzi.stayhifi.ui.onboarding.CodecConfigSummary
import com.chengzi.stayhifi.ui.onboarding.OnboardingAction
import com.chengzi.stayhifi.ui.onboarding.OnboardingScreen
import com.chengzi.stayhifi.ui.onboarding.OnboardingUiState
import com.chengzi.stayhifi.ui.onboarding.RecordWizard
import com.chengzi.stayhifi.ui.settings.SettingsAction
import com.chengzi.stayhifi.ui.settings.SettingsScreen
import com.chengzi.stayhifi.ui.settings.SettingsUiState
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import com.chengzi.stayhifi.util.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Task 5b：正式入口。首次启动走五页 Onboarding，完成后进 Home。
 * 所有 binder/IPC 调用都在 IO 线程（[ShizukuShellProvider.bind] 是阻塞的）。
 */
class MainActivity : ComponentActivity() {

    private lateinit var provider: ShizukuShellProvider
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var profileRepo: ProfileRepository

    // ---- Onboarding 状态 ----
    private var onboardingState by mutableStateOf(OnboardingUiState())

    // ---- RecordWizard 状态 ----
    private var wizardVisible by mutableStateOf(false)
    private var wizardResult by mutableStateOf<CodecConfigSummary?>(null)
    private var capturedMac: String? = null
    private var capturedName: String? = null
    private var capturedConfig: CodecConfig? = null

    // ---- Home 状态 ----
    private var homeState by mutableStateOf(HomeUiState())
    private var connectedMac: String? = null
    private var connectedName: String? = null

    // ---- 底部导航 / 日志页（Task 6） ----
    private var selectedTab by mutableStateOf(0)
    private var logsVisible by mutableStateOf(false)
    private var logs by mutableStateOf<List<String>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = ShizukuShellProvider(this)
        settingsRepo = SettingsRepository(this)
        profileRepo = ProfileRepository(this)

        setContent {
            StayHiFiTheme {
                val onboarded: Boolean? by settingsRepo.onboarded
                    .collectAsState(initial = null)
                val autoApplyEnabled by settingsRepo.autoEnabled
                    .collectAsState(initial = true)

                when (onboarded) {
                    // DataStore 首读前的空白闪屏
                    null -> Box(Modifier.fillMaxSize())
                    false -> OnboardingEntry()
                    true -> MainTabs(autoApplyEnabled = autoApplyEnabled)
                }

                RecordWizard(
                    visible = wizardVisible,
                    result = wizardResult,
                    onOpenDevOptions = ::onOpenDevOptions,
                    onCapture = ::onWizardCapture,
                    onSave = ::onWizardSave,
                    onDismiss = {
                        wizardVisible = false
                        wizardResult = null
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 首次读完成前不触发任何 IPC
        lifecycleScope.launch {
            val onboarded = settingsRepo.onboarded.first()
            if (onboarded) {
                refreshHome()
                logs = LogStore.snapshot().asReversed()
            } else {
                refreshCarriers()
                refreshBatteryState()
            }
        }
    }

    override fun onDestroy() {
        provider.unbind()
        super.onDestroy()
    }

    // ================= 三 tab 主导航（Task 6） =================

    /** 首页 / 设备 / 设置 三 tab；日志页全屏覆盖，设置页进入。 */
    private val mainTabsDestinations = listOf(
        DockDestination(Icons.Default.Home, "首页"),
        DockDestination(StayHiFiIcons.Headphones, "设备"),
        DockDestination(Icons.Default.Settings, "设置"),
    )

    @Composable
    private fun MainTabs(autoApplyEnabled: Boolean) {
        val profileList by profileRepo.all.collectAsState(initial = emptyList())
        val notifyResultOn by settingsRepo.notifyResult.collectAsState(initial = false)
        val notifyPersistentOn by settingsRepo.notifyPersistent.collectAsState(initial = false)
        val qsTileOn by settingsRepo.qsTile.collectAsState(initial = false)

        // 耳机事件总线：连接/断开/拉满完成都 bump，首页随之刷新。
        // version=0 是初始值，跳过避免首帧与 onResume 的 refreshHome 重复刷。
        val headsetVersion by HeadsetEvents.version.collectAsState()
        LaunchedEffect(headsetVersion) {
            if (headsetVersion > 0L) refreshHome()
        }

        Scaffold(
            bottomBar = {
                if (!logsVisible) {
                    NavigationDock(
                        destinations = mainTabsDestinations,
                        selectedIndex = selectedTab,
                        onSelected = { selectedTab = it },
                    )
                }
            },
        ) { innerPadding ->
            // 翻页过渡（对照原作 _HomePageView，home.dart L238-374）：NeverScrollable + 300ms easeOut；
            // 跨 >1 页用 order 重排把当前页换到目标相邻位，只滑一页、结束后恢复。
            val pagerState = rememberPagerState(pageCount = { mainTabsDestinations.size })
            var pageOrder by remember { mutableStateOf<List<Int>?>(null) }
            LaunchedEffect(selectedTab) {
                val target = selectedTab
                val current = pageOrder?.get(pagerState.currentPage) ?: pagerState.currentPage
                if (pageOrder != null) {
                    pageOrder = null
                    pagerState.scrollToPage(current)
                }
                if (target == current) return@LaunchedEffect
                if (abs(target - current) > 1) {
                    // 3 tab 下只有 首页↔设置 走到：order[adjacent]=current 换内容映射，
                    // 先跳相邻位（视觉无变化）再动画一页（原作 L296-322）。
                    val adjacent = if (target > current) target - 1 else target + 1
                    pageOrder = List(mainTabsDestinations.size) { it }.toMutableList().apply {
                        this[adjacent] = current
                        this[current] = adjacent
                    }
                    pagerState.scrollToPage(adjacent)
                }
                pagerState.animateScrollToPage(target, animationSpec = tween(300, easing = EaseOut))
                pageOrder = null
            }
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (pageOrder?.get(page) ?: page) {
                        0 -> HomeScreen(
                            state = homeState.copy(autoApplyEnabled = autoApplyEnabled),
                            onAction = ::onHomeAction,
                        )
                        1 -> DevicesScreen(
                            state = DevicesUiState(
                                profileList.map {
                                    DeviceItemUi(
                                        mac = it.mac,
                                        name = it.name,
                                        configSummary = it.config.summary(),
                                        source = it.source,
                                        autoApply = it.autoApply,
                                        provenance = it.provenanceLine(),
                                    )
                                },
                            ),
                            onAction = ::onDevicesAction,
                        )
                        2 -> SettingsScreen(
                            state = SettingsUiState(
                                notifyResult = notifyResultOn,
                                notifyPersistent = notifyPersistentOn,
                                qsTile = qsTileOn,
                                batteryWhitelisted = KeepAlive.isBatteryWhitelisted(this@MainActivity),
                            ),
                            onAction = ::onSettingsAction,
                        )
                    }
                }
                if (logsVisible) {
                    LogsScreen(state = LogsUiState(logs), onAction = ::onLogsAction)
                }
            }
        }
    }

    private fun onDevicesAction(action: DevicesAction) {
        when (action) {
            is DevicesAction.SetAutoApply -> lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) { profileRepo.profileFor(action.mac) }
                    ?: return@launch
                withContext(Dispatchers.IO) {
                    profileRepo.save(profile.copy(autoApply = action.enabled))
                }
                LogStore.log("${profile.name} 自动应用 = ${action.enabled}")
            }
            is DevicesAction.ReRecord -> {
                // 复用 RecordWizard；保存按 MAC 覆盖原档案
                wizardResult = null
                wizardVisible = true
            }
        }
    }

    private fun onSettingsAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetNotifyResult -> lifecycleScope.launch {
                settingsRepo.setNotifyResult(action.enabled)
                LogStore.log("拉满结果通知 = ${action.enabled}")
            }
            is SettingsAction.SetNotifyPersistent -> lifecycleScope.launch {
                settingsRepo.setNotifyPersistent(action.enabled)
                LogStore.log("常驻通知参数详情 = ${action.enabled}")
            }
            is SettingsAction.SetQsTile -> lifecycleScope.launch {
                settingsRepo.setQsTile(action.enabled)
                TileService.requestListeningState(
                    this@MainActivity,
                    ComponentName(this@MainActivity, QsTileService::class.java),
                )
                LogStore.log("QS 磁贴 = ${action.enabled}")
            }
            SettingsAction.OpenAutostart -> onOpenAutostartSettings()
            SettingsAction.OpenBatteryWhitelist -> onOpenBatteryWhitelist()
            SettingsAction.OpenBackgroundPower -> onOpenBackgroundPower()
            SettingsAction.OpenLogs -> {
                logs = LogStore.snapshot().asReversed()
                logsVisible = true
            }
            SettingsAction.CheckUpdate -> onCheckUpdate()
        }
    }

    /** 纯浏览器跳 GitHub Releases，不申请 INTERNET 权限。 */
    private fun onCheckUpdate() {
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/chengzi2333/StayHiFi/releases"),
                ),
            )
        } catch (t: Throwable) {
            LogStore.log("打开 Releases 失败: $t")
        }
    }

    private fun onLogsAction(action: LogsAction) {
        when (action) {
            LogsAction.Clear -> {
                LogStore.clear()
                logs = emptyList()
            }
            LogsAction.Back -> logsVisible = false
        }
    }

    // ================= Onboarding =================

    /** Onboarding 组合入口：补回 M1 调试页承担的运行时权限申请，然后渲染五页引导。 */
    @Composable
    private fun OnboardingEntry() {
        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { result -> LogStore.log("运行时权限结果: $result") }
        LaunchedEffect(Unit) {
            val wanted = buildList {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.BLUETOOTH_CONNECT)
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
        }
        OnboardingScreen(state = onboardingState, onAction = ::onOnboardingAction)
    }

    private fun onOnboardingAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Back -> moveTaskToBack(true)
            OnboardingAction.Next -> Unit // 翻页由 OnboardingScreen 内部 pager 处理
            OnboardingAction.Finish -> onOnboardingFinish()
            is OnboardingAction.InstallCarrier -> onInstallCarrier(action.kind)
            is OnboardingAction.OpenCarrierGuide -> openPackageOrLog(carrierPackage(action.kind))
            is OnboardingAction.RequestCarrierPermission -> onRequestCarrierPermission()
            OnboardingAction.RefreshConnection -> onRefreshConnection()
            is OnboardingAction.SelectSource ->
                onboardingState = onboardingState.copy(sourceMode = action.mode)
            OnboardingAction.OpenAutostartSettings -> onOpenAutostartSettings()
            OnboardingAction.OpenBatteryWhitelist -> onOpenBatteryWhitelist()
            OnboardingAction.OpenBackgroundPower -> onOpenBackgroundPower()
            OnboardingAction.OpenRecordWizard -> {
                wizardResult = null
                wizardVisible = true
            }
        }
    }

    /** 两张卡共用同一 rikka 通道：running/granted 都按 provider 实际状态显示。 */
    private fun refreshCarriers() {
        lifecycleScope.launch {
            val shizukuInstalled = withContext(Dispatchers.IO) { isInstalled(SHIZUKU_PACKAGE) }
            val stellarInstalled = withContext(Dispatchers.IO) { isInstalled(STELLAR_PACKAGE) }
            val running = withContext(Dispatchers.IO) { provider.isRunning }
            val granted = withContext(Dispatchers.IO) { provider.hasPermission }
            onboardingState = onboardingState.copy(
                shizuku = CarrierUiState(
                    kind = CarrierKind.SHIZUKU,
                    installed = shizukuInstalled,
                    running = running,
                    granted = granted,
                ),
                stellar = CarrierUiState(
                    kind = CarrierKind.STELLAR,
                    installed = stellarInstalled,
                    running = running,
                    granted = granted,
                ),
            )
        }
    }

    private fun onInstallCarrier(kind: CarrierKind) {
        // 网页兜底：酷安搜索对应管理器名（Shizuku 亦可走 shizuku.rikka.app/download）
        val url = "https://www.coolapk.com/search?q=${carrierPackage(kind)}"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (t: Throwable) {
            LogStore.log("打开安装指引失败: $t")
        }
    }

    private fun onRequestCarrierPermission() {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { provider.requestPermission(this@MainActivity) }
            LogStore.log("请求特权授权结果: $ok")
            refreshCarriers()
        }
    }

    /** Connect 页「刷新」：bind + dumpDevices + readConfig；命中内置预设且无档案时弹确认。 */
    private fun onRefreshConnection() {
        lifecycleScope.launch {
            try {
                if (!ensureCarrierReady()) return@launch
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                if (arr.length() == 0) {
                    onboardingState = onboardingState.copy(
                        headsetConnected = false, headsetName = null,
                    )
                    LogStore.log("刷新连接：无已连接 A2DP 设备")
                    return@launch
                }
                val mac = arr.getJSONObject(0).getString("mac")
                val name = arr.getJSONObject(0).getString("name")
                val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                val obj = JSONObject(stateJson)
                if (obj.has("error")) {
                    LogStore.log("readConfig 返回 error: ${obj.optString("error")}")
                    return@launch
                }
                val current = CodecConfig.fromJson(obj.getJSONObject("current").toString())
                onboardingState = onboardingState.copy(headsetConnected = true, headsetName = name)
                LogStore.log("已连接: $name ($mac)，当前 ${current.summary()}")
            } catch (t: Throwable) {
                LogStore.log("刷新连接失败: $t")
            }
        }
    }

    private fun onOpenAutostartSettings() {
        if (KeepAlive.openVivoAutostart(this)) {
            onboardingState = onboardingState.copy(autostartEnabled = true)
        }
    }

    private fun onOpenBatteryWhitelist() {
        if (KeepAlive.openBatteryWhitelist(this)) {
            onboardingState = onboardingState.copy(
                batteryWhitelistEnabled = KeepAlive.isBatteryWhitelisted(this),
            )
        }
    }

    private fun onOpenBackgroundPower() {
        // 与自启动同策略：系统无 API 可查，打开成功即乐观视为已引导
        KeepAlive.openBackgroundPower(this)
    }

    private fun refreshBatteryState() {
        onboardingState = onboardingState.copy(
            batteryWhitelistEnabled = KeepAlive.isBatteryWhitelisted(this),
        )
    }

    private fun onOnboardingFinish() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                settingsRepo.setOnboarded(true)
                settingsRepo.setAutoEnabled(true)
            }
            LogStore.log("Onboarding 完成：onboarded=true, autoEnabled=true")
            // 对抗审查 C1：引导完成时进程早已存活，StayHiFiApp.onCreate / 开关切换 /
            // BootReceiver 三处拉起点都不会再触发；装机首个会话必须同步拉起 FGS，
            // 否则用户当场连耳机验证效果时自动拉满整体失效。
            ListenerService.start(this@MainActivity)
            refreshHome()
        }
    }

    // ================= RecordWizard =================

    private fun onOpenDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            LogStore.log("打开开发者选项失败: $e")
        }
    }

    private fun onWizardCapture() {
        lifecycleScope.launch {
            try {
                if (!ensureCarrierReady()) return@launch
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                if (arr.length() == 0) {
                    LogStore.log("录制向导：无已连接 A2DP 设备")
                    return@launch
                }
                val mac = arr.getJSONObject(0).getString("mac")
                val name = arr.getJSONObject(0).getString("name")
                val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                val obj = JSONObject(stateJson)
                if (obj.has("error")) {
                    LogStore.log("readConfig 返回 error: ${obj.optString("error")}")
                    return@launch
                }
                val current = CodecConfig.fromJson(obj.getJSONObject("current").toString())
                capturedMac = mac
                capturedName = name
                capturedConfig = current
                wizardResult = summaryToUi(current.summary())
                LogStore.log("录制向导抓到: $name ($mac) ${current.summary()}")
            } catch (t: Throwable) {
                LogStore.log("录制向导抓取失败: $t")
            }
        }
    }

    private fun onWizardSave() {
        val mac = capturedMac
        val config = capturedConfig
        if (mac == null || config == null) return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                profileRepo.save(DeviceProfile(
                    mac = mac,
                    name = capturedName ?: mac,
                    config = config,
                    source = DeviceProfile.Source.CAPTURED,
                    autoApply = true,
                    // 出处四字段（Task 27）：录制设备 / Android 版本 / ROM 构建号 / 时间。
                    recordedOnModel = Build.MODEL,
                    recordedAndroidVersion = Build.VERSION.RELEASE,
                    recordedBuildDisplay = Build.DISPLAY,
                    recordedAtMillis = System.currentTimeMillis(),
                ))
            }
            LogStore.log("已保存录制档案: ${capturedName ?: mac} ($mac) ${config.summary()}")
            wizardVisible = false
            wizardResult = null
        }
    }

    // ================= Home =================

    private fun onHomeAction(action: HomeAction) {
        when (action) {
            is HomeAction.SetAutoApply -> lifecycleScope.launch {
                settingsRepo.setAutoEnabled(action.enabled)
                // FGS 主触发的拉起/停止与开关同步
                if (action.enabled) {
                    ListenerService.start(this@MainActivity)
                } else {
                    ListenerService.stop(this@MainActivity)
                }
                LogStore.log("自动拉满开关 = ${action.enabled}")
            }
            HomeAction.ApplyNow -> onApplyNow()
            HomeAction.FixCarrier -> openCarrierManager()
        }
    }

    /** onResume / ApplyNow 后刷新：载体异常 → 红条；否则 bind 读第一台设备实况。 */
    private fun refreshHome() {
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { provider.isRunning }
            if (!running) {
                homeState = homeState.copy(
                    carrierAlert = "Shizuku/Stellar 未运行，点我修复",
                    headsetName = null,
                    codec = null,
                    rate = null,
                    bits = null,
                    quality = null,
                    fullBars = false,
                )
                connectedMac = null
                connectedName = null
                return@launch
            }
            homeState = homeState.copy(carrierAlert = null)
            if (!withContext(Dispatchers.IO) { provider.hasPermission }) {
                LogStore.log("首页刷新：特权未授予，跳过设备读取")
                return@launch
            }
            try {
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                if (arr.length() == 0) {
                    homeState = homeState.copy(
                        headsetName = null, codec = null, rate = null,
                        bits = null, quality = null, fullBars = false,
                    )
                    connectedMac = null
                    connectedName = null
                    return@launch
                }
                val mac = arr.getJSONObject(0).getString("mac")
                val name = arr.getJSONObject(0).getString("name")
                val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                val obj = JSONObject(stateJson)
                if (obj.has("error")) {
                    LogStore.log("首页 readConfig error: ${obj.optString("error")}")
                    return@launch
                }
                val current = CodecConfig.fromJson(obj.getJSONObject("current").toString())
                // Task 27 两态裁决：fullBars = 与 ApplyWorker 同一份 deriveTarget 判定
                // （readConfig 响应已含 selectable），四字段相等即"已达本机最高音质输出"。
                val selectable = obj.optJSONArray("selectable")?.let { arr ->
                    (0 until arr.length()).map {
                        CodecConfig.fromJson(arr.getJSONObject(it).toString())
                    }
                } ?: emptyList()
                val fullBars = ApplyPolicy.matches(
                    ApplyPolicy.deriveTarget(current, selectable), current,
                )
                homeState = homeState.copy(
                    headsetName = name,
                    codec = Masks.codecName(current.codecType),
                    rate = Masks.rateHz(current.sampleRate),
                    bits = Masks.bitsDepth(current.bitsPerSample),
                    quality = Masks.qualityLabel(current.codecType, current.cs1),
                    fullBars = fullBars,
                )
                connectedMac = mac
                connectedName = name
            } catch (t: Throwable) {
                LogStore.log("首页刷新失败: $t")
            }
        }
    }

    private fun onApplyNow() {
        val mac = connectedMac ?: return
        val name = connectedName
        lifecycleScope.launch {
            try {
                // 对抗审查 I2：手动入口传 force=true，不被 autoApply=false 静默拦截
                ApplyWorker(this@MainActivity).run(mac, name, force = true)
            } catch (t: Throwable) {
                LogStore.log("立即满血失败: $t")
            }
            refreshHome()
        }
    }

    /** 红条点击：优先跳已安装的管理器 App（Stellar 优先，其次 Shizuku）。 */
    private fun openCarrierManager() {
        val pkg = when {
            isInstalled(STELLAR_PACKAGE) -> STELLAR_PACKAGE
            isInstalled(SHIZUKU_PACKAGE) -> SHIZUKU_PACKAGE
            else -> null
        }
        if (pkg != null) {
            openPackageOrLog(pkg)
        } else {
            LogStore.log("Shizuku/Stellar 均未安装")
        }
    }

    // ================= 公共工具 =================

    /** 载体未运行时给日志；未授权时先申请。返回是否可安全 bind。 */
    private suspend fun ensureCarrierReady(): Boolean {
        if (!withContext(Dispatchers.IO) { provider.isRunning }) {
            LogStore.log("特权载体未运行")
            return false
        }
        if (!withContext(Dispatchers.IO) { provider.hasPermission }) {
            val ok = withContext(Dispatchers.IO) { provider.requestPermission(this@MainActivity) }
            if (!ok) {
                LogStore.log("特权授权未授予")
                return false
            }
        }
        return true
    }

    private fun isInstalled(pkg: String): Boolean = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    private fun openPackageOrLog(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            LogStore.log("找不到 $pkg 的启动入口")
            return
        }
        try {
            startActivity(intent)
        } catch (t: ActivityNotFoundException) {
            LogStore.log("打不开 $pkg: $t")
        }
    }

    private fun carrierPackage(kind: CarrierKind): String = when (kind) {
        CarrierKind.SHIZUKU -> SHIZUKU_PACKAGE
        CarrierKind.STELLAR -> STELLAR_PACKAGE
    }

    /** "LHDC·192kHz·24bit" → UI 摘要三元组；异常格式兜底 unknown。 */
    private fun summaryToUi(summary: String): CodecConfigSummary {
        val parts = summary.split("·")
        return CodecConfigSummary(
            codec = parts.getOrElse(0) { "unknown" },
            sampleRate = parts.getOrElse(1) { "unknown" },
            bitDepth = parts.getOrElse(2) { "unknown" },
        )
    }

    /** 档案出处四字段 → 设备页展示行；旧档案无出处时返回空（不显示）。 */
    private fun DeviceProfile.provenanceLine(): String {
        if (recordedAtMillis == 0L) return ""
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(recordedAtMillis))
        return listOf("记录于 $recordedOnModel", "Android $recordedAndroidVersion", recordedBuildDisplay, date)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val STELLAR_PACKAGE = "roro.stellar.manager"
    }
}
