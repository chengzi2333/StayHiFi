package com.chengzi.stayhifi.shell

import com.chengzi.stayhifi.codec.ApiReadState
import com.chengzi.stayhifi.codec.SupportedCodecType
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3：`SupportedTypesCatalogCache` 服务侧缓存 / 限频 / 失效。
 * 可控 clock + 可控 loader：
 *  - OK / UNAVAILABLE 60,000ms 内只加载一次，超过 TTL 才重试；
 *  - NULL / EMPTY / ERROR 10,000ms 内只加载一次，超过 TTL 才重试；
 *  - 单飞：首个 loader 阻塞时并发 get 不得再发 binder，有 stale 返回 stale，
 *    无值返回 UNAVAILABLE(refresh-in-flight) 且不等待；
 *  - invalidate() 立即失效；invalidate 进行中的 loader 结果被丢弃；
 *  - loader 抛异常只变成 ERROR 结果，缓存状态（inFlight/缓存条目）不损坏。
 */
class SupportedTypesCatalogCacheTest {

    private class Clock {
        var now = 0L
    }

    private val catalog = listOf(SupportedCodecType(nativeType = 0, codecId = 1L, codecName = "SBC"))

    private fun ok(value: List<SupportedCodecType> = catalog) =
        ReflectedValue(ApiReadState.OK, value)

    private fun loaderOf(
        result: ReflectedValue<List<SupportedCodecType>>,
        count: AtomicInteger,
    ): () -> ReflectedValue<List<SupportedCodecType>> = {
        count.incrementAndGet()
        result
    }

    // ─────────────────────────── TTL 限频 ───────────────────────────

    @Test
    fun okResultCachedFor60s() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val count = AtomicInteger()
        val loader = loaderOf(ok(), count)

        assertEquals(ApiReadState.OK, cache.get(loader).state)
        clock.now = 59_999
        cache.get(loader)
        assertEquals("60s TTL 内不得重复加载", 1, count.get())

