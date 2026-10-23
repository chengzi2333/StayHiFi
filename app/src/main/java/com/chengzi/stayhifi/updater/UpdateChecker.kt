package com.chengzi.stayhifi.updater

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Task 38：GitHub Releases 云端自更新（无服务器方案）。
 *
 * 全应用唯一网络出口：
 * - [checkLatest] 请求 releases/latest API，仅做版本号比对，不携带任何设备信息；
 * - [downloadApk] 应用内下载 APK，由调用方走 FileProvider 拉起系统安装器。
 *
 * 刻意不引 OkHttp / 第三方 JSON 库：HttpURLConnection + org.json 保体积（项目已有 org.json）。
 */
object UpdateChecker {

    private const val API_URL =
        "https://api.github.com/repos/chengzi2333/StayHiFi/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 10_000

    /** 检查结果三态。 */
    sealed interface Result {
        /** 已是最新。 */
        data object Latest : Result

        /** 有新版本：tag（如 "v1.1.0"）、更新说明、APK 下载地址。 */
        data class NewAvailable(val tag: String, val notes: String, val apkUrl: String) : Result

        /** 网络/解析失败，cause 进日志。 */
        data class Error(val cause: String) : Result
    }

    /**
     * 语义化版本比较：strip 前导 v/V → 按 . 拆数值段逐段比较，缺段补 0、非数值段按 0。
     * 保证 1.0.0 < 1.1.0 < 1.10.0（数值比较，非字典序）；相等或远程更旧都返回 false。
     * 纯字符串运算，单测覆盖。
     */
    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").removePrefix("V").split(".")
        val l = local.removePrefix("v").removePrefix("V").split(".")
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val rv = r.getOrNull(i)?.toIntOrNull() ?: 0
            val lv = l.getOrNull(i)?.toIntOrNull() ?: 0
            if (rv != lv) return rv > lv
        }
        return false
    }

    /**
     * 检查最新 Release。阻塞 IO，调用方负责切到 IO 线程。
     * 失败不抛异常，统一收敛为 [Result.Error]。
     */
    fun checkLatest(currentVersion: String): Result = try {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub API 要求 User-Agent；顺手声明接受 JSON
            setRequestProperty("User-Agent", "StayHiFi")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Result.Error("HTTP $code")
            } else {
                parseRelease(conn.inputStream.bufferedReader().readText(), currentVersion)
            }
        } finally {
            conn.disconnect()
        }
    } catch (t: Throwable) {
        Result.Error(t.message ?: t.toString())
    }

    /** 解析 releases/latest 响应：tag_name / body / assets 里取 .apk 的下载地址。 */
    private fun parseRelease(body: String, currentVersion: String): Result {
        try {
        val obj = JSONObject(body)
        val tag = obj.optString("tag_name")
        if (tag.isBlank()) return Result.Error("响应缺 tag_name")
        val notes = obj.optString("body")
        var apkUrl: String? = null
        val assets = obj.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val url = asset.optString("browser_download_url")
                if (url.endsWith(".apk")) {
                    apkUrl = url
                    break
                }
            }
        }
        if (apkUrl == null) return Result.Error("Release 无 APK 附件")
        if (isNewerVersion(tag, currentVersion)) {
            return Result.NewAvailable(tag, notes, apkUrl)
        } else {
            return Result.Latest
        }
        } catch (t: Throwable) {
            return Result.Error("解析响应失败: ${t.message ?: t}")
        }
    }

    /**
     * 下载 APK 到 [dest]。阻塞 IO；失败抛 [IOException]，调用方负责删半成品文件。
     * [onProgress] 回调（已下载字节, 总字节；总长度未知时为 -1），在 IO 线程触发。
     * Content-Length 可得时校验实际大小，不符视为失败。
     */
    @Throws(IOException::class)
    fun downloadApk(url: String, dest: File, onProgress: (downloaded: Long, total: Long) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            // 下载 read timeout 放宽到 30s：单次阻塞读超时，慢网不至于误杀
            readTimeout = 30_000
            setRequestProperty("User-Agent", "StayHiFi")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("下载失败: HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        onProgress(downloaded, total)
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                throw IOException("下载不完整: $downloaded / $total 字节")
            }
        } finally {
            conn.disconnect()
        }
    }
}
