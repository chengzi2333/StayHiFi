package com.chengzi.stayhifi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.chengzi.stayhifi.codec.CodecConfig
import com.chengzi.stayhifi.codec.CodecIdentity
import com.chengzi.stayhifi.codec.ManualSelection
import com.chengzi.stayhifi.codec.Masks

/**
 * 手动调参面板的数据面：当前生效配置 + 当前连接上报的 selectable 能力 +
 * 系统已裁决身份（[identity] 只佐证当前 codec 的家族/显示名，如 OPD 的 LHDC V3
 * caps 仅 48/96；跨 codec 无身份证据时 ManualSelection 保持保守，不裸猜）。
 */
data class ManualEditorInput(
    val current: CodecConfig,
    val selectable: List<CodecConfig>,
    val identity: CodecIdentity? = null,
)

/**
 * 「自定义音质」底部弹层：编码器/采样率/位深/码率四参数手调。
 * 可选项全部按当前连接 selectable 生成（[ManualSelection] 同条目证明）；
 * 当前连接未提供的档位仅置灰，不断言硬件不支持。应用/恢复自适应都只上抛
 * 目标配置，写入与读回验证归 MainActivity/ApplyWorker。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ManualEditorSheet(
    input: ManualEditorInput,
    applying: Boolean,
    onApply: (CodecConfig) -> Unit,
    onRestoreAdaptive: (CodecConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = input.current
    val selectable = input.selectable
    val identity = input.identity

    // 编码器选项来自 selectable；当前 codec 未被上报时补在最前（live 配置本身是证据）
    val codecOptions = remember(selectable, current.codecType) {
        val fromSelectable = ManualSelection.codecOptions(selectable)
        if (current.codecType in fromSelectable) fromSelectable
        else listOf(current.codecType) + fromSelectable
    }

    var codec by remember { mutableIntStateOf(current.codecType) }
    var rate by remember { mutableIntStateOf(current.sampleRate) }
    var bits by remember { mutableIntStateOf(current.bitsPerSample) }
    // 码率档恒为显式选中的展示档位：同 codec 保留当前档，跨 codec 默认该家族自适应档，
    // 绝不让 cs1 静默落到档位表之外（Fix review）
    var qualityCs1 by remember {
        mutableStateOf(
            ManualSelection.defaultQualityCs1(current.codecType, selectable, current, identity),
        )
    }

    fun selectCodec(next: Int) {
        codec = next
        rate = if (next == current.codecType && current.sampleRate in ManualSelection.RATE_OPTIONS) {
            current.sampleRate
        } else {
            ManualSelection.availableRates(next, selectable).maxOrNull() ?: 0
        }
        bits = if (next == current.codecType && current.bitsPerSample in ManualSelection.BITS_OPTIONS) {
            current.bitsPerSample
        } else {
            ManualSelection.availableBits(next, rate, selectable).maxOrNull() ?: 0
        }
        qualityCs1 = ManualSelection.defaultQualityCs1(next, selectable, current, identity)
    }

    fun selectRate(next: Int) {
        rate = next
        val proven = ManualSelection.availableBits(codec, next, selectable)
        val keepsLive = codec == current.codecType &&
            next == current.sampleRate && bits == current.bitsPerSample
        if (bits !in proven && !keepsLive) bits = proven.maxOrNull() ?: 0
    }

    fun rateEnabled(value: Int): Boolean =
        value in ManualSelection.availableRates(codec, selectable) ||
            (codec == current.codecType && value == current.sampleRate)

    fun bitsEnabled(value: Int): Boolean =
        value in ManualSelection.availableBits(codec, rate, selectable) ||
            (codec == current.codecType && rate == current.sampleRate && value == current.bitsPerSample)

    val qualityOptions = ManualSelection.qualityOptions(codec, selectable, current, identity)
    val target = ManualSelection.buildTarget(
        current = current, codecType = codec, rate = rate, bits = bits,
        qualityCs1 = qualityCs1, selectable = selectable,
    )
    val adaptive = ManualSelection.adaptiveTarget(current, selectable, identity)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "自定义音质",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "拉满后无声或杂音时，可在这里降档；灰色是手机或耳机暂不支持的选项。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            ChipSection(title = "编码器") {
                for (option in codecOptions) {
                    FilterChip(
                        selected = codec == option,
                        enabled = !applying,
                        onClick = { selectCodec(option) },
                        label = { Text(ManualSelection.codecLabel(option, selectable, identity)) },
                    )
                }
            }

            ChipSection(title = "采样率") {
                for (value in ManualSelection.RATE_OPTIONS) {
                    FilterChip(
                        selected = rate == value,
                        enabled = !applying && rateEnabled(value),
                        onClick = { selectRate(value) },
                        label = { Text(Masks.rateHz(value)) },
                    )
                }
            }

            ChipSection(title = "位深") {
                for (value in ManualSelection.BITS_OPTIONS) {
                    FilterChip(
                        selected = bits == value,
                        enabled = !applying && bitsEnabled(value),
                        onClick = { bits = value },
                        label = { Text(Masks.bitsDepth(value)) },
                    )
                }
            }

            if (qualityOptions.isEmpty()) {
                Text(
                    text = "码率：当前编码器没有已知码率档，保持系统协商",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                ChipSection(title = "码率") {
                    for (option in qualityOptions) {
                        FilterChip(
                            selected = qualityCs1 == option.cs1,
                            enabled = !applying,
                            onClick = { qualityCs1 = option.cs1 },
                            label = { Text(option.label) },
                        )
                    }
                }
            }

            Text(
                text = if (target != null) {
                    val qualityLabel = qualityCs1?.let {
                        Masks.qualityLabel(ManualSelection.familyOf(codec, selectable, identity), it)
                    }
                    val base = ManualSelection.codecLabel(codec, selectable, identity) +
                        "·" + Masks.rateHz(rate) + "·" + Masks.bitsDepth(bits)
                    "将应用：" + if (qualityLabel != null) "$base·$qualityLabel" else base
                } else {
                    "当前组合未被连接能力证明，无法应用"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (target != null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onRestoreAdaptive(adaptive) },
                    // 恒可用：通配写入 = 把 rate/bits/码率交回协议栈重协商，
                    // 不能用 matches 门控（通配目标对无码率档编码器恒匹配会锁死出口）
                    enabled = !applying,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("恢复自适应")
                }
                Button(
                    onClick = { target?.let(onApply) },
                    enabled = !applying && target != null,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (applying) "应用中…" else "应用")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}
