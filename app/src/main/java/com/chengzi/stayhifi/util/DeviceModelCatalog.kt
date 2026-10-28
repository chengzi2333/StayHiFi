package com.chengzi.stayhifi.util

import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.serialization.json.Json

/** Task C2：机型目录加载终态。NOT_LOADED → READY / FAILED，均不可回退。 */
internal enum class CatalogLoadState { NOT_LOADED, READY, FAILED }

/**
 * 离线 Model→营销名目录的进程内有界单飞缓存（spec §10）。
 *
 * - [preload] 在调用者线程完成有界 GZIP/JSON 读取（仅 IO），同步单飞保证 opener 至多一次；
 * - [lookup] 非阻塞、零打开/解析、不持锁，只读已发布的不可变快照；
 * - 任何打开/解压/解析/超限异常统一转为 FAILED，不抛给调用方、不记录资产内容；
 * - 解压字节预算见 [MAX_DECOMPRESSED_BYTES]，读取至多多一个字节以识别超限。
 */
internal class DeviceModelCatalog(
    private val openAsset: () -> InputStream,
) {
    @Volatile
    var state: CatalogLoadState = CatalogLoadState.NOT_LOADED
        private set

    @Volatile
    private var catalog: Map<String, String>? = null

    private val loadLock = Any()

    /** 阻塞预载（仅 IO）；终态后重复调用直接返回现状态，不重读资产。 */
    fun preload(): CatalogLoadState {
        if (state != CatalogLoadState.NOT_LOADED) return state
        synchronized(loadLock) {
            if (state != CatalogLoadState.NOT_LOADED) return state
            state = try {
                catalog = loadBounded()
                CatalogLoadState.READY
            } catch (t: Throwable) {
                catalog = null
                CatalogLoadState.FAILED
            }
            return state
        }
    }

    /** 非阻塞精确查询；未就绪/失败一律返回 null，绝不触发打开或解析。 */
    fun lookup(model: String): String? = catalog?.get(model)

    private fun loadBounded(): Map<String, String> {
        openAsset().use { raw ->
            GZIPInputStream(raw).use { gzip ->
                val bytes = gzip.readNBytes(MAX_DECOMPRESSED_BYTES + 1)
                if (bytes.size > MAX_DECOMPRESSED_BYTES) {
                    throw IllegalStateException("解压后超过 $MAX_DECOMPRESSED_BYTES bytes 上限")
                }
                return JSON.decodeFromString(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    private companion object {
        const val MAX_DECOMPRESSED_BYTES = 1_600_000
        val JSON = Json
    }
}
