package com.chengzi.btmax.ui.onboarding

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
import com.chengzi.btmax.ui.theme.FullBarsTheme

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
 * 录制向导弹层：教用户去开发者选项调好参数、回来点抓取、确认保存为模板。
 * 纯无状态：可见性、结果、三个回调全部由外部驱动。
 */
@Composable
fun RecordWizard(
    visible: Boolean,
    result: CodecConfigSummary?,
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
                    WizardStep(index = 1, text = "连上耳机，进入系统「开发者选项」，把蓝牙音频编码器、采样率、位深调到最高。")
                    WizardStep(index = 2, text = "调好后回到这里，点下面的「抓取当前参数」。")
                    WizardStep(index = 3, text = "确认抓到的参数没问题，保存为这台耳机的专属模板。")
                }

                if (result != null) {
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
                            Text(
                                text = result.display(),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                Button(
                    onClick = onCapture,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(if (result == null) "抓取当前参数" else "重新抓取") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
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
    FullBarsTheme(dynamicColor = false) {
        RecordWizard(
            visible = true,
            result = CodecConfigSummary(codec = "LHDC", sampleRate = "192kHz", bitDepth = "24bit"),
            onCapture = {},
            onSave = {},
            onDismiss = {},
        )
    }
}

@Preview(showBackground = true, name = "录制向导-深色")
@Composable
private fun RecordWizardPreviewDark() {
    FullBarsTheme(darkTheme = true, dynamicColor = false) {
        RecordWizard(
            visible = true,
            result = null,
            onCapture = {},
            onSave = {},
            onDismiss = {},
        )
    }
}
