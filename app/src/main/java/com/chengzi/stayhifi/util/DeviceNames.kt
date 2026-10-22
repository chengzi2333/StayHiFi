package com.chengzi.stayhifi.util

import android.os.Build

/**
 * 面客机型名（Task 28）：`Build.MODEL` 是内部型号（V2505A），面客名走
 * SystemProperties 市场名键链。SystemProperties 是隐藏 API（android.jar 不导出），
 * 只能反射读；全 miss 回 [Build.MODEL]。
 * 真机实测：vivo/iQOO 给 ro.vivo.market.name（"iQOO 15"），OPPO/realme/一加给
 * ro.oppo.market.name，通用键 ro.product.marketname。
 */
object DeviceNames {

    private val MARKET_NAME_KEYS = listOf(
        "ro.vivo.market.name",
        "ro.product.marketname",
        "ro.oppo.market.name",
    )

    /** 面客机型名；键链全空回 Build.MODEL。 */
    fun marketModel(): String {
        for (key in MARKET_NAME_KEYS) {
            val value = getSystemProperty(key)
            if (!value.isNullOrBlank()) return value.trim()
        }
        return Build.MODEL
    }

    /** 状态卡机型行文案（Task 28 F2）："iQOO 15（Android 17）"。 */
    fun marketModelWithAndroid(): String = "${marketModel()}（Android ${Build.VERSION.RELEASE}）"

    private fun getSystemProperty(key: String): String? = try {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    } catch (t: Throwable) {
        null
    }
}
