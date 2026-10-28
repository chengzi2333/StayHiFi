package com.chengzi.stayhifi.util

/**
 * Task C3：对外机型名四层精确裁决（spec §10.1，Global Constraints 固定顺序）。
 *
 * 1. 精确人工 override（[DEVICE_MODEL_OVERRIDES]，仅精确匹配）；
 * 2. 厂商 SystemProperties 固定键链（[MARKET_NAME_KEYS]，空白/异常/等于
 *    Build.MODEL 的内部型号占位值一律继续下层）；
 * 3. 离线 Model→营销名目录（[DeviceModelCatalog] 当前缓存快照，未就绪为 null）；
 * 4. Build.MODEL，空白时最后显示 "Unknown"。
 *
 * 纯 JVM：属性读取注入为 lambda，不做模糊匹配、不触发任何 IO（目录查询
 * 零打开由 DeviceModelCatalog 保证）。
 */
internal class DeviceNameResolver(
    private val buildModel: String,
    private val overrides: Map<String, String> = DEVICE_MODEL_OVERRIDES,
    private val readProperty: (String) -> String?,
    private val catalog: DeviceModelCatalog,
) {

    fun marketModel(): String {
        overrides[buildModel]?.let { return it }
        for (key in MARKET_NAME_KEYS) {
            val raw = try {
                readProperty(key)
            } catch (t: Throwable) {
                null
            } ?: continue
            val value = raw.trim()
            // 空白、或返回内部型号占位值（等于 Build.MODEL）的属性视为无效，继续下层。
            if (value.isEmpty() || value == buildModel) continue
            return value
        }
        catalog.lookup(buildModel)?.let { return it }
        val model = buildModel.trim()
        return if (model.isEmpty()) "Unknown" else model
    }
}

/**
 * 厂商市场名属性键的固定查询顺序（Global Constraints）。
 *
 * 前三个是既有已验证键（vivo/iQOO 的 ro.vivo.market.name、通用
 * ro.product.marketname、旧 OPPO ro.oppo.market.name）；现代 OPlus 键
 * ro.vendor.oplus.market.name / ro.vendor.oplus.market.enname 有真实 getprop
 * 样本（OnePlus Ace 3 用户样本：fastfetch-cli/fastfetch#1768），但 PHZ110
 * 的属性路径未验证，不得由该样本外推。
 */
internal val MARKET_NAME_KEYS: List<String> = listOf(
    "ro.vivo.market.name",
    "ro.product.marketname",
    "ro.vendor.oplus.market.name",
    "ro.vendor.oplus.market.enname",
    "ro.oppo.market.name",
)
