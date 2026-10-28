package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.LogStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 探测/日志来源（设计 §11.6）。 */
enum class ProviderSource { ONBOARDING, SETTINGS, BOOT, LISTENER, QS, APPLY }

/**
 * 通道选择的只读存储面（设计 §11.3）：[rawSelection] 异步恢复持久值，
 * [setRawSelection] 提交新选择。由 SettingsRepository 实现。
 */
interface ProviderSelectionStore {
    val rawSelection: kotlinx.coroutines.flow.Flow<String?>
    suspend fun setRawSelection(value: String)
}

/** 一次通道探测的结果。Root 通道额外带回可观察授权三态（只读，未验证为 null）。 */
data class ProviderProbe(
    val kind: ProviderKind,
    val isRunning: Boolean,
    val hasPermission: Boolean,
    /** Root 通道：isAppGrantedRoot 的可观察三态；非 Root 探测为 null（§11.4.2）。 */
    val rootGranted: Boolean? = null,
)

/**
 * Task D2：进程级通道协调器（设计 §11.3/§11.4.5）。
 *
 * 在注入的 application scope 异步 collect 持久值：首读完成前 [selectedProvider] /
 * [activeProvider] 均为 null，并阻止一切特权 IPC——不得先构造默认 Shizuku 再"纠正"，
 * 也不得在 Application/BootReceiver 主线程 runBlocking 首读。
 *
 * 探测边界：[probeSelected] 必须先 [awaitSelection]；
 * Shizuku 探测由 [shizukuFactory] 创建的 provider 回答 isRunning/hasPermission（绝不 bind）；
 * Root 探测只读注入的 [RootStatusSource]，业务层拿不到 factory，绝不创建 shell 或弹窗。
 * candidate session / generation lease 等切换事务由后续任务在此之上扩展。
 */
class ProviderCoordinator(
    applicationScope: CoroutineScope,
    store: ProviderSelectionStore,
    private val shizukuFactory: () -> ShellProvider,
    private val rootStatusSource: RootStatusSource = RootStatusSource { null },
    private val log: (String) -> Unit = { LogStore.log(it) },
) {

    /** Root 通道的可观察授权状态源：只读，绝不发起授权或创建 shell（§11.4.5）。 */
    fun interface RootStatusSource {
        fun granted(): Boolean?
    }

    private val _selectedProvider = MutableStateFlow<ProviderKind?>(null)
    val selectedProvider: StateFlow<ProviderKind?> = _selectedProvider.asStateFlow()

    private val _activeProvider = MutableStateFlow<ProviderKind?>(null)
    val activeProvider: StateFlow<ProviderKind?> = _activeProvider.asStateFlow()

    private val firstSelection = CompletableDeferred<ProviderKind>()
    private val loggedUnknownValues = mutableSetOf<String>()

    init {
        applicationScope.launch {
            store.rawSelection.collect { raw ->
                val kind = ProviderKind.fromStored(raw)
                if (raw != null && raw != ProviderKind.SHIZUKU.stored &&
                    raw != ProviderKind.ROOT.stored && loggedUnknownValues.add(raw)) {
                    log("未知 provider 值 '$raw'，回退 ${ProviderKind.SHIZUKU.stored}")
                }
                _selectedProvider.value = kind
                _activeProvider.value = kind
                if (!firstSelection.isCompleted) firstSelection.complete(kind)
            }
        }
    }

    /** 挂起至首读完成并返回所选通道；此后直接返回当前选择。 */
    suspend fun awaitSelection(): ProviderKind = firstSelection.await()

    /**
     * 探测当前所选通道的就绪态。必须先完成首读；
     * 返回前绝不 bind/创建 shell（§11.4.5 后台不得制造授权弹窗）。
     */
    suspend fun probeSelected(source: ProviderSource): ProviderProbe {
        val kind = awaitSelection()
        return when (kind) {
            ProviderKind.SHIZUKU -> {
                val provider = shizukuFactory()
                ProviderProbe(
                    kind = kind,
                    isRunning = provider.isRunning,
                    hasPermission = provider.hasPermission,
                )
            }
            ProviderKind.ROOT -> {
                val granted = rootStatusSource.granted()
                ProviderProbe(
                    kind = kind,
                    isRunning = false,
                    hasPermission = granted == true,
                    rootGranted = granted,
                )
            }
        }
    }
}
