package com.chengzi.btmax

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.chengzi.btmax.ICodecService
import com.chengzi.btmax.codec.CodecConfig
import com.chengzi.btmax.codec.DeviceProfile
import com.chengzi.btmax.data.ProfileRepository
import com.chengzi.btmax.data.SettingsRepository
import com.chengzi.btmax.shell.ShizukuShellProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Task 3/4 临时调试页（Task 5 重写替换）：
 * 按钮：绑定特权服务 / 读取配置 / 申请权限 / 自动拉满开关 / 录制设备档案。
 * 结果打到 TextView 与 logcat(tag=FullBars)。
 */
class MainActivity : ComponentActivity() {

    private lateinit var provider: ShizukuShellProvider
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var profileRepo: ProfileRepository
    private var service: ICodecService? = null
    private var logText by mutableStateOf("调试 UI：先连上耳机，再点「绑定服务」\n")
    private var autoEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = ShizukuShellProvider(this)
        settingsRepo = SettingsRepository(this)
        profileRepo = ProfileRepository(this)
        lifecycleScope.launch {
            autoEnabled = withContext(Dispatchers.IO) { settingsRepo.isAutoEnabled() }
        }
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
                    Button(onClick = { bindService() }) { Text("① 绑定特权服务") }
                    Button(onClick = { readConfig() }) { Text("② 读取当前配置") }
                    Button(onClick = { requestRuntimePermissions() }) { Text("③ 申请蓝牙/通知权限") }
                    Button(onClick = { toggleAutoApply() }) { Text("④ 自动拉满开关（当前: $autoEnabled）") }
                    Button(onClick = { recordProfile() }) { Text("⑤ 录制当前设备档案") }
                    Text(logText)
                }
            }
        }
    }

    override fun onDestroy() {
        provider.unbind()
        super.onDestroy()
    }

    private fun bindService() {
        lifecycleScope.launch {
            try {
                if (!provider.isRunning) {
                    log("Shizuku 未运行（详见 logcat 探测日志）")
                    return@launch
                }
                if (!provider.hasPermission &&
                    !provider.requestPermission(this@MainActivity)) {
                    log("Shizuku 权限未授予")
                    return@launch
                }
                log("正在绑定…")
                service = withContext(Dispatchers.IO) { provider.bind() }
                log("特权服务已绑定 ✓")
            } catch (t: Throwable) {
                log("绑定失败: $t")
            }
        }
    }

    private fun readConfig() {
        val svc = service
        if (svc == null) {
            log("请先绑定服务")
            return
        }
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.IO) { svc.readConfig("") }
                log(json)
            } catch (t: Throwable) {
                log("readConfig 失败: $t")
            }
        }
    }

    /** M1 临时入口（Task 5 收进 Onboarding）：申请 BLUETOOTH_CONNECT / POST_NOTIFICATIONS。 */
    private val permissionLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { result ->
            log("权限结果: $result")
        }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.isEmpty()) {
            log("权限已齐全")
            return
        }
        permissionLauncher.launch(wanted.toTypedArray())
        log("已弹权限申请: $wanted")
    }

    private fun toggleAutoApply() {
        lifecycleScope.launch {
            try {
                val next = !withContext(Dispatchers.IO) { settingsRepo.isAutoEnabled() }
                settingsRepo.setAutoEnabled(next)
                autoEnabled = next
                log("自动拉满开关 = $next")
            } catch (t: Throwable) {
                log("开关切换失败: $t")
            }
        }
    }

    /** 把第一台已连接设备的当前配置录制成档案（autoApply=true），供自动链路使用。 */
    private fun recordProfile() {
        val svc = service
        if (svc == null) {
            log("请先绑定服务")
            return
        }
        lifecycleScope.launch {
            try {
                val devicesJson = withContext(Dispatchers.IO) { svc.dumpDevices() }
                val arr = JSONArray(devicesJson)
                if (arr.length() == 0) {
                    log("无已连接 A2DP 设备")
                    return@launch
                }
                val mac = arr.getJSONObject(0).getString("mac")
                val name = arr.getJSONObject(0).getString("name")
                val stateJson = withContext(Dispatchers.IO) { svc.readConfig(mac) }
                val config = CodecConfig.fromJson(JSONObject(stateJson).getJSONObject("current").toString())
                withContext(Dispatchers.IO) {
                    profileRepo.save(DeviceProfile(
                        mac = mac, name = name, config = config,
                        source = DeviceProfile.Source.CAPTURED, autoApply = true,
                    ))
                }
                log("已录制档案: $name ($mac) ${config.summary()}")
            } catch (t: Throwable) {
                log("录制档案失败: $t")
            }
        }
    }

    private fun log(s: String) {
        logText += "[${
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
        }] $s\n"
        Log.i("FullBars", s)
    }
}
