package com.chengzi.stayhifi

import java.util.LinkedHashMap
import java.util.Locale

/**
 * Task B7：单次报告稳定脱敏的反馈日志脱敏器。
 *
 * 每次 [sanitize] 开始即清空上一次映射（单次报告语义，不跨报告跟踪）；只处理最近
 * 199 行，给调用方的 1 行 header 留位（反馈报告总预算 200 行）。两阶段纯文本处理：
 *
 * 第一遍：以忽略大小写的 Bluetooth MAC 正则按首次出现建立 LinkedHashMap 编号
 * （device1/device2/...），并从同一行「名称 (MAC)」既有格式（连接/建档/恢复/QS 等，
 * 见 [KNOWN_NAME_MAC_PREFIXES]）发现自定义设备名与别名的关联；空名、`?` 与 MAC 本身
 * 不作为名称。
 *
 * 第二遍：先按长度从长到短替换名称的字面形式及 JSON 转义形式（Regex.escape，不解释
 * 用户正则），再替换全部 MAC（忽略大小写），使异常消息内嵌副本与重复副本同样脱敏；
 * 同名设备确定性地映射到同一别名，不泄露原文。
 *
 * 纯内存实现：不写日志、不持久化、无任何载体 IO；[reset] 仅清内存状态。
 */
class FeedbackLogSanitizer {

    /** 反馈报告总预算 200 行 = 调用方 1 行 header + 最近 199 行日志。 */
    private val windowSize = 199

    /** 忽略大小写的 Bluetooth MAC（6 组十六进制，冒号分隔）。 */
    private val macRegex = Regex("""(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}""")

    /** LogStore 行首时间戳前缀。 */
    private val timestampPrefix = Regex("""^\[\d{2}:\d{2}:\d{2}]\s*""")

    /**
     * 既有日志文案里「名称 (MAC)」前的固定前缀（连接/建档/恢复/QS/漂移/向导/ACL）。
     * 见 MainActivity、QsTileService、ListenerService、ConnectionReceiver、CodecOperationLog。
     */
    private val knownNameMacPrefixes = listOf(
        "已连接: ",
        "QS 磁贴触发拉满: ",
        "收到 ACL_CONNECTED: ",
        "启动补触发：发现已连接设备 ",
        "检测到参数漂移: ",
        "录制向导抓到: ",
        "已保存录制档案: ",
        "已自动建档: ",
    )

    /** 小写 MAC -> deviceN；编号按报告内首次出现顺序递增。 */
    private val macAliases = LinkedHashMap<String, String>()

    /** 设备名原文 -> deviceN；与 [macAliases] 共用同一套编号。 */
    private val nameAliases = LinkedHashMap<String, String>()

    /**
     * 脱敏一段日志行并返回单行拼接文本（不含 header；header 由调用方前置）。
     * 空输入返回空字符串；每次调用都是独立报告，映射不沿用。
     */
    @Synchronized
    fun sanitize(lines: List<String>): String {
        macAliases.clear()
        nameAliases.clear()
        if (lines.isEmpty()) return ""
        val window = if (lines.size > windowSize) {
            lines.subList(lines.size - windowSize, lines.size)
        } else {
            lines
        }
        window.forEach { discover(it) }
        return window.joinToString("\n") { sanitizeLine(it) }
    }

    /** 仅清内存状态：不写真机日志、不持久化。清空按钮与跨报告隔离共用。 */
    @Synchronized
    fun reset() {
        macAliases.clear()
        nameAliases.clear()
    }

    /** 第一遍：MAC 首次出现编号；从同一行「名称 (MAC)」格式发现名称关联。 */
    private fun discover(line: String) {
        // 顺序扫描 MAC：首次出现顺序即编号顺序。
        macRegex.findAll(line).forEach { match ->
            val key = match.value.lowercase(Locale.ROOT)
            if (key !in macAliases) {
                macAliases[key] = "device${macAliases.size + 1}"
            }
        }
        // 「名称 (MAC)」：行内每个 `(MAC)` 都尝试向前提取名称。
        macRegex.findAll(line).forEach { match ->
            val macKey = match.value.lowercase(Locale.ROOT)
            val before = line.substring(0, match.range.first)
            if (!before.endsWith(" (")) return@forEach
            val candidate = before.substring(0, before.length - 2)
            extractName(candidate)?.let { name ->
                // 同名确定性映射到首次配对的别名；本行 MAC 保留自己的编号，
                // 两个 MAC 不被同名折叠（漂移/对连场景仍能区分设备）。
                if (name !in nameAliases) {
                    nameAliases[name] = macAliases.getValue(macKey)
                }
            }
        }
    }

    /** 从「名称 (MAC)」前的文本提取设备名：去时间戳/已知前缀，最后按冒号兜底。 */
    private fun extractName(beforeParen: String): String? {
        var text = beforeParen.replace(timestampPrefix, "")
        knownNameMacPrefixes.firstOrNull { text.startsWith(it) }?.let { prefix ->
            text = text.substring(prefix.length)
        } ?: run {
            // 未知前缀兜底：取最后一个冒号后的部分（通知/异常文案常见结构）。
            // 冒号必须在行内第一个 MAC 之前搜索——MAC 自身含冒号，否则会把
            // 堆栈里「... 名称 (MAC) ... 重复副本 名称」截成以 MAC 字节开头的伪名称。
            val macStart = macRegex.find(text)?.range?.first ?: text.length
            val idx = text.lastIndexOf(':', macStart - 1).coerceAtLeast(
                text.lastIndexOf('：', macStart - 1),
            )
            if (idx >= 0 && idx + 1 < text.length) text = text.substring(idx + 1)
        }
        val name = text.trim()
        if (name.isEmpty() || name == "?") return null
        if (name.length > MAX_NAME_CHARS) return null
        if (macRegex.matches(name)) return null
        // 候选里若出现「MAC)」收尾结构，说明截取到的是日志文本而非设备名
        //（异常堆栈里内嵌的「... 名称 (MAC) ...」副本），不建立名称关联。
        if (macRegex.findAll(name).any { it.range.last + 1 < name.length && name[it.range.last + 1] == ')' }) {
            return null
        }
        return name
    }

    /** 第二遍：先名称（字面 + JSON 转义，长度从长到短），后全部 MAC（忽略大小写）。 */
    private fun sanitizeLine(line: String): String {
        var out = line
        nameAliases.entries
            .sortedByDescending { it.key.length }
            .forEach { (name, alias) ->
                out = out.replace(name.toRegexLiteral(), alias)
                val escaped = jsonEscape(name)
                if (escaped != name) out = out.replace(escaped.toRegexLiteral(), alias)
            }
        return macRegex.replace(out) { match ->
            macAliases[match.value.lowercase(Locale.ROOT)] ?: match.value
        }
    }

    /** 用户内容按字面量替换：Regex.escape 转义元字符，不解释用户正则。 */
    private fun String.toRegexLiteral(): Regex = Regex(Regex.escape(this))

    /** 与 Android/Org.json 常见输出一致的 JSON 字符串转义形式。 */
    private fun jsonEscape(s: String): String = buildString(s.length + 8) {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    companion object {
        /** 设备名长度上限：超过视为异常/堆栈文本，不建立名称关联。 */
        private const val MAX_NAME_CHARS = 120
    }
}
