package com.chengzi.stayhifi.trigger

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationRefreshGateTest {

    @Test
    fun concurrentRefreshRunsOnlyOneBlock() = runTest {
        val gate = NotificationRefreshGate()
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val first = async {
            gate.run {
                calls++
                entered.complete(Unit)
                finish.await()
            }
        }
        runCurrent()
        entered.await()

        val secondRan = gate.run { calls++ }

        assertFalse(secondRan)
        assertEquals(1, calls)
        finish.complete(Unit)
        assertTrue(first.await())
    }

    @Test
    fun physicallyBlockedCallMergesInsteadOfQueuingAnotherReadOrPost() = runBlocking {
        val gate = NotificationRefreshGate()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var laterReadOrPostCalls = 0
        val first = async(Dispatchers.IO) {
            gate.run {
                entered.countDown()
                // 模拟不可被协程取消的 Binder transact；测试结束时会物理释放。
                release.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(entered.await(1, TimeUnit.SECONDS))

        val second = withTimeoutOrNull(100) {
            gate.run { laterReadOrPostCalls++ }
        }

        assertTrue(
            "阻塞期间必须立即合并，不能排队: second=$second laterCalls=$laterReadOrPostCalls",
            second == false,
        )
        assertEquals(0, laterReadOrPostCalls)
        release.countDown()
        assertTrue(first.await())
    }

    @Test
    fun exceptionReleasesGateForLaterRefresh() = runBlocking {
        val gate = NotificationRefreshGate()
        val failure = runCatching {
            gate.run { throw IllegalStateException("boom") }
        }.exceptionOrNull()
        var laterCalls = 0

        val laterRan = gate.run { laterCalls++ }

        assertTrue(failure is IllegalStateException)
        assertTrue(laterRan)
        assertEquals(1, laterCalls)
    }
}
