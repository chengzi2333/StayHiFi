package com.chengzi.stayhifi.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 38：版本比较单测——v 前缀 / 段长不一 / 相等 / 降级 / 多位数值段。
 */
class VersionCompareTest {

    @Test
    fun `remote 带 v 前缀且更高判为新版本`() {
        assertTrue(UpdateChecker.isNewerVersion("v1.1.0", "1.0.0"))
    }

    @Test
    fun `remote 不带前缀也能比较`() {
        assertTrue(UpdateChecker.isNewerVersion("1.1.0", "v1.0.0"))
    }

    @Test
    fun `数值比较非字典序 1_10_0 大于 1_9_0`() {
        assertTrue(UpdateChecker.isNewerVersion("v1.10.0", "1.9.0"))
    }

    @Test
    fun `相等版本不算新`() {
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"))
    }

    @Test
    fun `段长不一缺段补 0 相等`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0"))
    }

    @Test
    fun `段长不一末段非零判为新版本`() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0"))
    }

    @Test
    fun `远程更旧降级不提示`() {
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0", "1.1.0"))
    }

    @Test
    fun `非数值段按 0 兜底不炸`() {
        assertTrue(UpdateChecker.isNewerVersion("v2.0.0-beta", "1.9.9"))
        assertFalse(UpdateChecker.isNewerVersion("v1.0.0-beta", "1.0.0"))
    }
}