        clock.now = 60_001
        cache.get(loader)
        assertEquals("超过 60s TTL 必须重试", 2, count.get())
    }

    @Test
    fun unavailableResultCachedFor60s() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val count = AtomicInteger()
        val unavailable = ReflectedValue<List<SupportedCodecType>>(ApiReadState.UNAVAILABLE)
        val loader = loaderOf(unavailable, count)

        assertEquals(ApiReadState.UNAVAILABLE, cache.get(loader).state)
        clock.now = 60_001
        cache.get(loader)
        assertEquals(2, count.get())
    }

    @Test
    fun nullEmptyErrorResultsCachedFor10s() {
        val states = listOf(
            ReflectedValue<List<SupportedCodecType>>(ApiReadState.NULL),
            ReflectedValue(ApiReadState.EMPTY, emptyList<SupportedCodecType>()),
            ReflectedValue<List<SupportedCodecType>>(ApiReadState.ERROR, error = "boom"),
        )
        for (r in states) {
            val clock = Clock()
            val cache = SupportedTypesCatalogCache { clock.now }
            val count = AtomicInteger()
            val loader = loaderOf(r, count)

            assertEquals(r.state, cache.get(loader).state)
            clock.now = 9_999
            cache.get(loader)
            assertEquals("state=${r.state} 10s TTL 内不得重复加载", 1, count.get())

            clock.now = 10_001
            cache.get(loader)
            assertEquals("state=${r.state} 超过 10s TTL 必须重试", 2, count.get())
        }
    }

    // ─────────────────────────── 并发单飞 ───────────────────────────

    @Test
    fun concurrentCallReturnsStaleWhileRefreshInFlight() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val count = AtomicInteger()
        val staleList = listOf(SupportedCodecType(nativeType = 0, codecId = 1L, codecName = "SBC"))
        val freshList = listOf(SupportedCodecType(nativeType = 1, codecId = 2L, codecName = "LDAC"))

        cache.get(loaderOf(ReflectedValue(ApiReadState.OK, staleList), count))
        assertEquals(1, count.get())
        clock.now = 60_001   // OK 目录刚过期 → 触发刷新

        val started = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val loader: () -> ReflectedValue<List<SupportedCodecType>> = {
            count.incrementAndGet()
            started.countDown()
            blocker.await(5, TimeUnit.SECONDS)
            ReflectedValue(ApiReadState.OK, freshList)
        }
        val aResult = AtomicReference<ReflectedValue<List<SupportedCodecType>>>()
        val a = Thread { aResult.set(cache.get(loader)) }.apply { start() }
        assertTrue("loader 应在 5s 内启动", started.await(5, TimeUnit.SECONDS))

        // 并发 get：不等待、不再发 binder，直接拿 stale 目录
        val b = cache.get(loader)
        assertEquals(ApiReadState.OK, b.state)
        assertEquals(staleList, b.value)
        assertEquals("并发调用不得再发 binder", 2, count.get())

        blocker.countDown()
        a.join(5_000)
        assertEquals(freshList, aResult.get().value)
        // 刷新完成后新 TTL 内复用
        cache.get(loader)
        assertEquals(2, count.get())
    }

    @Test
    fun concurrentCallWithoutValueReturnsUnavailableImmediately() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val count = AtomicInteger()
        val started = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val loader: () -> ReflectedValue<List<SupportedCodecType>> = {
            count.incrementAndGet()
            started.countDown()
            blocker.await(5, TimeUnit.SECONDS)
            ok()
        }
        val a = Thread { cache.get(loader) }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        val b = cache.get(loader)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertEquals(ApiReadState.UNAVAILABLE, b.state)
        assertEquals("refresh-in-flight", b.error)
        assertEquals("不得再发 binder", 1, count.get())
        assertTrue("不得等待 in-flight loader（实际 ${elapsedMs}ms）", elapsedMs < 2_000)

        blocker.countDown()
        a.join(5_000)
    }

    // ─────────────────────────── 失效 ───────────────────────────

    @Test
    fun invalidateForcesImmediateReload() {
        val cache = SupportedTypesCatalogCache { 0L }
        val count = AtomicInteger()
        val first = listOf(SupportedCodecType(nativeType = 0, codecId = 1L, codecName = "SBC"))
        val second = listOf(SupportedCodecType(nativeType = 1, codecId = 2L, codecName = "LDAC"))

        assertEquals(first, cache.get {
            count.incrementAndGet(); ReflectedValue(ApiReadState.OK, first)
        }.value)
        cache.invalidate()
        assertEquals(second, cache.get {
            count.incrementAndGet(); ReflectedValue(ApiReadState.OK, second)
        }.value)
        assertEquals(2, count.get())
    }

    @Test
    fun invalidateDuringInFlightDiscardsLoaderResult() {
        val cache = SupportedTypesCatalogCache { 0L }
        val count = AtomicInteger()
        val started = CountDownLatch(1)
        val blocker = CountDownLatch(1)
        val staleGenList = listOf(SupportedCodecType(nativeType = 1, codecId = 2L, codecName = "LDAC"))
        val loader: () -> ReflectedValue<List<SupportedCodecType>> = {
            count.incrementAndGet()
            started.countDown()
            blocker.await(5, TimeUnit.SECONDS)
            ReflectedValue(ApiReadState.OK, staleGenList)
        }
        val aResult = AtomicReference<ReflectedValue<List<SupportedCodecType>>>()
        val a = Thread { aResult.set(cache.get(loader)) }.apply { start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        cache.invalidate()   // loader 仍在飞行：其结果必须被丢弃，且不得返回给原调用方
        blocker.countDown()
        a.join(5_000)

        // 旧代结果不得流出：原调用方只拿到瞬态 UNAVAILABLE，而不是被 invalidate 取代的目录
        assertEquals(ApiReadState.UNAVAILABLE, aResult.get().state)
        assertEquals("invalidated-during-load", aResult.get().error)

        // 结果被丢弃 → 下一次 get 必须重新加载且拿到全新目录
        val freshList = listOf(SupportedCodecType(nativeType = 2, codecId = 3L, codecName = "aptX"))
        val r = cache.get {
            count.incrementAndGet()
            ReflectedValue(ApiReadState.OK, freshList)
        }
        assertEquals(freshList, r.value)
        assertEquals(2, count.get())
    }

    // ─────────────────────────── loader 异常隔离 ───────────────────────────

    @Test
    fun loaderExceptionBecomesErrorAndKeepsCacheUsable() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val count = AtomicInteger()

        val r1 = cache.get {
            count.incrementAndGet()
            throw RuntimeException("binder dead")
        }
        assertEquals(ApiReadState.ERROR, r1.state)
        assertTrue(r1.error.orEmpty().contains("RuntimeException"))

        // ERROR 走 10s TTL：TTL 内复用 ERROR 结果，不重复 binder
        clock.now = 5_000
        val r2 = cache.get(loaderOf(ok(), count))
        assertEquals(ApiReadState.ERROR, r2.state)
        assertEquals(1, count.get())

        // 超过 TTL 后缓存仍正常工作（inFlight 已复位，可重载成功）
        clock.now = 20_000
        val r3 = cache.get(loaderOf(ok(), count))
        assertEquals(ApiReadState.OK, r3.state)
        assertEquals(2, count.get())
    }

    // ─────────────────────────── adapter ON/OFF 跳变门控 ───────────────────────────

    @Test
    fun continuousOffReadsKeepFailureRateLimit() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val gate = AdapterStateInvalidator(cache)
        val count = AtomicInteger()
        val offResult = ReflectedValue<List<SupportedCodecType>>(ApiReadState.NULL)

        // 首次 OFF（跳变）：失效一次，随后加载 NULL 目录
        gate.onAdapterState(false)
        assertEquals(ApiReadState.NULL, cache.get(loaderOf(offResult, count)).state)
        assertEquals(1, count.get())

        // 连续 OFF 读取：不得再 invalidate，NULL 结果仍受 10s 限频
        clock.now = 5_000
        gate.onAdapterState(false)
        cache.get(loaderOf(offResult, count))
        assertEquals("连续 OFF 不得绕过 10s 限频", 1, count.get())

        clock.now = 11_000
        gate.onAdapterState(false)
        cache.get(loaderOf(offResult, count))
        assertEquals("超过 10s TTL 才重试", 2, count.get())
    }

    @Test
    fun offToOnTransitionForcesReloadWhileContinuousOffDoesNot() {
        val clock = Clock()
        val cache = SupportedTypesCatalogCache { clock.now }
        val gate = AdapterStateInvalidator(cache)
        val count = AtomicInteger()
        val offResult = ReflectedValue<List<SupportedCodecType>>(ApiReadState.NULL)

        gate.onAdapterState(true)
        cache.get(loaderOf(ok(), count))          // count=1，TTL 60s
        clock.now = 5_000

        gate.onAdapterState(false)                // ON→OFF 跳变：目录失效
        gate.onAdapterState(false)                // 连续 OFF：不得重复失效
        gate.onAdapterState(false)
        assertEquals(ApiReadState.NULL, cache.get(loaderOf(offResult, count)).state)
        assertEquals(2, count.get())

        clock.now = 8_000                         // OFF 期读取：NULL 结果 10s TTL 内限频
        gate.onAdapterState(false)
        cache.get(loaderOf(offResult, count))
        assertEquals("OFF 期内不得绕过限频", 2, count.get())

        gate.onAdapterState(true)                 // OFF→ON：必须重载
        assertEquals(ApiReadState.OK, cache.get(loaderOf(ok(), count)).state)
        assertEquals(3, count.get())
    }
}
