package com.chengzi.stayhifi.trigger

import android.content.Context
import android.content.ContextWrapper
import android.os.IBinder
import com.chengzi.stayhifi.ICodecService
import com.chengzi.stayhifi.codec.CodecConfig
import java.lang.reflect.Proxy
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Task B4：ApplyLock 后 per-MAC 本机 A2DP 断连复查。
 *
 * 用真实 Mutex + runBlocking + fake probe/carrier 证明：
 * 排队任务真正拿到锁后才复查；DISCONNECTED 零载体调用只记一条结束日志；
 * force 只影响 autoApply 判断，不能绕过复查；UNKNOWN/probe 异常按旧路径执行并明确记录；
 * 载体异常后锁正常释放。
 */
class ApplyConnectionGuardTest {

    private class FakeProbe(
        private val result: LocalA2dpState,
        private val error: Throwable? = null,
    ) : LocalA2dpProbe {
        var calls = 0
        override fun state(mac: String): LocalA2dpState {
            calls++
            error?.let { throw it }
            return result
        }
    }

    private class FakeCodecService(
        private val writeResult: String = """{"applied":true}""",
        private val readResult: String? = null,
    ) : ICodecService {
        var writeCalls = 0
        override fun asBinder(): IBinder = Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(IBinder::class.java),
        ) { _, _, _ -> null } as IBinder

        override fun dumpDevices(): String = "[]"
        override fun readConfig(mac: String?): String =
            readResult ?: """{"error":"fake read unavailable"}"""

        override fun writeConfig(mac: String?, configJson: String?): String {
            writeCalls++
            return writeResult
        }

