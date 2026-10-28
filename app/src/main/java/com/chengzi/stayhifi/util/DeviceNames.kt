package com.chengzi.stayhifi.util

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 面客机型名（Task 28 + Task C3/C4）：四层兜底链的 Android 薄适配器（spec §10）。
 *
 * 顺序：精确人工 override → 厂商 SystemProperties（[MARKET_NAME_KEYS]）→
 * 离线 Model→营销名目录 → Build.MODEL。首屏同步查询只走 override/属性/当前
 * 缓存快照，不等待、不打开资产；[preload] 在后台线程完成后由
 * [marketModelWithAndroidFlow] 驱动 UI 刷新。资产缺失/损坏/超限时安全回退，
 * 不阻塞启动、不崩溃、不显示空白。
 */
object DeviceNames {

    private const val CATALOG_ASSET_PATH = "device_names/device_models.json.gz"
    private const val CATALOG_ASSET_PLAIN_PATH = "device_names/device_models.json"

    @Volatile
    private var appContext: Context? = null

    // 懒构造：opener 只捕获 context 引用，不做任何 IO；真正的打开只在 preload 时发生。
    // aapt 会把 assets 的 .gz 透明解压并改名为裸 .json，先按规范名打开，缺失时回退
    // 打包后的裸 JSON 名（DeviceModelCatalog 按 magic 自动识别是否仍需解压）。
    private val catalog: DeviceModelCatalog by lazy {
        DeviceModelCatalog(openAsset = {
            val ctx = appContext ?: throw IllegalStateException("DeviceNames.initialize 未调用")
            try {
                ctx.assets.open(CATALOG_ASSET_PATH)
            } catch (notFound: java.io.FileNotFoundException) {
                ctx.assets.open(CATALOG_ASSET_PLAIN_PATH)
            }
        })
    }

    private val _marketModelWithAndroidFlow = MutableStateFlow(marketModelWithAndroid())

    /** 机型行文案状态流：preload 完成后自动发出新值驱动重组。 */
    val marketModelWithAndroidFlow: StateFlow<String> = _marketModelWithAndroidFlow

    /** 仅保存 applicationContext（主线程安全，无 IO）。 */
    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 阻塞预载（仅 IO）；完成后刷新 [marketModelWithAndroidFlow]。
     * 由单飞缓存保证资产至多打开一次，任何失败都内聚为 FAILED，不向调用方抛异常。
     */
    fun preload() {
        if (appContext == null) return
        catalog.preload()
        _marketModelWithAndroidFlow.value = marketModelWithAndroid()
    }

    /** 面客机型名；始终只走 override、反射属性和当前缓存快照，不等待预载。 */
    fun marketModel(): String = DeviceNameResolver(
        buildModel = Build.MODEL,
        readProperty = AndroidSystemProperties::get,
        catalog = catalog,
    ).marketModel()

    /** 状态卡机型行文案（Task 28 F2）："iQOO 15（Android 17）"。 */
    fun marketModelWithAndroid(): String = "${marketModel()}（Android ${Build.VERSION.RELEASE}）"
}

/**
 * android.os.SystemProperties 是隐藏 API（android.jar 不导出），只能反射读；
 * 任何反射/调用异常隔离为 null，交由 DeviceNameResolver 继续下层。
 */
internal object AndroidSystemProperties {
    fun get(key: String): String? = try {
        val sp = Class.forName("android.os.SystemProperties")
        val get = sp.getMethod("get", String::class.java)
        get.invoke(null, key) as? String
    } catch (t: Throwable) {
        null
    }
}
