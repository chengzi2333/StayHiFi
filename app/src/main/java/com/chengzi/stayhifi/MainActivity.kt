package com.chengzi.stayhifi

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.service.quicksettings.TileService
import android.widget.Toast
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.abs
import kotlinx.coroutines.delay
import com.chengzi.stayhifi.codec.BtStateParser
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.DeviceProfile
import com.chengzi.stayhifi.codec.Masks
import com.chengzi.stayhifi.codec.VendorDiagnostics
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
import com.chengzi.stayhifi.ui.settings.UpdateDialog
import com.chengzi.stayhifi.ui.settings.UpdateDialogUi
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme
import com.chengzi.stayhifi.updater.UpdateChecker
import com.chengzi.stayhifi.util.DeviceNames
import com.chengzi.stayhifi.util.KeepAlive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    // Task 46：公开 API 本地即时判定音乐播放，替代需要两轮采样的 PCM 计数增量。
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    // ---- Onboarding 状态 ----
    private var onboardingState by mutableStateOf(OnboardingUiState())

    // ---- RecordWizard 状态 ----
    private var wizardVisible by mutableStateOf(false)
    private var wizardResult by mutableStateOf<CodecConfigSummary?>(null)
    // 结果卡片设备名 / 内联错误 / 成功态（Task 33）
    private var wizardDeviceLabel by mutableStateOf<String?>(null)
    private var wizardError by mutableStateOf<String?>(null)
    private var wizardSaved by mutableStateOf(false)
    // 重录入口锁定的目标 MAC（Task 33）：抓取时按 MAC 匹配，防止抓到另一台设备。
    private var wizardTargetMac: String? = null
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

    // ---- 自更新（Task 38） ----
    private var updateChecking by mutableStateOf(false)
    private var updateMessage by mutableStateOf<String?>(null)
    private var updateDialog by mutableStateOf<UpdateDialogUi?>(null)
    private var updateDownloadJob: Job? = null
    // Task 39：下载任务身份令牌——每次发起下载换一个新令牌，
    // 旧 job 残留的 catch/回调写 UI 前凭引用比较识别自己已过期
    private var updateDownloadToken: Any? = null

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
                    deviceLabel = wizardDeviceLabel,
                    error = wizardError,
                    saved = wizardSaved,
                    onCapture = ::onWizardCapture,
                    onSave = ::onWizardSave,
                    onDismiss = ::onWizardDismiss,
                )

                // Task 38：更新弹窗挂在根组合——自动检查弹窗不依赖用户正停留在设置页
                updateDialog?.let { dialog ->
                    UpdateDialog(
                        state = dialog,
                        onConfirm = ::onUpdateConfirm,
                        onDismiss = ::onUpdateDismiss,
                    )
                }
            }
        }

        // Task 38：自动更新检查——距上次 ≥24h 才联网，静默；仅新版本时弹窗（每进程最多一次）
        autoCheckUpdate()
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
            // Task 44：切回首页（index 0）时补一次 refreshHome——此前 refreshHome 只在
            // onResume/连接事件触发，页内 tab 切换不触发，开着歌切回首页实况格仍是旧值/「—」。
            // 跳过初始组合（onResume 已刷过），不与既有路径双跑。
            var prevPage by remember { mutableIntStateOf(-1) }
            LaunchedEffect(pagerState.currentPage) {
                val cur = pageOrder?.get(pagerState.currentPage) ?: pagerState.currentPage
                if (prevPage != -1 && cur == 0 && prevPage != 0) refreshHome()
                prevPage = cur
            }
            // Task 46：首页可见期间每 3s 补刷实况（原 5s，用户要求更快反映停播）。
            // 仅首页 tab 且 RESUMED 时跑：离开页面（isHomePage 翻 false 取消协程）/
            // 退后台（repeatOnLifecycle 挂起）即停；dumpsys 开销前台 3s 一次可接受（用户拍板）。
            // 无连接设备时跳过（ headsetName==null ），避免未授权分支每 3s 刷一条日志。
            val isHomePage = (pageOrder?.get(pagerState.currentPage) ?: pagerState.currentPage) == 0
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(isHomePage, lifecycleOwner) {
                if (!isHomePage) return@LaunchedEffect
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        delay(3_000)
                        if (homeState.headsetName != null) refreshHome()
                    }
                }
            }
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
                    // Task 30：空闲预组合相邻页——切 tab 时目标页已就绪，不把首组合砸在切换帧上。
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    when (pageOrder?.get(page) ?: page) {
                        0 -> HomeScreen(
                            state = homeState,
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
                                        hasOrigin = it.originConfig != null,
                                    )
                                },
                            ),
                            onAction = ::onDevicesAction,
                        )
                        2 -> SettingsScreen(
                            state = SettingsUiState(
                                autoApplyEnabled = autoApplyEnabled,
                                notifyResult = notifyResultOn,
                                notifyPersistent = notifyPersistentOn,
                                qsTile = qsTileOn,
                                batteryWhitelisted = KeepAlive.isBatteryWhitelisted(this@MainActivity),
                                checkingUpdate = updateChecking,
                                updateMessage = updateMessage,
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
                // Task 33：runBlocking + DataStore edit 的 IO 异常会抛到 lifecycleScope 闪退，包一层保护。
                try {
                    val profile = withContext(Dispatchers.IO) { profileRepo.profileFor(action.mac) }
                        ?: return@launch
                    withContext(Dispatchers.IO) {
                        profileRepo.save(profile.copy(autoApply = action.enabled))
                    }
                    LogStore.log("${profile.name} 自动应用 = ${action.enabled}")
                } catch (t: Throwable) {
                    LogStore.log("修改自动应用失败: $t")
                }
            }
            is DevicesAction.ReRecord -> {
                // 复用 RecordWizard；保存按 MAC 覆盖原档案。
                // Task 33：锁定目标 MAC，抓取时按 MAC 匹配而不是无脑取 dumpDevices 第一台。
                wizardResult = null
                wizardDeviceLabel = null
                wizardError = null
                wizardSaved = false
                wizardTargetMac = action.mac
                wizardVisible = true
            }
            is DevicesAction.RestoreDefault -> onRestoreDefault(action.mac)
        }
    }

    /**
     * Task 42：恢复默认参数（后悔药）——把首次拉满前的原始协商配置写回设备。
     * 成功后必须连带关掉这台设备的自动拉满（autoApply=false）：不关的话下次重连又被拉满，
     * 回退就白费了。失败不碰档案，记日志 + 轻提示。设备列表/首页状态靠现有 flow 自动刷新。
     */
    private fun onRestoreDefault(mac: String) {
        lifecycleScope.launch {
            try {
                val profile = withContext(Dispatchers.IO) { profileRepo.profileFor(mac) } ?: return@launch
                val origin = profile.originConfig ?: return@launch
                val ok = ApplyWorker(this@MainActivity).restoreDefault(mac, profile.name, origin)
                if (ok) {
                    withContext(Dispatchers.IO) {
                        profileRepo.save(profile.copy(autoApply = false))
                    }
                    LogStore.log("${profile.name} 已恢复默认参数，这台设备的自动拉满已连带关闭")
                    refreshHome()
                } else {
                    LogStore.log("${profile.name} 恢复默认参数失败")
                    Toast.makeText(this@MainActivity, "恢复默认参数失败，详见日志页", Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                LogStore.log("恢复默认参数失败: $t")
                Toast.makeText(this@MainActivity, "恢复默认参数失败，详见日志页", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSettingsAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetAutoApply -> lifecycleScope.launch {
                settingsRepo.setAutoEnabled(action.enabled)
                // FGS 主触发的拉起/停止与开关同步（Task 50 从首页迁入，行为不变）
                if (action.enabled) {
                    ListenerService.start(this@MainActivity)
                } else {
                    ListenerService.stop(this@MainActivity)
                }
                LogStore.log("自动拉满开关 = ${action.enabled}")
            }
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
            SettingsAction.DismissUpdateMessage -> updateMessage = null
        }
    }

    // ================= 自更新（Task 38） =================

    /**
     * 手动检查更新（设置页「检查更新」）：
     * Latest → 内联提示「已是最新版本」；NewAvailable → 弹窗；Error → 提示重试。
     * 手动检查同样刷新 24h 节流时间戳（刚查过就别让自动检查立刻再联网）。
     */
    private fun onCheckUpdate() {
        if (updateChecking) return
        updateChecking = true
        updateMessage = null
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                UpdateChecker.checkLatest(BuildConfig.VERSION_NAME)
            }
            runCatching {
                settingsRepo.setLastUpdateCheckMillis(System.currentTimeMillis())
            }
            updateChecking = false
            when (result) {
                UpdateChecker.Result.Latest -> updateMessage = "已是最新版本"
                is UpdateChecker.Result.NewAvailable -> {
                    updateDialog = UpdateDialogUi(
                        tag = result.tag,
                        notes = result.notes,
                        apkUrl = result.apkUrl,
                    )
                }
                is UpdateChecker.Result.Error -> {
                    LogStore.log("检查更新失败: ${result.cause}")
                    updateMessage = "检查失败，请确认能访问 GitHub 后重试"
                }
            }
        }
    }

    /**
     * 自动检查：仅 onboarded 后；距上次 ≥24h 才联网；Latest/Error 完全静默，
     * NewAvailable 才弹同款弹窗，且每进程最多弹一次（伴生对象标志，配置变更重建也算已弹）。
     */
    private fun autoCheckUpdate() {
        lifecycleScope.launch {
            try {
                if (!settingsRepo.onboarded.first()) return@launch
                val last = settingsRepo.lastUpdateCheckMillis.first()
                if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return@launch
                // 发起即记账：失败也节流，避免无网环境每次启动都白请求一次
                settingsRepo.setLastUpdateCheckMillis(System.currentTimeMillis())
                val result = withContext(Dispatchers.IO) {
                    UpdateChecker.checkLatest(BuildConfig.VERSION_NAME)
                }
                if (result is UpdateChecker.Result.NewAvailable && !autoDialogShownThisProcess) {
                    autoDialogShownThisProcess = true
                    updateDialog = UpdateDialogUi(
                        tag = result.tag,
                        notes = result.notes,
                        apkUrl = result.apkUrl,
                    )
                    LogStore.log("自动检查：发现新版本 ${result.tag}")
                }
            } catch (t: Throwable) {
                LogStore.log("自动检查更新失败: $t")
            }
        }
    }

    /** 「立即更新/重试」：应用内下载 APK 到应用专属 Download 目录，完成拉起系统安装器。 */
    private fun onUpdateConfirm() {
        val dialog = updateDialog ?: return
        if (dialog.downloading) return
        val dest = File(
            getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "StayHiFi-${dialog.tag}.apk",
        )
        updateDialog = dialog.copy(
            downloading = true, error = null, progressPercent = null, downloadedMb = 0f,
        )
        // Task 39：重试/重新发起前取消旧下载 job——阻塞式 IO 未必立刻响应取消，
        // 不取消的话旧 job 的 catch 与新 job 的进度回调会交叉覆写弹窗状态（进度回跳的根因）
        updateDownloadJob?.cancel()
        // Task 39：每次下载发新身份令牌；下行在主线程串行执行，旧 job 的 Main 回调必定看到新令牌
        val token = Any()
        updateDownloadToken = token
        updateDownloadJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                UpdateChecker.downloadApk(dialog.apkUrl, dest) { downloaded, total ->
                    // 进度直写 snapshot state（快照系统允许跨线程写），省掉每 64KB 一次切线程
                    updateDialog?.takeIf { it.downloading }?.let { cur ->
                        // Task 39：单调守卫——旧 job 残留回调/乱序写入只许前进不许倒退
                        val newPercent = if (total > 0) (downloaded * 100 / total).toInt() else null
                        updateDialog = cur.copy(
                            progressPercent = newPercent?.let { maxOf(cur.progressPercent ?: 0, it) }
                                ?: cur.progressPercent,
                            downloadedMb = maxOf(cur.downloadedMb, downloaded / 1048576f),
                        )
                    }
                }
                withContext(Dispatchers.Main) { installApk(dest) }
            } catch (t: Throwable) {
                // 失败删半成品，弹窗报错可重试（取消也走这里，无需单独处理）
                dest.delete()
                if (t !is kotlinx.coroutines.CancellationException) {
                    LogStore.log("下载更新包失败: $t")
                }
                withContext(Dispatchers.Main) {
                    // Task 39：身份校验——本 job 已被新下载顶替（或弹窗已取消重建），
                    // 过期 catch 只记日志不写 UI，防止旧任务把新任务的进度/状态回滚
                    if (updateDownloadToken !== token) {
                        LogStore.log("过期下载任务结束，忽略其状态写回: $t")
                        return@withContext
                    }
                    // 弹窗已被用户关闭则不复活
                    updateDialog?.let {
                        updateDialog = it.copy(
                            downloading = false,
                            error = if (t is kotlinx.coroutines.CancellationException) null
                                else "下载失败，请检查网络后重试",
                        )
                    }
                }
            }
        }
    }

    /** 取消/关闭弹窗：下载中则取消任务并删半成品文件。 */
    private fun onUpdateDismiss() {
        updateDialog?.takeIf { it.downloading }?.let { dialog ->
            updateDownloadJob?.cancel()
            File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "StayHiFi-${dialog.tag}.apk",
            ).delete()
        }
        updateDownloadJob = null
        // Task 39：取消下载同时作废令牌，被取消 job 迟到的 catch 不再碰 UI
        updateDownloadToken = null
        updateDialog = null
    }

    /** FileProvider 拉起系统安装器：同签名覆盖装数据不丢；不同签名系统直接拒装兜底。 */
    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
            updateDialog = null
            LogStore.log("更新包下载完成，已拉起系统安装器: ${file.name}")
        } catch (t: Throwable) {
            updateDialog = updateDialog?.copy(
                downloading = false,
                error = "无法拉起系统安装器，请到系统文件管理器手动安装",
            )
            LogStore.log("拉起安装器失败: $t")
        }
    }

    private fun onLogsAction(action: LogsAction) {
        when (action) {
            LogsAction.Copy -> copyLogs()
            LogsAction.Clear -> {
                LogStore.clear()
                resetVendorDiagnostics()
                ApplyWorker.resetVendorDiagnostics()
                btDiagLast = null
                logs = emptyList()
            }
            LogsAction.Back -> logsVisible = false
        }
    }

    /** 用户主动复制现有内存日志；不新增持久化或上传通道。 */
    private fun copyLogs() {
        val lines = LogStore.snapshot()
        if (lines.isEmpty()) {
            Toast.makeText(this, "暂无日志可复制", Toast.LENGTH_SHORT).show()
            return
        }
        val header = "StayHiFi v${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE}) · " +
            "${DeviceNames.marketModelWithAndroid()} · ${Build.DISPLAY}"
        try {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("StayHiFi 运行日志", (listOf(header) + lines).joinToString("\n"))
            )
            Toast.makeText(this, "已复制 ${lines.size} 条日志", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            LogStore.log("复制日志失败: $t")
            Toast.makeText(this, "复制失败，请查看日志页", Toast.LENGTH_SHORT).show()
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
                // Task 33：Onboarding 入口不带目标 MAC 约束，清空重录锁定的 MAC。
                wizardResult = null
                wizardDeviceLabel = null
                wizardError = null
                wizardSaved = false
                wizardTargetMac = null
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

    /** 关闭弹层：状态全清零，包括重录锁定的目标 MAC（Task 33）。 */
    private fun onWizardDismiss() {
        wizardVisible = false
        wizardResult = null
        wizardDeviceLabel = null
        wizardError = null
        wizardSaved = false
        wizardTargetMac = null
    }

    private fun onWizardCapture() {
        // 每次抓取先清内联错误（Task 33）
        wizardError = null
        lifecycleScope.launch {
            try {
                // 载体/授权判定内联展开（对照 ensureCarrierReady）：向导要区分两种人话文案。
                if (!withContext(Dispatchers.IO) { provider.isRunning }) {
                    wizardError = "Shizuku/Stellar 未运行，请先修复授权"
                    LogStore.log("录制向导：特权载体未运行")
                    return@launch
                }
                if (!withContext(Dispatchers.IO) { provider.hasPermission }) {
                    val ok = withContext(Dispatchers.IO) { provider.requestPermission(this@MainActivity) }
                    if (!ok) {
                        wizardError = "特权授权未授予"
                        LogStore.log("录制向导：特权授权未授予")
                        return@launch
                    }
                }
                val svc = withContext(Dispatchers.IO) { provider.bind() }
                val arr = JSONArray(withContext(Dispatchers.IO) { svc.dumpDevices() })
                if (arr.length() == 0) {
                    wizardError = "未检测到已连接的耳机，请先连接"
                    LogStore.log("录制向导：无已连接 A2DP 设备")
                    return@launch
                }
                // Task 33：重录入口已锁定目标 MAC，按 MAC 匹配而不是取 [0]；
                // 匹配不到说明目标耳机没连上，报错而不是抓别的设备。
                val targetMac = wizardTargetMac
                val idx = if (targetMac == null) {
                    0
                } else {
                    (0 until arr.length()).firstOrNull {
                        arr.getJSONObject(it).getString("mac").equals(targetMac, ignoreCase = true)
                    }
                }
                if (idx == null) {
                    wizardError = "目标耳机未连接"
                    LogStore.log("录制向导：目标耳机 $targetMac 不在已连接设备列表")
                    return@launch
                }
                val mac = arr.getJSONObject(idx).getString("mac")
                val name = arr.getJSONObject(idx).getString("name")
                val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                val obj = JSONObject(stateJson)
                if (obj.has("error")) {
                    wizardError = obj.optString("error")
                    LogStore.log("readConfig 返回 error: ${obj.optString("error")}")
                    return@launch
                }
                val current = CodecConfig.fromJson(obj.getJSONObject("current").toString())
                capturedMac = mac
                capturedName = name
                capturedConfig = current
                wizardResult = summaryToUi(current.summary())
                wizardDeviceLabel = "$name ($mac)"
                LogStore.log("录制向导抓到: $name ($mac) ${current.summary()}")
            } catch (t: Throwable) {
                wizardError = "抓取失败：${t.message ?: t.toString()}"
                LogStore.log("录制向导抓取失败: $t")
            }
        }
    }

    private fun onWizardSave() {
        val mac = capturedMac
        val config = capturedConfig
        if (mac == null || config == null) return
        lifecycleScope.launch {
            // Task 33：save 是 runBlocking + DataStore edit，IO 异常上抛会闪退，包 try/catch。
            try {
                withContext(Dispatchers.IO) {
                    profileRepo.save(DeviceProfile(
                        mac = mac,
                        name = capturedName ?: mac,
                        config = config,
                        source = DeviceProfile.Source.CAPTURED,
                        autoApply = true,
                        // 出处四字段（Task 27）：录制设备 / Android 版本 / ROM 构建号 / 时间。
                        // Task 28：录制设备用面客机型名（市场名键链），与状态卡机型行同源。
                        recordedOnModel = DeviceNames.marketModel(),
                        recordedAndroidVersion = Build.VERSION.RELEASE,
                        recordedBuildDisplay = Build.DISPLAY,
                        recordedAtMillis = System.currentTimeMillis(),
                    ))
                }
                LogStore.log("已保存录制档案: ${capturedName ?: mac} ($mac) ${config.summary()}")
                // 弹层内轻量成功态（Task 33），由用户点「完成」关闭。
                wizardSaved = true
            } catch (t: Throwable) {
                wizardError = "保存失败：${t.message ?: t.toString()}"
                LogStore.log("录制向导保存失败: $t")
            }
        }
    }

    // ================= Home =================

    private fun onHomeAction(action: HomeAction) {
        when (action) {
            HomeAction.ApplyNow -> onApplyNow()
            HomeAction.FixCarrier -> openCarrierManager()
        }
    }

    // Task 46：dumpBtState 诊断日志去重——3s 轮询下同一条失败日志会刷屏，
    // 只在诊断内容变化时记一条；传 null 表示恢复可读/进入未播放态（下次失败会重新记）。
    private var btDiagLast: String? = null
    private fun logBtDiag(msg: String?) {
        if (msg == btDiagLast) return
        btDiagLast = msg
        if (msg != null) LogStore.log(msg)
    }

    // Task 46：结构化抓取诊断沿用 logBtDiag 去重，包含反馈定位所需全部上下文。
    private fun realtimeDiag(current: CodecConfig, codecName: String, reason: String) {
        logBtDiag(
            "实际传输抓取诊断: 机型=${DeviceNames.marketModel()} " +
                "codecType=${current.codecType} 编码器=$codecName 原因=$reason"
        )
    }

    // 每次刷新递增代次；慢读取可并发，但只有最新轮次允许写首页状态与 PCM 前值。
    @Volatile private var refreshGeneration = 0L

    /** onResume / ApplyNow 后刷新：载体异常 → 红条；否则 bind 读第一台设备实况。 */
    private fun refreshHome() {
        lifecycleScope.launch {
            val gen = ++refreshGeneration
            val running = withContext(Dispatchers.IO) { provider.isRunning }
            if (!running) {
                if (gen != refreshGeneration) return@launch
                homeState = homeState.copy(
                    carrierAlert = "Shizuku/Stellar 未运行，点我修复",
                    headsetName = null,
                    codec = null,
                    rate = null,
                    bits = null,
                    quality = null,
                    realtimeKbps = null,
                    realtimeQualityMode = null,
                    realtimeNotStreaming = false,
                    fullBars = false,
                )
                connectedMac = null
                connectedName = null
                return@launch
            }
            if (gen != refreshGeneration) return@launch
            homeState = homeState.copy(carrierAlert = null)
            if (!withContext(Dispatchers.IO) { provider.hasPermission }) {
                LogStore.log("首页刷新：特权未授予，跳过设备读取")
                return@launch
            }
            try {
                // Task 46（Task 3 审查捎带裁定）：bind 同为 binder 调用，ColorOS 冻结载体
                // 时同样可能永久阻塞，与 dumpDevices/readConfig 同口径包 8s 超时；
                // 超时早退与「特权未授予」分支一致（直接 return，不清空已有状态）。
                // 超时必须在 IO 外层：取消的是等待线程，已发出的同步 binder 调用本身
                // 不可中断，IO 线程可能滞留。
                val svc = withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) { provider.bind() }
                }
                if (svc == null) {
                    LogStore.log("首页刷新：bind 超时(8s)，本轮跳过")
                    return@launch
                }
                // Task 45：ColorOS 可能冻结载体进程使 binder 永久阻塞（ListenerService
                // 浸泡实测已踩过），dumpDevices/readConfig 各包 8s 超时（同
                // ListenerService 兜底口径）；超时按「本轮读不到」走与无设备相同的
                // 清空/早退路径，避免首页协程挂起停旧数据被误诊为「参数显示不出来」。
                val devicesJson = withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) { svc.dumpDevices() }
                }
                if (devicesJson == null) {
                    LogStore.log("首页刷新：读取超时(8s)，本轮跳过")
                    if (gen != refreshGeneration) return@launch
                    homeState = homeState.copy(
                        headsetName = null, codec = null, rate = null,
                        bits = null, quality = null,
                        realtimeKbps = null, realtimeQualityMode = null,
                        realtimeNotStreaming = false, fullBars = false,
                    )
                    connectedMac = null
                    connectedName = null
                    return@launch
                }
                val arr = JSONArray(devicesJson)
                if (arr.length() == 0) {
                    if (gen != refreshGeneration) return@launch
                    homeState = homeState.copy(
                        headsetName = null, codec = null, rate = null,
                        bits = null, quality = null,
                        realtimeKbps = null, realtimeQualityMode = null,
                        realtimeNotStreaming = false, fullBars = false,
                    )
                    connectedMac = null
                    connectedName = null
                    return@launch
                }
                val mac = arr.getJSONObject(0).getString("mac")
                val name = arr.getJSONObject(0).getString("name")
                val stateJson = withTimeoutOrNull(8_000) {
                    withContext(Dispatchers.IO) { svc.readConfig(mac) }
                }
                if (stateJson == null) {
                    LogStore.log("首页刷新：读取超时(8s)，本轮跳过")
                    if (gen != refreshGeneration) return@launch
                    homeState = homeState.copy(
                        headsetName = null, codec = null, rate = null,
                        bits = null, quality = null,
                        realtimeKbps = null, realtimeQualityMode = null,
                        realtimeNotStreaming = false, fullBars = false,
                    )
                    connectedMac = null
                    connectedName = null
                    return@launch
                }
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
                // Task 46：播放判定先行——isMusicActive=false 时不再读 dump，首轮直接
                // 显示「未在播放」；true 才读取真实码率，避免 LHDC 粘性值首读闪烁。
                // 保留 Task 44 的段落精确名、-1 未流式语义与 Task 46 并行分支的 8s 超时保护。
                // （旧 dump 读取块已由下方 isMusicActive 版取代，合并时整段移除。）
                // Task 45：ColorOS 新栈 LHDC 在 vendor 段（0x10..0x1F），
                // 用 selectable 能力掩码佐证后再认 LHDC。
                val capsMask = Masks.capsRateMask(current.codecType, selectable)
                val codecName = Masks.codecName(current.codecType, capsMask)
                val isMusicActive = audioManager.isMusicActive
                var preciseCodec: String? = null
                var realtime = BtStateParser.realtimeDisplay(isMusicActive, state = null)
                // 保留本次 dump 原文，未知 vendor 编码器诊断复用同一次返回，不再开 dumpsys。
                var btDump: String? = null
                if (!isMusicActive) {
                    logBtDiag(null)
                } else {
                    try {
                        // Task 46 并行分支：dumpBtState 超时必须在 IO 外层（对齐
                        // ListenerService 范式；取消的是等待，阻塞中的 binder 不可中断）。
                        val btJson = withTimeoutOrNull(8_000) {
                            withContext(Dispatchers.IO) { svc.dumpBtState() }
                        }
                        if (btJson == null) {
                            realtimeDiag(current, codecName, "超时(8s)")
                        } else {
                            val btObj = JSONObject(btJson)
                            if (btObj.has("error")) {
                                realtimeDiag(current, codecName, "error JSON: ${btObj.optString("error")}")
                            } else {
                                btDump = btObj.optString("dump")
                                // 必须走 current+selectable 的佐证版名称；单参映射会把
                                // ColorOS 19 变成 vendor(19)，永远匹配不到 LHDC 段。
                                val parsed = BtStateParser.parse(
                                    btDump.orEmpty(), current, selectable,
                                )
                                realtime = BtStateParser.realtimeDisplay(isMusicActive = true, state = parsed)
                                if (parsed == null) {
                                    realtimeDiag(current, codecName, "无匹配段落")
                                } else {
                                    preciseCodec = parsed.codec
                                    if (!realtime.notStreaming && realtime.bitrateKbps == null) {
                                        realtimeDiag(current, codecName, "无码率字段")
                                    } else {
                                        logBtDiag(null)
                                    }
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        realtimeDiag(current, codecName, "读取异常: $t")
                    }
                }
                // Task 49：与 ApplyWorker 同口径的轻量厂商诊断，复用上方同一次 dump。
                // 19 号已识别 LHDC 也记录 raw cs1 + 实况证据；进程内按 mac+raw 签名去重。
                if (btDump != null && VendorDiagnostics.shouldLog(current.codecType, capsMask) &&
                    recordUnknownVendor(mac, current)
                ) {
                    LogStore.log(
                        "厂商编码器诊断: 机型=${DeviceNames.marketModel()} " +
                            "Android=${Build.VERSION.RELEASE} 构建=${Build.DISPLAY} " +
                            "mac=$mac capsRateMask=$capsMask current=${current.toJson()}"
                    )
                    val state = BtStateParser.parse(btDump, current, selectable)
                    if (state != null) {
                        LogStore.log("dumpsys证据: mac=$mac ${VendorDiagnostics.evidence(state)}")
                    } else {
                        LogStore.log("dumpsys证据: mac=$mac 无匹配段落")
                        btDump.lineSequence()
                            .filter { it.contains("State:") }
                            .take(12)
                            .forEach { LogStore.log("dumpsys段: $it") }
                    }
                }
                if (gen != refreshGeneration) return@launch
                homeState = homeState.copy(
                    headsetName = name,
                    // Task 44：编码器格优先用 dumpsys 段落精确名（如 "LHDC V5"），
                    // 读不到回落 Masks 映射名；CodecConfig 层语义不动（cs1 档位仍按 codecType）。
                    codec = preciseCodec ?: Masks.codecName(current.codecType, capsMask),
                    rate = Masks.rateHz(current.sampleRate),
                    bits = Masks.bitsDepth(current.bitsPerSample),
                    quality = Masks.qualityLabel(current.codecType, current.cs1),
                    realtimeKbps = realtime.bitrateKbps,
                    realtimeQualityMode = realtime.qualityMode,
                    realtimeNotStreaming = realtime.notStreaming,
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
        // Task 31 加载态：按下即进处理态，run 完成/异常 finally 回弹并刷新实况。
        homeState = homeState.copy(applying = true)
        lifecycleScope.launch {
            try {
                // 对抗审查 I2：手动入口传 force=true，不被 autoApply=false 静默拦截
                ApplyWorker(this@MainActivity).run(mac, name, force = true)
            } catch (t: Throwable) {
                LogStore.log("立即满血失败: $t")
            } finally {
                homeState = homeState.copy(applying = false)
                refreshHome()
            }
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
        /** Task 38：自动更新检查节流间隔（24h）。 */
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        /** Task 38：自动弹窗每进程最多一次（伴生对象，配置变更重建后仍记得已弹过）。 */
        private var autoDialogShownThisProcess = false

        /** Task 46：未知 vendor 编码器 raw 日志去重（进程内，签名 = mac + raw JSON）。 */
        private val loggedUnknownVendorSignatures = mutableSetOf<String>()

        /** 签名首次出现返回 true；refreshHome 可能并发（onResume/轮询/事件），伴生集合访问需同步。 */
        @Synchronized
        private fun recordUnknownVendor(mac: String, current: CodecConfig): Boolean =
            loggedUnknownVendorSignatures.add("$mac|${current.toJson()}")

        @Synchronized
        private fun resetVendorDiagnostics() {
            loggedUnknownVendorSignatures.clear()
        }
    }
}
