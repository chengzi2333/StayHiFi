package com.chengzi.stayhifi.util

import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Task C3：四层精确裁决（override → 属性 → 目录 → Build.MODEL）的纯 JVM 测试。 */
class DeviceNameResolverTest {

    private fun gzipJson(pairs: Map<String, String>): ByteArray {
        val entries = pairs.toSortedMap().entries.joinToString(",") { (k, v) -> "\"$k\":\"$v\"" }
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("{$entries}".toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun readyCatalog(pairs: Map<String, String>): DeviceModelCatalog =
        DeviceModelCatalog(openAsset = { ByteArrayInputStream(gzipJson(pairs)) })
            .apply { preload() }

    private fun notLoadedCatalog(): DeviceModelCatalog =
        DeviceModelCatalog(openAsset = {
            AtomicInteger().incrementAndGet()
            ByteArrayInputStream(ByteArray(0))
        })

    private fun resolver(
        buildModel: String,
        props: Map<String, String> = emptyMap(),
        catalog: DeviceModelCatalog = notLoadedCatalog(),
        overrides: Map<String, String> = DEVICE_MODEL_OVERRIDES,
    ) = DeviceNameResolver(
        buildModel = buildModel,
        overrides = overrides,
        readProperty = { key -> props[key] },
        catalog = catalog,
    )

    @Test
    fun test_override_beatsPropertyAndCatalog() {
        val catalog = readyCatalog(mapOf("NX741J" to "nubia Z80", "PHZ110" to "OPPO Find X7"))
        assertEquals(
            "nubia Z80 Ultra",
            resolver("NX741J", props = mapOf("ro.vivo.market.name" to "PROP-NAME"), catalog = catalog).marketModel(),
        )
        assertEquals("Find X7", resolver("PHZ110", catalog = catalog).marketModel())
    }

    @Test
    fun test_override_isExactMatchOnly() {
        // NX741JX 不得误中 NX741J 的 override；无属性无目录时回 Build.MODEL。
        assertEquals("NX741JX", resolver("NX741JX").marketModel())
    }

    @Test
    fun test_catalogUsedWhenNoProperty() {
        val catalog = readyCatalog(mapOf("OPD2409" to "OPPO Pad 4 Pro"))
        assertEquals("OPPO Pad 4 Pro", resolver("OPD2409", catalog = catalog).marketModel())
    }

    @Test
    fun test_vivoMarketNameProperty_beatsCatalog() {
        val catalog = readyCatalog(mapOf("DPD2438B" to "FAKE-CATALOG-VALUE"))
        val props = mapOf("ro.vivo.market.name" to "vivo Pad5 Pro")
        assertEquals("vivo Pad5 Pro", resolver("DPD2438B", props = props, catalog = catalog).marketModel())
    }

    @Test
    fun test_modernOplusKeys_priorityOverLegacy() {
        val props = mapOf(
            "ro.vendor.oplus.market.name" to "OnePlus Ace 3 本地名",
            "ro.vendor.oplus.market.enname" to "OnePlus Ace 3 EN",
            "ro.oppo.market.name" to "OPPO Legacy Name",
        )
        assertEquals("OnePlus Ace 3 本地名", resolver("PJE110", props = props).marketModel())
    }

    @Test
    fun test_ennameUsedWhenLocalNameBlank() {
        val props = mapOf(
            "ro.vendor.oplus.market.name" to "   ",
            "ro.vendor.oplus.market.enname" to "OnePlus Ace 3 EN",
        )
        assertEquals("OnePlus Ace 3 EN", resolver("PJE110", props = props).marketModel())
    }

    @Test
    fun test_legacyOppoKeyStillUsed() {
        val props = mapOf("ro.oppo.market.name" to "OPPO Find X6 Pro")
        assertEquals("OPPO Find X6 Pro", resolver("PGEM10", props = props).marketModel())
    }

    @Test
    fun test_invalid_property_values_fall_through() {
        val catalog = readyCatalog(mapOf("V2505A" to "iQOO 15"))
        // 空白值继续下层。
        assertEquals(
            "iQOO 15",
            resolver("V2505A", props = mapOf("ro.vivo.market.name" to "  "), catalog = catalog).marketModel(),
        )
        // 等于 Build.MODEL 的内部型号占位值继续下层，不能遮住目录。
        assertEquals(
            "iQOO 15",
            resolver("V2505A", props = mapOf("ro.vivo.market.name" to "V2505A"), catalog = catalog).marketModel(),
        )
        // 前面的键抛异常也继续下层。
        val throwing = DeviceNameResolver(
            buildModel = "V2505A",
            readProperty = { key ->
                when (key) {
                    "ro.vivo.market.name" -> throw RuntimeException("getprop failed")
                    else -> null
                }
            },
            catalog = catalog,
        )
        assertEquals("iQOO 15", throwing.marketModel())
    }

    @Test
    fun test_allMiss_fallsBackToBuildModel() {
        assertEquals("SM-S911B", resolver("SM-S911B").marketModel())
    }

    @Test
    fun test_blankBuildModel_showsUnknown() {
        assertEquals("Unknown", resolver("   ").marketModel())
    }

    @Test
    fun test_notLoadedCatalog_fallsThroughToBuildModel() {
        // NOT_LOADED 时 lookup 为 null，绝不触发打开（opener 次数由 Task 2 纯 JVM 用例覆盖）。
        assertEquals("SM-S911B", resolver("SM-S911B", catalog = notLoadedCatalog()).marketModel())
    }

    @Test
    fun test_propertyValueIsTrimmed() {
        val props = mapOf("ro.product.marketname" to "  Galaxy S24  ")
        assertEquals("Galaxy S24", resolver("SM-S921B", props = props).marketModel())
    }
}
