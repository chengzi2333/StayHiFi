package com.chengzi.stayhifi

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogBufferTest {

    private val lineRegex = Regex("""^\[\d{2}:\d{2}:\d{2}\] msg-\d+$""")

    @Test
    fun concurrentAppendsAreFormattedAndRetainedWithoutCorruption() {
        val clock = AtomicLong(1_000_000L)
        val buffer = LogBuffer(nowMillis = { clock.incrementAndGet() })
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val ready = CountDownLatch(threads)
        val futures = (0 until threads).map { t ->
            pool.submit<List<String>> {
                ready.countDown()
                start.await()
                val out = ArrayList<String>(perThread)
                for (i in 0 until perThread) {
                    out.add(buffer.append("msg-${t * perThread + i}"))
                }
                out
            }
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        val perThreadLines = futures.map { f ->
            try {
                f.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                throw AssertionError("append 线程抛异常: $e", e)
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        // 所有 append 返回的行都符合格式（无格式化异常、无破损行）
        val allReturned = perThreadLines.flatten()
        assertEquals(threads * perThread, allReturned.size)
        assertTrue(allReturned.all { lineRegex.matches(it) })

        // 快照恰好 200 行，格式完好、无重复
        val snap = buffer.snapshot()
        assertEquals(200, snap.size)
        assertTrue(snap.all { lineRegex.matches(it) })
        assertEquals(snap.size, snap.toSet().size)

        // 时间戳非递减（同一 lock 内取时间+格式化+写入，故与缓冲顺序一致）
        val stamps = snap.map { it.substring(1, 9) }
        assertTrue(stamps.zipWithNext().all { (a, b) -> a <= b })

        // 快照中的行全部来自真实 append 返回
        val returnedSet = allReturned.toSet()
        assertTrue(snap.all { it in returnedSet })

        // 每个线程在快照中的贡献必须是其自身 append 序列的连续后缀：
        // 单线程内 append 顺序即入队顺序，被淘汰的只能是最前面的行。
        val snapIds = snap.map { it.substringAfter("msg-").toInt() }.toSet()
        for (t in 0 until threads) {
            val base = t * perThread
            val kept = (base until base + perThread).filter { it in snapIds }
            if (kept.isEmpty()) continue
            assertEquals(kept.first(), base + perThread - kept.size)
            assertEquals(kept.last(), base + perThread - 1)
        }

        // 所有线程贡献之和恰为 200
        val totalKept = (0 until threads).sumOf { t ->
            val base = t * perThread
            snapIds.count { it in base until base + perThread }
        }
        assertEquals(200, totalKept)
    }

    @Test
    fun clearEmptiesAndBufferCanBeReused() {
        val buffer = LogBuffer()
        buffer.append("a")
        buffer.append("b")
        assertEquals(2, buffer.snapshot().size)
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty())
        buffer.append("c")
        val snap = buffer.snapshot()
        assertEquals(1, snap.size)
        assertTrue(snap[0].endsWith("] c"))
    }

    @Test
    fun singleThreadedFormatMatchesLegacyShape() {
        val buffer = LogBuffer(nowMillis = { 0L })
        val line = buffer.append("hello")
        assertTrue(Regex("""^\[\d{2}:\d{2}:\d{2}\] hello$""").matches(line))
    }
}
