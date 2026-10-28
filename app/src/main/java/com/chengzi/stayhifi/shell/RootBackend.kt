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

    /**
     * 绑定 RootCodecService（Task D5）：实现必须用显式 Intent + `RootService.bind`，
     * 且只在主线程调用（调用线程由 RootTransport 注入的 mainDispatcher 保证，§11.4.7）。
     * 返回句柄即表示 bind 请求已发出；连接结果经 [callbacks] 异步回调，回调线程为主线程。
     * 不使用 daemon mode，不调用 `RootService.stop`（§11.1/§11.4.7）。
     */
    fun bind(callbacks: RootBindCallbacks): RootBinding

    /** 幂等解绑 [binding]；调用线程同样由 RootTransport 的 mainDispatcher 保证。 */
    fun unbind(binding: RootBinding)
}

/** libsu ServiceConnection 回调的传输层抽象（binder 为 null 时 [RootBindCallbacks.onConnected] 传 null）。 */
interface RootBindCallbacks {
    fun onConnected(service: com.chengzi.stayhifi.ICodecService?)

    /** 远端死亡/断开（区别于主动 unbind，§11.4.8）。 */
    fun onDisconnected()
}

/** 一次 bind 的句柄；[RootBackend.unbind] 按句柄幂等解绑。 */
interface RootBinding {
    val id: Long
}

/** getShell 返回后的可观察事实。 */
data class RootShellObservation(
    val isRoot: Boolean,
)
