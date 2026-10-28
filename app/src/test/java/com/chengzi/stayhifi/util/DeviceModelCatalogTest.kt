package com.chengzi.stayhifi.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/** Task C2：机型目录有界单飞缓存的纯 JVM 测试。 */
class DeviceModelCatalogTest {

    private fun gzipRaw(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gzipJson(pairs: Map<String, String>): ByteArray {
        val entries = pairs.toSortedMap().entries.joinToString(",") { (k, v) ->
            "${jsonString(k)}:${jsonString(v)}"
        }
        return gzipRaw("{$entries}".toByteArray(Charsets.UTF_8))
    }

    private fun catalogWith(
        bytes: ByteArray?,
        openCount: AtomicInteger = AtomicInteger(),
    ): Pair<DeviceModelCatalog, AtomicInteger> {
        val catalog = DeviceModelCatalog(openAsset = {
            openCount.incrementAndGet()
            if (bytes == null) throw FileNotFoundException("device_models.json.gz")
            ByteArrayInputStream(bytes)
        })
        return catalog to openCount
    }

    @Test
    fun test_notLoaded_lookupIsNullAndOpenerNotCalled() {
        val asset = gzipJson(mapOf("OPD2409" to "OPPO Pad 4 Pro"))
        val (catalog, openCount) = catalogWith(asset)
        assertEquals(CatalogLoadState.NOT_LOADED, catalog.state)
        assertNull(catalog.lookup("OPD2409"))
        assertEquals(0, openCount.get())
    }

    @Test
    fun test_preloadReady_lookupWorks() {
        val asset = gzipJson(mapOf("OPD2409" to "OPPO Pad 4 Pro", "PHZ110" to "OPPO Find X7"))
        val (catalog, openCount) = catalogWith(asset)
        assertEquals(CatalogLoadState.READY, catalog.preload())
        assertEquals(CatalogLoadState.READY, catalog.state)
        assertEquals("OPPO Pad 4 Pro", catalog.lookup("OPD2409"))
        assertEquals("OPPO Find X7", catalog.lookup("PHZ110"))
        assertNull(catalog.lookup("UNKNOWN"))
        assertEquals(1, openCount.get())
    }

    @Test
    fun test_missingAsset_entersFailedWithoutThrowing() {
        val (catalog, openCount) = catalogWith(null)
        assertEquals(CatalogLoadState.FAILED, catalog.preload())
        assertEquals(CatalogLoadState.FAILED, catalog.state)
        assertNull(catalog.lookup("OPD2409"))
        // 失败后重复 preload 不重读。
        assertEquals(CatalogLoadState.FAILED, catalog.preload())
        assertEquals(1, openCount.get())
    }

    @Test
    fun test_truncatedGzip_entersFailed() {
        val asset = gzipJson(mapOf("OPD2409" to "OPPO Pad 4 Pro")).copyOf(20)
        val (catalog, openCount) = catalogWith(asset)
        assertEquals(CatalogLoadState.FAILED, catalog.preload())
        assertNull(catalog.lookup("OPD2409"))
        assertEquals(1, openCount.get())
    }

    @Test
    fun test_badJsonInValidGzip_entersFailed() {
        val asset = gzipRaw("{not valid json".toByteArray(Charsets.UTF_8))
        val (catalog, _) = catalogWith(asset)
        assertEquals(CatalogLoadState.FAILED, catalog.preload())
        assertNull(catalog.lookup("OPD2409"))
    }

    @Test
    fun test_overBudgetDecompressedBytes_entersFailed() {
        // 高压缩比 payload：gzip 很小，但解压后超过 1_600_000 bytes。
        val asset = gzipRaw(ByteArray(1_600_100) { 'a'.code.toByte() })
        val (catalog, _) = catalogWith(asset)
        assertEquals(CatalogLoadState.FAILED, catalog.preload())
        assertNull(catalog.lookup("OPD2409"))
    }

    @Test
    fun test_atBudgetBoundary_stillReady() {
        // 合法 JSON 对象里装一个超长值，解压后紧贴上限以内。
        val value = "x".repeat(1_599_500)
        val asset = gzipJson(mapOf("OPD2409" to value))
        val (catalog, _) = catalogWith(asset)
        assertEquals(CatalogLoadState.READY, catalog.preload())
        assertEquals(value, catalog.lookup("OPD2409"))
    }

    @Test
    fun test_plainJsonWithoutGzip_isAccepted() {
        // aapt 打包时会把 .gz 资产透明解压并改名；裸 JSON 形态必须同样可读。
        val plain = "{\"OPD2409\":\"OPPO Pad 4 Pro\"}".toByteArray(Charsets.UTF_8)
        val (catalog, _) = catalogWith(plain)
        assertEquals(CatalogLoadState.READY, catalog.preload())
        assertEquals("OPPO Pad 4 Pro", catalog.lookup("OPD2409"))
    }

    @Test
    fun test_concurrentPreload_opensOnceAndConsistentState() {
        val asset = gzipJson(mapOf("OPD2409" to "OPPO Pad 4 Pro"))
        val (catalog, openCount) = catalogWith(asset)
        val threads = 8
        val latch = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<CatalogLoadState>())
        val workers = (1..threads).map {
            Thread {
                latch.await()
                results.add(catalog.preload())
            }
        }
        workers.forEach { it.start() }
        latch.countDown()
        workers.forEach { it.join(10_000) }
        assertEquals(threads, results.size)
        assertTrue(results.all { it == CatalogLoadState.READY })
        assertEquals(CatalogLoadState.READY, catalog.state)
        assertEquals(1, openCount.get())
        assertEquals("OPPO Pad 4 Pro", catalog.lookup("OPD2409"))
    }
}

/** 测试内极简 JSON 字符串转义（避免引入额外依赖）。 */
private fun jsonString(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s) {
        when (c) {
            '"', '\\' -> sb.append('\\').append(c)
            else -> sb.append(c)
        }
    }
    return sb.append('"').toString()
}
