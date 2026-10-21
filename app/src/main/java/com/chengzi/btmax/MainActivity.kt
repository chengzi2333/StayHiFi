package com.chengzi.btmax

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.chengzi.btmax.codec.CodecConfig
import com.chengzi.btmax.codec.DeviceProfile
import com.chengzi.btmax.codec.Presets
import com.chengzi.btmax.data.ProfileRepository
import com.chengzi.btmax.data.SettingsRepository
import com.chengzi.btmax.shell.ShizukuShellProvider
import com.chengzi.btmax.trigger.ApplyWorker
import com.chengzi.btmax.ui.home.HomeAction
import com.chengzi.btmax.ui.home.HomeScreen
import com.chengzi.btmax.ui.home.HomeUiState
import com.chengzi.btmax.ui.onboarding.CarrierKind
import com.chengzi.btmax.ui.onboarding.CarrierUiState
import com.chengzi.btmax.ui.onboarding.CodecConfigSummary
import com.chengzi.btmax.ui.onboarding.OnboardingAction
import com.chengzi.btmax.ui.onboarding.OnboardingScreen
import com.chengzi.btmax.ui.onboarding.OnboardingUiState
import com.chengzi.btmax.ui.onboarding.RecordWizard
import com.chengzi.btmax.ui.theme.FullBarsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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
    private var presetOffer by mutableStateOf<PresetOffer?>(null)

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = ShizukuShellProvider(this)
        settingsRepo = SettingsRepository(this)
        profileRepo = ProfileRepository(this)

        setContent {
            FullBarsTheme {
                val onboarded: Boolean? by settingsRepo.onboarded
                    .collectAsState(initial = null)
                val autoApplyEnabled by settingsRepo.autoEnabled
                    .collectAsState(initial = true)

                when (onboarded) {
                    // DataStore 首读前的空白闪屏
                    null -> Box(Modifier.fillMaxSize())
                    false -> OnboardingEntry()
                    true -> HomeScreen(
                        state = homeState.copy(autoApplyEnabled = autoApplyEnabled),
                        onAction = ::onHomeAction,
                    )
                }

                RecordWizard(
                    visible = wizardVisible,
                    result = wizardResult,
                    onCapture = ::onWizardCapture,
                    onSave = ::onWizardSave,
                    onDismiss = {
                        wizardVisible = false
                        wizardResult = null
                    },
                )

                presetOffer?.let { offer ->
                    PresetOfferDialog(
                        offer = offer,
                        onApply = { onAcceptPreset(offer) },
                        onDismiss = { presetOffer = null },
                    )
                }
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

                // Review Focus #3：识别到已知设备 → 预置确认弹窗
                val preset = Presets.match(name, current.codecType)
                val hasProfile = withContext(Dispatchers.IO) { profileRepo.profileFor(mac) } != null
                if (preset != null && !hasProfile) {
                    presetOffer = PresetOffer(name = name, mac = mac, config = preset)
                }
            } catch (t: Throwable) {
                LogStore.log("刷新连接失败: $t")
            }
        }
    }

    private fun onOpenAutostartSettings() {
        // vivo OriginOS 自启动管理页；无 API 可查，按「用户已手动开启」乐观置位
        val vivo = Intent().setComponent(ComponentName(
            "com.vivo.permissionmanager",
            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ))
        val opened = try {
            startActivity(vivo)
            true
        } catch (t: Throwable) {
            LogStore.log("vivo 自启动页打不开（$t），回退本 App 详情页")
            try {
                startActivity(Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName"),
                ))
                true
            } catch (t2: Throwable) {
                LogStore.log("打开应用详情页也失败: $t2")
                false
            }
        }
        if (opened) {
            onboardingState = onboardingState.copy(autostartEnabled = true)
        }
    }

    private fun onOpenBatteryWhitelist() {
        try {
            startActivity(Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ))
        } catch (t: Throwable) {
            LogStore.log("请求电池白名单失败: $t")
        }
    }

    private fun refreshBatteryState() {
        val pm = getSystemService(PowerManager::class.java)
        onboardingState = onboardingState.copy(
            batteryWhitelistEnabled = pm.isIgnoringBatteryOptimizations(packageName),
        )
    }

    private fun onOnboardingFinish() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                settingsRepo.setOnboarded(true)
                settingsRepo.setAutoEnabled(true)
            }
            LogStore.log("Onboarding 完成：onboarded=true, autoEnabled=true")
            refreshHome()
        }
    }

    private fun onAcceptPreset(offer: PresetOffer) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                profileRepo.save(DeviceProfile(
                    mac = offer.mac,
                    name = offer.name,
                    config = offer.config,
                    source = DeviceProfile.Source.PRESET,
                    autoApply = true,
                ))
            }
            LogStore.log("已应用内置预设: ${offer.name} (${offer.mac}) ${offer.config.summary()}")
            presetOffer = null
        }
    }

    // ================= RecordWizard =================

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
                    configSummary = null,
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
                        headsetName = null, configSummary = null, fullBars = false,
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
                // 栈可能规范化 priority/cs1~cs4，满血按 codecType+采样率+位深三项判定
                val profile = withContext(Dispatchers.IO) { profileRepo.profileFor(mac) }
                val fullBars = profile != null &&
                    current.codecType == profile.config.codecType &&
                    current.sampleRate == profile.config.sampleRate &&
                    current.bitsPerSample == profile.config.bitsPerSample
                homeState = homeState.copy(
                    headsetName = name,
                    configSummary = current.summary(),
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
                ApplyWorker(this@MainActivity).run(mac, name)
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

    private data class PresetOffer(
        val name: String,
        val mac: String,
        val config: CodecConfig,
    )

    @Composable
    private fun PresetOfferDialog(
        offer: PresetOffer,
        onApply: () -> Unit,
        onDismiss: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("识别到 ${offer.name}") },
            text = { Text("这台设备有已知满血参数（${offer.config.summary()}），连上后自动应用，现在保存吗？") },
            confirmButton = {
                TextButton(onClick = onApply) { Text("应用") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("暂不需要") }
            },
        )
    }

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val STELLAR_PACKAGE = "roro.stellar.manager"
    }
}