        override fun dumpBtState(): String = """{"dump":""}"""
        override fun destroy() = Unit
    }

    /**
     * 仅支撑构造 ApplyWorker 的最小 Context：applicationContext 返回自身，
     * 其余方法一律不触碰（JVM 单测不走生产依赖的 lazy 初始化）。
     */
    private class FakeContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private fun fakeContext(): Context = FakeContext()

    @Test
    fun queuedDisconnectedWorkerChecksAfterLockAndDoesNoCarrierIo() = runBlocking {
        val lock = Mutex().also { it.lock() }
        val probe = FakeProbe(LocalA2dpState.DISCONNECTED)
        val logs = mutableListOf<String>()
        var carrierCalls = 0
        val guard = ApplyConnectionGuard(lock, probe, logs::add)

        val pending = async { guard.run("AA:BB:CC:DD:EE:FF") { carrierCalls++ } }
        yield() // 让排队任务跑到锁门口：此时还轮不到它读 probe
        assertEquals(0, probe.calls)
        assertEquals(0, carrierCalls)

        lock.unlock()
        assertFalse(pending.await())
        assertEquals(1, probe.calls)
        assertEquals(0, carrierCalls)
        assertEquals(listOf("设备已断开，跳过排队任务: mac=AA:BB:CC:DD:EE:FF"), logs)
    }

    @Test
    fun forcedTriggerStillCannotBypassDisconnectedGuard() = runBlocking {
        val probe = FakeProbe(LocalA2dpState.DISCONNECTED)
        val logs = mutableListOf<String>()
        var carrierCalls = 0
        val guard = ApplyConnectionGuard(Mutex(), probe, logs::add)
        val dispatcher = TriggerDispatcher(
            TriggerGate(nowMillis = { 0L }),
            runner = TriggerRunner { mac, _, _ -> guard.run(mac) { carrierCalls++ } },
            log = logs::add,
        )

        // force=true（手动入口）只应跳过 TriggerGate 的合并窗口，不能跳过锁后断连复查。
        assertTrue(dispatcher.dispatch("AA:BB", "buds", true, TriggerSource.MANUAL))

        assertEquals(0, carrierCalls)
        assertEquals(1, logs.count { it.contains("设备已断开，跳过排队任务") })
    }

    @Test
    fun connectedDeviceRunsCarrierOnce() = runBlocking {
        val probe = FakeProbe(LocalA2dpState.CONNECTED)
        val logs = mutableListOf<String>()
        var carrierCalls = 0
        val guard = ApplyConnectionGuard(Mutex(), probe, logs::add)

        assertTrue(guard.run("AA:BB") { carrierCalls++ })

        assertEquals(1, carrierCalls)
        assertTrue(logs.isEmpty())
    }

    @Test
    fun unknownProbeStateFallsBackToLegacyPathWithOneLog() = runBlocking {
        val probe = FakeProbe(LocalA2dpState.UNKNOWN)
        val logs = mutableListOf<String>()
        var carrierCalls = 0
        val guard = ApplyConnectionGuard(Mutex(), probe, logs::add)

        assertTrue(guard.run("AA:BB") { carrierCalls++ })

        assertEquals(1, carrierCalls)
        assertEquals(listOf("本机 A2DP 状态不可用，按旧路径执行: mac=AA:BB"), logs)
    }

    @Test
    fun probeThrowingSecurityExceptionFallsBackToLegacyPathWithOneLog() = runBlocking {
        val probe = FakeProbe(LocalA2dpState.UNKNOWN, SecurityException("no BLUETOOTH_CONNECT"))
        val logs = mutableListOf<String>()
        var carrierCalls = 0
        val guard = ApplyConnectionGuard(Mutex(), probe, logs::add)

        assertTrue(guard.run("AA:BB") { carrierCalls++ })

        assertEquals(1, carrierCalls)
        assertEquals(listOf("本机 A2DP 状态不可用，按旧路径执行: mac=AA:BB"), logs)
    }

    @Test
    fun emptyAddressListMeansDisconnected() {
        assertEquals(
            LocalA2dpState.DISCONNECTED,
            LocalBluetoothA2dpProbe.stateOf(emptyList(), "AA:BB:CC:DD:EE:FF"),
        )
        assertEquals(
            LocalA2dpState.CONNECTED,
            LocalBluetoothA2dpProbe.stateOf(listOf("aa:bb:cc:dd:ee:ff"), "AA:BB:CC:DD:EE:FF"),
        )
        assertEquals(
            LocalA2dpState.UNKNOWN,
            LocalBluetoothA2dpProbe.stateOf(null, "AA:BB:CC:DD:EE:FF"),
        )
    }

    @Test
    fun carrierExceptionReleasesMutexForNextRun() = runBlocking {
        val lock = Mutex()
        val guard = ApplyConnectionGuard(lock, FakeProbe(LocalA2dpState.CONNECTED)) {}

        try {
            guard.run("AA:BB") { throw IllegalStateException("boom") }
            fail("载体异常应向上传播")
        } catch (t: IllegalStateException) {
            // 预期：异常穿过 guard，锁仍须释放。
        }
        // 上一次载体异常后锁必须已释放：同一锁上的新 guard 照常复查并按 DISCONNECTED 跳过。
        val disconnected = ApplyConnectionGuard(lock, FakeProbe(LocalA2dpState.DISCONNECTED)) {}
        assertFalse(disconnected.run("AA:BB") {})
    }

    @Test
    fun restoreDefaultSkipsCarrierWhenDisconnectedAfterQueue() = runBlocking {
        val logs = mutableListOf<String>()
        var bindCalls = 0
        var notifyCalls = 0
        var notifyFixCalls = 0
        val worker = ApplyWorker(fakeContext())
        worker.logSink = logs::add
        worker.connectionGuard = ApplyConnectionGuard(
            Mutex(), FakeProbe(LocalA2dpState.DISCONNECTED), logs::add,
        )
        worker.restoreEnv = ApplyWorker.RestoreEnv(
            shellReady = { true },
            hasPrivilege = { true },
            bind = { bindCalls++; FakeCodecService() },
            unbind = { },
            notifyFix = { notifyFixCalls++ },
            notify = { _, _ -> notifyCalls++ },
            notifyResultIfEnabled = { _, _ -> notifyCalls++ },
        )

        val origin = CodecConfig(4, 0, 32, 2, 2, 1000L, 0L, 0L, 0L)
        assertFalse(worker.restoreDefault("AA:BB:CC:DD:EE:FF", "buds", origin))

        assertEquals(0, bindCalls)   // 零载体调用 → 不会向设备写 origin
        assertEquals(0, notifyCalls) // 不发成功通知
        assertEquals(0, notifyFixCalls)
        assertEquals(1, logs.count { it.contains("设备已断开，跳过排队任务") })
    }

    @Test
    fun restoreDefaultRunsOriginalWriteReadbackFlowWhenConnected() = runBlocking {
        val logs = mutableListOf<String>()
        val mac = "AA:BB:CC:DD:EE:FF"
        val origin = CodecConfig(4, 0, 32, 2, 2, 1000L, 0L, 0L, 0L)
        val service = FakeCodecService(readResult = """{"current":${origin.toJson()},"selectable":[]}""")
        val notifications = mutableListOf<Pair<String, String>>()
        val worker = ApplyWorker(fakeContext())
        worker.logSink = logs::add
        worker.connectionGuard = ApplyConnectionGuard(
            Mutex(), FakeProbe(LocalA2dpState.CONNECTED), logs::add,
        )
        var unbindCalls = 0
        worker.restoreEnv = ApplyWorker.RestoreEnv(
            shellReady = { true },
            hasPrivilege = { true },
            bind = { service },
            unbind = { unbindCalls++ },
            notifyFix = { notifications += "FIX" to it },
            notify = { t, c -> notifications += t to c },
            notifyResultIfEnabled = { t, c -> notifications += t to c },
        )
        // 读回一致后应作废旧拉满目标。
        VerifiedTargetStore.record(mac, CodecConfig(4, 0, 32, 2, 2, 1000L, 0L, 0L, 0L))

        assertTrue(worker.restoreDefault(mac, "buds", origin))

        assertEquals(1, service.writeCalls) // 写回 origin 一次
        assertEquals(1, unbindCalls)
        assertEquals(listOf("已恢复默认参数" to "buds → ${origin.summary()}"), notifications)
        assertTrue(VerifiedTargetStore.targetFor(mac) == null)
        assertTrue(logs.any { it.contains("恢复默认成功") })
    }
}
