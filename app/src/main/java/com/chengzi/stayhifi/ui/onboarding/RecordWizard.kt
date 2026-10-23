package com.chengzi.stayhifi.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.chengzi.stayhifi.ui.theme.StayHiFiTheme

/**
 * 抓到的参数摘要。全部用 String，不引用 codec 层类型，降低耦合。
 */
data class CodecConfigSummary(
    val codec: String,
    val sampleRate: String,
    val bitDepth: String,
) {
    /** 形如 "LHDC·192kHz·24bit"。 */
    fun display(): String = "$codec·$sampleRate·$bitDepth"
}

/**
 * 录制向导弹层（Task 27 后口径）：可选诊断工具——抓一次当前协商参数存档到设备页，
 * 不参与拉满决策，无需先调满再抓。
 * 纯无状态：可见性、结果、设备名、错误、成功态、三个回调全部由外部驱动（Task 33）。
 */
@Composable
fun RecordWizard(
    visible: Boolean,
    result: CodecConfigSummary?,
    deviceLabel: String?,
    error: String?,
    saved: Boolean,
    onCapture: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "录制参数模板",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WizardStep(index = 1, text = "连上耳机后直接点下面的「抓取当前参数」，抓取当前协商参数存档，用于设备页记录。")
                    WizardStep(index = 2, text = "确认抓到的耳机和参数没问题，保存为这台耳机的档案。")
                }

                // 失败路径内联展示（Task 33）：不再只写 LogStore 静默失败。
                if (error != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                if (saved) {
                    // 保存成功可见反馈（Task 33）：轻量成功态，只留「完成」出口。
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Text(
                            text = "已保存为这台耳机的档案",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }

                if (result != null && !saved) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "抓到了：",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            // 结果卡片带设备名（Task 33）：保存前让用户确认抓的是哪台。
                            if (deviceLabel != null) {
                                Text(
                                    text = deviceLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                            Text(
                                text = result.display(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                if (!saved) {
                    Button(
                        onClick = onCapture,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                    ) { Text(if (result == null) "抓取当前参数" else "重新抓取") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (saved) {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("完成")
                        }
                    } else {
                        TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("取消")
                        }
                        if (result != null) {
                            TextButton(onClick = onSave, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("保存为这台耳机的模板")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStep(index: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true, name = "录制向导-浅色")
@Composable
private fun RecordWizardPreviewLight() {
    StayHiFiTheme(dynamicColor = false) {
        RecordWizard(
            visible = true,
            result = CodecConfigSummary(codec = "LHDC", sampleRate = "192kHz", bitDepth = "24bit"),
            deviceLabel = "OPPO Enco X3 (38:F8:89:01:23:45)",
            error = null,
            saved = false,
            onCapture = {},
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "录制向导-深色")
@Composable
private fun RecordWizardPreviewDark() {
    StayHiFiTheme(darkTheme = true, dynamicColor = false) {
        RecordWizard(
            visible = true,
            result = null,
            deviceLabel = null,
            error = "未检测到已连接的耳机，请先连接",
            saved = false,
            onCapture = {},
            onSave = {},
            onDismiss = {},
        )
    }
}
