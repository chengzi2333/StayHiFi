package com.chengzi.stayhifi.util

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task C4：机型目录与 SystemProperties 的 Android 宿主验证。
 *
 * 只验证真实打包资产与 framework 属性；不断言任何 OEM 键必定存在
 * （OEM 属性路径的真机样本见 Global Constraints，未验证结论不外推）。
 */
class DeviceNamesInstrumentedTest {

    @Test
    fun packagedCatalogAssetPreloadsAndContainsOpd2409() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // 与 DeviceNames 生产 opener 同一解析顺序：aapt 可能已把 .gz 透明解压为裸 .json。
        val catalog = DeviceModelCatalog(
            openAsset = {
                try {
                    context.assets.open("device_names/device_models.json.gz")
                } catch (notFound: java.io.FileNotFoundException) {
                    context.assets.open("device_names/device_models.json")
                }
            },
        )
        assertEquals(CatalogLoadState.READY, catalog.preload())
        assertEquals("OPPO Pad 4 Pro", catalog.lookup("OPD2409"))
    }

    @Test
    fun systemPropertiesCanReadFrameworkModel() {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        val model = get.invoke(null, "ro.product.model") as? String
        assertEquals(Build.MODEL, model)
    }
}
