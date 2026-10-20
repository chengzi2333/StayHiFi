package com.chengzi.btmax

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
import com.chengzi.btmax.shell.ShizukuShellProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Task 3 临时调试页（Task 5 重写替换）：
 * 两个按钮 —— 绑定特权服务 + 读取第一台设备的 codec 配置，结果打到 TextView 与 logcat(tag=FullBars)。
 */
class MainActivity : ComponentActivity() {

    private lateinit var provider: ShizukuShellProvider
    private var service: ICodecService? = null
    private var logText by mutableStateOf("调试 UI：先连上耳机，再点「绑定服务」\n")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        provider = ShizukuShellProvider(this)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp).verticalScroll(rememberScrollState())) {
                    Button(onClick = { bindService() }) { Text("① 绑定特权服务") }
                    Button(onClick = { readConfig() }) { Text("② 读取当前配置") }
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

    private fun log(s: String) {
        logText += "[${
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(java.util.Date())
        }] $s\n"
        Log.i("FullBars", s)
    }
}
