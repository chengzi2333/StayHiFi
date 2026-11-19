package com.chengzi.stayhifi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * v1.2.5：新手教程结束后的「首次使用说明」一次性弹窗。
 *
 * 目的：新用户看到「实际传输 —」时，当场知道这是机型限制、不是 App 坏了。
 * 写「人话」——只讲哪个系统能显示、哪个不能，不出现 DSP/编码/码流这类实现细节；
 * 完整取证说明留在 设置 → 实际传输说明。
 *
 * 机型结论的证据强度（改文案前先看这里，别凭印象加机型）：
 *  - vivo / iQOO：多机型实测可读（OriginOS 回报码率）。
 *  - 红魔 / 努比亚：nubia Z80 Ultra 实测可读（2026-11-19 用户日志，400kbps·ABR）；
 *    红魔机型尚未单独实测，与 nubia 同厂栈，故合并成一行。
 *  - OPPO / 一加：ColorOS 搭配 LHDC 在 DSP 端编码，栈自身拿不到码流；LDAC 同样无数据。
 *  - 小米：部分机型无该段落。
 *  - 荣耀：目前只有机型名映射，无任何传输层实测 —— 只能写「还没确认」。
 *
 * 「不再提醒」默认勾选：确认过就不再打扰；取消勾选则本次只是关掉，下次启动还会再出现一次。
 */
@Composable
fun RealtimeIntroDialog(
    onDismiss: (remember: Boolean) -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = {
            // 点击外部/返回 = 本次关掉，不写「不再提醒」，下次启动还会来一次
            onDismiss(false)
        },
        title = { Text("首次使用说明") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "不同厂商的系统对音频参数支持不一样，实际传输码率也不是所有系统都能读到。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "目前各系统的实际情况：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "· vivo / iQOO：正常显示\n" +
                        "· 红魔 / 努比亚：正常显示\n" +
                        "· OPPO / 一加：显示「—」（含 LDAC）\n" +
                        "· 小米：部分机型显示「—」\n" +
                        "· 荣耀：还没确认",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "读不到只影响这个数字，不影响音质。音质能拉多高，耳机和手机都有份：" +
                        "连上会自动拉到这台设备的最高档，想微调就去首页「自定义音质」。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { rememberChoice = !rememberChoice }
                        .heightIn(min = 40.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = rememberChoice, onCheckedChange = null)
                    Text(
                        text = "不再提醒",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(rememberChoice) }) { Text("知道了") }
        },
    )
}
