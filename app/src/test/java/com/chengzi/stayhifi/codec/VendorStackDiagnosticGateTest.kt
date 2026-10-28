package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task B5：有界去重闸——LRU 上限、suppressedCount 累计/drain、manual force、reset。 */
class VendorStackDiagnosticGateTest {

    private fun entry(codecType: Int, priority: Int = 0) =
        CodecDeviceFixtures.unverified(codecType, 1, bitsPerSample = 1).copy(priority = priority)

    private fun snapshot(selectable: List<CodecConfig>, local: List<CodecConfig> = selectable) =
        CodecStateSnapshot(
            current = selectable.firstOrNull(),
            selectable = selectable,
            localCapabilities = local,
            readStatus = CodecReadStatus(
                localCount = local.size,
                selectableCount = selectable.size,
                bluetoothState = "ON",
                a2dpProxyState = "CONNECTED",
            ),
            identityDiagnostics = IdentityDiagnostics(supportedTypesStatus = ApiReadState.OK),
        )

    @Test
    fun signatureUsesUntruncatedCapabilities() {
        // 仅第 17 条 selectable 改变（超出渲染上限 16）也必须产生新 signature。
        val selectableA = (1..17).map { entry(200 + it, priority = it) }
        val selectableB = selectableA.dropLast(1) + entry(299, priority = 99)
        val snapA = snapshot(selectableA)
        val snapB = snapshot(selectableB)
        val sigA = VendorStackDiagnostics.signature(snapA, "mac-a", emptySet())
        val sigA2 = VendorStackDiagnostics.signature(snapA, "mac-a", emptySet())
        val sigB = VendorStackDiagnostics.signature(snapB, "mac-a", emptySet())

        assertEquals(sigA, sigA2) // 完全相同快照 → 相同签名
        assertNotEquals(sigA, sigB) // 截断边界外的变化也留证
        // mac 参与签名。
        assertNotEquals(sigA, VendorStackDiagnostics.signature(snapA, "mac-b", emptySet()))
        // reason 集合参与签名且与顺序无关。
        assertEquals(
            VendorStackDiagnostics.signature(snapA, "mac-a", sortedSetOf("a", "b")),
            VendorStackDiagnostics.signature(snapA, "mac-a", sortedSetOf("b", "a")),
        )
        assertNotEquals(
            sigA,
            VendorStackDiagnostics.signature(snapA, "mac-a", setOf("a")),
        )
    }

    @Test
    fun lruEvictsEldestAfter64Signatures() {
        val gate = VendorStackDiagnosticGate()
        for (i in 1..64) {
            assertTrue("sig-$i 首次应记录", gate.record("sig-$i").shouldRecord)
        }
        // 第 65 个新签名触发有界淘汰（LRU 只限内存，不宣称持久去重）。
        assertTrue(gate.record("sig-65").shouldRecord)
        assertFalse(gate.record("sig-65").shouldRecord)
        // 最久未访问的 sig-1 已被淘汰，可再次记录。
        assertTrue(gate.record("sig-1").shouldRecord)
        // 重新插入 sig-1 又占满容量、淘汰当前最久的 sig-2（真 LRU 语义）。
        assertTrue(gate.record("sig-2").shouldRecord)
        // sig-2 的再插入淘汰了 sig-3；远离淘汰边界的 sig-4 仍在缓存内，重复被抑制。
        assertFalse(gate.record("sig-4").shouldRecord)
    }

    @Test
    fun duplicatesAccumulateSuppressedUntilNextAcceptedEvent() {
        val gate = VendorStackDiagnosticGate()
        assertEquals(VendorStackDiagnosticGate.Decision(true, 0), gate.record("sig-a"))
        assertEquals(VendorStackDiagnosticGate.Decision(false, 1), gate.record("sig-a"))
        assertEquals(VendorStackDiagnosticGate.Decision(false, 2), gate.record("sig-a"))
        // 导出 drain 附总数并清零。
        assertEquals(2, gate.drainSuppressedCount())
        assertEquals(0, gate.drainSuppressedCount())
        // 重复计数被 drain 后，下一接受事件不再携带。
        assertEquals(VendorStackDiagnosticGate.Decision(true, 0), gate.record("sig-b"))
        // 重新累计。
        assertEquals(VendorStackDiagnosticGate.Decision(false, 1), gate.record("sig-a"))
        assertEquals(VendorStackDiagnosticGate.Decision(true, 1), gate.record("sig-c"))
    }

    @Test
    fun manualForceRecordsWithoutDuplicatingOrSwallowingCounts() {
        val gate = VendorStackDiagnosticGate()
        gate.record("sig-a")
        gate.record("sig-a") // pending=1
        // manual force：记录，但不增加重复计数。
        val forced = gate.record("sig-a", force = true)
        assertTrue(forced.shouldRecord)
        assertEquals(0, forced.suppressedCount)
        // pending 未被吞掉：下一接受事件仍携带 1。
        assertEquals(VendorStackDiagnosticGate.Decision(true, 1), gate.record("sig-b"))
    }

    @Test
    fun resetAllowsSameSignatureAgainAndClearsCounts() {
        val gate = VendorStackDiagnosticGate()
        gate.record("sig-a")
        gate.record("sig-a")
        gate.reset()
        assertEquals(VendorStackDiagnosticGate.Decision(true, 0), gate.record("sig-a"))
        assertEquals(0, gate.drainSuppressedCount())
    }

    @Test
    fun duplicateRecordTouchesLruRecency() {
        val gate = VendorStackDiagnosticGate()
        // 填满 64 项，插入序 sig-1 在最久端。
        for (i in 1..64) gate.record("sig-$i")
        // 重复记录触碰 sig-1，使其回到热端（不产生新证据）。
        assertFalse(gate.record("sig-1").shouldRecord)
        // 第 65 个新签名淘汰的是最久未访问的 sig-2 而非 sig-1。
        assertTrue(gate.record("sig-65").shouldRecord)
        assertTrue(gate.record("sig-2").shouldRecord) // sig-2 已被淘汰
        assertFalse(gate.record("sig-1").shouldRecord) // sig-1 仍在缓存
    }
}
