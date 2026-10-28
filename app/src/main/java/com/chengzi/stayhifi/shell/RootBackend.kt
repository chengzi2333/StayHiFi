package com.chengzi.stayhifi.shell

/**
 * Task D4：Root 通道的底层能力抽象（设计 §11.4）。
 * 生产实现为 LibsuRootBackend（Task D5）；测试用 fake。
 * 接口只表达"可观察/可执行的最小事实"，不携带 UI、持久化或切换语义。
 */
interface RootBackend {

    /**
     * isAppGrantedRoot 的可观察三态（§11.4.2）：true/false/null（未知）。
     * 只读——绝不发起授权、绝不创建 shell；null 不得当作"未授权"。
     */
    fun grantedState(): Boolean?

    /**
     * 建立 root shell（libsu getShell；可能长时间阻塞，可能触发 Root 管理器授权弹窗）。
     * 只允许在用户显式点击后、由 RootTransport 单飞调度到专用 auth dispatcher 调用；
     * 后台路径永不调用。返回不等于 Root 成功：libsu 可能回落普通 sh（§11.4.1），
     * 必须再验证 [RootShellObservation.isRoot]。
     */
    fun openShell(): RootShellObservation
}

/** getShell 返回后的可观察事实。 */
data class RootShellObservation(
    val isRoot: Boolean,
)
