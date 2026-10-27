package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.ApiReadState
import com.chengzi.stayhifi.codec.SupportedCodecType
import java.lang.reflect.InvocationTargetException

/**
 * Task 3：supported types 目录的服务侧缓存 / 限频 / 失效。
 *
 * 契约（见 task-3-brief）：
 *  - OK / UNAVAILABLE 结果缓存 60,000ms；NULL / EMPTY / ERROR 缓存 10,000ms；
 *  - 缓存锁只保护状态读写，loader（binder IO）在锁外执行；
 *  - 单飞：in-flight 时并发 get 不再发 binder——有 stale 返回 stale，
 *    无值返回 UNAVAILABLE("refresh-in-flight") 且不等待；
 *  - [invalidate] 立即使缓存与进行中的 loader 结果失效（generation 计数）；
 *  - loader 抛出的异常只转成 ERROR 结果，不损坏缓存状态（inFlight 必复位）。
 *
 * 生产环境用 `SystemClock.elapsedRealtime` 注入 [nowMs]（单调时钟，墙钟回拨安全）。
 */
internal class SupportedTypesCatalogCache(private val nowMs: () -> Long) {

    private val lock = Any()
    private var cached: ReflectedValue<List<SupportedCodecType>>? = null
    private var cachedAtMs: Long = 0L
    private var inFlight = false

    /** invalidate 代数：loader 启动时捕获，完成时不匹配则丢弃结果（invalidate 后不残留旧目录）。 */
    private var generation = 0L

    fun get(loader: () -> ReflectedValue<List<SupportedCodecType>>): ReflectedValue<List<SupportedCodecType>> {
        val gen: Long
        synchronized(lock) {
            val c = cached
            if (c != null) {
                if (nowMs() - cachedAtMs <= ttlOf(c.state)) return c          // TTL 内直接复用
                if (inFlight) return c                                        // stale-if-present：刷新中先返回旧目录
            } else if (inFlight) {
                return ReflectedValue(ApiReadState.UNAVAILABLE, error = "refresh-in-flight")
            }
            inFlight = true
            gen = generation
        }
        // ── 锁外执行 loader：binder IO 禁止进入上面的 synchronized 块 ──
        val result = try {
            loader()
        } catch (t: Throwable) {
            ReflectedValue<List<SupportedCodecType>>(ApiReadState.ERROR, error = errorText(t))
        }
        synchronized(lock) {
            inFlight = false
            if (gen == generation) {
                cached = result
                cachedAtMs = nowMs()
            }
        }
        return result
    }

    /** 立即失效：清空缓存；进行中的 loader 完成后其结果也被丢弃。 */
    fun invalidate() {
        synchronized(lock) {
            cached = null
            cachedAtMs = 0L
            generation++
        }
    }

    private fun ttlOf(state: ApiReadState): Long = when (state) {
        ApiReadState.OK, ApiReadState.UNAVAILABLE -> TTL_OK_MS
        else -> TTL_FAILURE_MS
    }

    private fun errorText(t: Throwable): String {
        var cause = t
        while (cause is InvocationTargetException && cause.cause != null) {
            cause = cause.cause!!
        }
        return cause.javaClass.simpleName + ": " + cause.message
    }

    companion object {
        private const val TTL_OK_MS = 60_000L        // OK / UNAVAILABLE
        private const val TTL_FAILURE_MS = 10_000L   // NULL / EMPTY / ERROR
    }
}
