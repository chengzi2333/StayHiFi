package com.chengzi.stayhifi.ui.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * Task 40：GitHub release notes 的轻量 Markdown 渲染（不引第三方库）。
 *
 * 只覆盖我们自己写的更新说明会用到的四条规则：
 * - `**bold**` / `__bold__` → 真粗体 span
 * - 行首 `#`~`######`（后跟空白）→ 去井号、整行粗体
 * - `[文字](url)` → 只留文字（弹窗内不做跳转，URL 反而干扰阅读）
 * - 行内 `` `code` `` → 去反引号、保留内容
 * 其余原样输出（`- ` 列表项按 GitHub 惯例保留）。
 *
 * 容错：标记未闭合（如结尾悬空的 `**`）按原样输出，不崩、不吃字。
 * 纯函数、无 Android 运行时依赖，可本地单测（MarkdownLiteTest）。
 */
fun renderMarkdownLite(markdown: String): AnnotatedString = buildAnnotatedString {
    // GitHub API 返回的 body 可能带 \r\n，逐行处理时统一剥掉 \r
    val lines = markdown.split('\n')
    lines.forEachIndexed { index, rawLine ->
        if (index > 0) append('\n')
        var line = rawLine.removeSuffix("\r")
        // 行首标题规则：`## 标题` → 去井号整行粗体
        val heading = Regex("^#{1,6}\\s+").find(line)
        if (heading != null) {
            line = line.substring(heading.value.length)
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                appendInline(line)
            }
        } else {
            appendInline(line)
        }
    }
}

/**
 * Task 40：行内规则解析——粗体 / 链接 / 行内代码。
 * 粗体内容递归走本函数，嵌套的行内代码、链接在粗体内同样生效。
 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var i = 0
    while (i < text.length) {
        when {
            // 粗体：**…** 或 __…__；找不到闭合标记则标记本身原样输出
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val marker = text.substring(i, i + 2)
                val close = text.indexOf(marker, i + 2)
                if (close < 0) {
                    append(marker)
                    i += 2
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        appendInline(text.substring(i + 2, close))
                    }
                    i = close + 2
                }
            }
            // 行内代码：`…` → 去反引号保留内容；未闭合则反引号原样输出
            text[i] == '`' -> {
                val close = text.indexOf('`', i + 1)
                if (close < 0) {
                    append('`')
                    i += 1
                } else {
                    append(text.substring(i + 1, close))
                    i = close + 1
                }
            }
            // 链接：[文字](url) → 只留文字；缺 ]( 或 ) 则按普通字符输出
            text[i] == '[' -> {
                val mid = text.indexOf("](", i + 1)
                val close = if (mid >= 0) text.indexOf(')', mid + 2) else -1
                if (mid < 0 || close < 0) {
                    append('[')
                    i += 1
                } else {
                    appendInline(text.substring(i + 1, mid))
                    i = close + 1
                }
            }
            else -> {
                append(text[i])
                i += 1
            }
        }
    }
}
