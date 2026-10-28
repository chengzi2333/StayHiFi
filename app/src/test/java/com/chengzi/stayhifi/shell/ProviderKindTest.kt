package com.chengzi.stayhifi.shell

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Task D2：ProviderKind 字符串持久化映射（设计 §11.3）。
 * DataStore 只存字符串 shizuku/root，不存 enum ordinal；
 * 缺失/未知/大小写不符一律回 SHIZUKU（Coordinator 侧另记一条回退日志）。
 */
class ProviderKindTest {

    @Test
    fun nullOrMissingFallsBackToShizuku() {
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored(null))
    }

    @Test
    fun exactRootSelectsRoot() {
        assertEquals(ProviderKind.ROOT, ProviderKind.fromStored("root"))
    }

    @Test
    fun exactShizukuSelectsShizuku() {
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored("shizuku"))
    }

    @Test
    fun unknownValueFallsBackToShizuku() {
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored("magisk"))
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored(""))
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored(" root "))
    }

    @Test
    fun caseMismatchFallsBackToShizuku() {
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored("Root"))
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored("ROOT"))
        assertEquals(ProviderKind.SHIZUKU, ProviderKind.fromStored("Shizuku"))
    }

    @Test
    fun storedStringsAreStable() {
        assertEquals("shizuku", ProviderKind.SHIZUKU.stored)
        assertEquals("root", ProviderKind.ROOT.stored)
    }
}
