package com.chengzi.stayhifi.ui.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 40：MarkdownLite 单测——粗体 / 标题 / 链接 / 行内代码四条规则 +
 * 嵌套与未闭合标记容错（未闭合按原样输出，不崩不吃字）。
 */
class MarkdownLiteTest {

    /** 取出全部粗体 span 覆盖的文本段，便于断言范围 */
    private fun boldRangesOf(result: AnnotatedString): List<String> =
        result.spanStyles
            .filter { it.item.fontWeight == FontWeight.Bold }
            .map { result.text.substring(it.start, it.end) }

    @Test
    fun `双星号粗体去标记加粗体span`() {
        val result = renderMarkdownLite("修复 **断连** 问题")
        assertEquals("修复 断连 问题", result.text)
        assertEquals(listOf("断连"), boldRangesOf(result))
    }

    @Test
    fun `双下划线粗体同样生效`() {
        val result = renderMarkdownLite("__重要__ 更新")
        assertEquals("重要 更新", result.text)
        assertEquals(listOf("重要"), boldRangesOf(result))
    }

    @Test
    fun `行首井号标题去井号整行粗体`() {
        val result = renderMarkdownLite("## What's Changed\n- 条目")
        assertEquals("What's Changed\n- 条目", result.text)
        assertEquals(listOf("What's Changed"), boldRangesOf(result))
    }

    @Test
    fun `一级标题同样生效且列表项原样保留`() {
        val result = renderMarkdownLite("# v1.1.0\n- 新特性")
        assertEquals("v1.1.0\n- 新特性", result.text)
        assertEquals(listOf("v1.1.0"), boldRangesOf(result))
    }

    @Test
    fun `链接只留文字去掉URL`() {
        val result = renderMarkdownLite("详见 [发布页](https://github.com/a/b/releases) 说明")
        assertEquals("详见 发布页 说明", result.text)
        assertTrue(boldRangesOf(result).isEmpty())
    }

    @Test
    fun `行内代码去反引号保留内容`() {
        val result = renderMarkdownLite("执行 `adb shell` 命令")
        assertEquals("执行 adb shell 命令", result.text)
    }

    @Test
    fun `普通文本与空串原样输出`() {
        assertEquals("普通更新说明", renderMarkdownLite("普通更新说明").text)
        assertEquals("", renderMarkdownLite("").text)
    }

    @Test
    fun `未闭合粗体按原样输出不崩不吃字`() {
        val result = renderMarkdownLite("结尾悬空的 **星号")
        assertEquals("结尾悬空的 **星号", result.text)
        assertTrue(boldRangesOf(result).isEmpty())
    }

    @Test
    fun `未闭合反引号与方括号按原样输出`() {
        assertEquals("半个 `反引号", renderMarkdownLite("半个 `反引号").text)
        assertEquals("半个 [链接", renderMarkdownLite("半个 [链接").text)
        assertEquals("缺右括号 [文](url", renderMarkdownLite("缺右括号 [文](url").text)
    }

    @Test
    fun `粗体内嵌套行内代码与链接同样解析`() {
        val result = renderMarkdownLite("**修复 `codec` 协商，见 [日志](https://x)**")
        assertEquals("修复 codec 协商，见 日志", result.text)
        // 整段都在粗体 span 内（嵌套 span 允许拆段，故拼接断言）
        assertEquals("修复 codec 协商，见 日志", boldRangesOf(result).joinToString(""))
    }

    @Test
    fun `多行混合场景井号粗体链接代码各归其位`() {
        val body = "## 新特性\n- 支持 **LDAC** 强制\n- 详见 [文档](https://d)\n- 标记 `beta`"
        val result = renderMarkdownLite(body)
        assertEquals("新特性\n- 支持 LDAC 强制\n- 详见 文档\n- 标记 beta", result.text)
        assertEquals(listOf("新特性", "LDAC"), boldRangesOf(result))
    }

    @Test
    fun `CRLF换行不残留回车符`() {
        val result = renderMarkdownLite("## 标题\r\n- 条目\r\n")
        assertEquals("标题\n- 条目\n", result.text)
    }
}
