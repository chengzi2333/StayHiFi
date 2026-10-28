package com.chengzi.stayhifi.codec

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task B5：通用 recorder——跃迁留证、suppressedCount 汇总行、manual 每次留快照、
 * 失败 root cause 解包与预算。写入行数即返回值，全部走 fake log sink。
 */
class VendorStackDiagnosticRecorderTest {

    private val lines = mutableListOf<String>()
    private val recorder = VendorStackDiagnosticRecorder(log = { lines += it })

    private val phzCurrent = CodecDeviceFixtures.unverified(19, 32, bitsPerSample = 2)

    /** 每个快照都带一个持久 reason（蓝牙 OFF），使跃迁行必然可记录。 */
    private fun offReadStatus(currentNull: Boolean, localCount: Int, selectableCount: Int) =
        CodecReadStatus(
            codecStatusNull = false,
            currentNull = currentNull,
            localCount = localCount,
            selectableCount = selectableCount,
            currentSelectable = if (currentNull) null else true,
            bluetoothState = "OFF",
            a2dpProxyState = "CONNECTED",
            codecStatusState = ApiReadState.OK,
            currentState = if (currentNull) ApiReadState.NULL else ApiReadState.OK,
            localCapabilitiesState = ApiReadState.OK,
            selectableState = ApiReadState.OK,
        )

    private fun snapshot(
        current: CodecConfig? = phzCurrent,
        selectable: List<CodecConfig> = listOf(phzCurrent),
        local: List<CodecConfig> = listOf(phzCurrent),
    ) = CodecStateSnapshot(
        current = current,
        selectable = selectable,
        localCapabilities = local,
        readStatus = offReadStatus(current == null, local.size, selectable.size),
        identityDiagnostics = IdentityDiagnostics(supportedTypesStatus = ApiReadState.OK),
    )

    private fun context(trigger: DiagnosticTrigger = DiagnosticTrigger.CONNECTION) =
        VendorDiagnosticContext(
            mac = "AA:BB:CC:DD:EE:FF",
            trigger = trigger,
            source = if (trigger == DiagnosticTrigger.MANUAL) DiagnosticSource.MANUAL else DiagnosticSource.HOME,
            timestampMillis = 1_700_000_000_000L,
            providerKind = "shizuku",
            device = DiagnosticDeviceMetadata(
                marketModel = "TestModel", androidRelease = "15", sdkInt = 35,
                buildDisplay = "display.1", manufacturer = "mfr", hardware = "hw",
                board = "board", socManufacturer = "socm", socModel = "socx",
            ),
        )

    @Test
    fun emptyToNonEmptyAndNullToValueTransitionsProduceNewEvidence() {
        // selectable=[] → [cap]：同 mac/reason 下空到非空各记录一次。
        assertEquals(1, recorder.record(snapshot(selectable = emptyList()), context()))
        // 完全相同快照重复 3 次只首条留证。
        repeat(3) { assertEquals(0, recorder.record(snapshot(selectable = emptyList()), context())) }
        assertEquals(1, lines.size)
        // 跃迁到非空：新证据行携带 suppressedCount=3。
        assertEquals(1, recorder.record(snapshot(selectable = listOf(phzCurrent)), context()))
        assertEquals(2, lines.size)
        assertTrue("跃迁行应携带 suppressedCount=3: ${lines[1]}", lines[1].contains("suppressedCount=3"))
        // current=null → valid：null 到有效值再各记录一次。
        assertEquals(1, recorder.record(snapshot(current = null, selectable = listOf(phzCurrent)), context()))
        assertEquals(3, lines.size)
        assertTrue(lines[2].contains("currentNull"))
        assertTrue(lines[2].contains("current=null"))
        repeat(2) {
            assertEquals(0, recorder.record(snapshot(current = null, selectable = listOf(phzCurrent)), context()))
        }
        // 回到有效 current（selectable 换成此前未出现的能力条目，构成真正的跃迁）：
        // 跃迁行携带 suppressedCount=2。
        val newCap = phzCurrent.copy(cs1 = 0x8005L)
        assertEquals(
            1,
            recorder.record(snapshot(current = phzCurrent, selectable = listOf(newCap)), context()),
        )
        assertEquals(4, lines.size)
        assertTrue("跃迁行应携带 suppressedCount=2: ${lines[3]}", lines[3].contains("suppressedCount=2"))
        assertTrue(lines[3].contains("reasons=[${VendorStackDiagnostics.REASON_BLUETOOTH_OFF}]"))
    }

    @Test
    fun signatureUsesUntruncatedCapabilitiesAndOutputStaysBounded() {
        // 8 local / 17 selectable（超渲染上限 1 条）/ 32 supportedTypes / 24 条 240 字符线索。
        val local = (1..8).map { CodecDeviceFixtures.unverified(100 + it, 1).copy(priority = it) }
        val selectable = (1..17).map { CodecDeviceFixtures.unverified(200 + it, 2).copy(priority = it) }
        val supportedTypes = (1..32).map { SupportedCodecType(300 + it, (300 + it).toLong(), "codec-$it") }
        val clues = (1..24).map { ("clue-$it-" + "x".repeat(300)).take(240) }
        val big = CodecStateSnapshot(
            current = phzCurrent,
            selectable = selectable,
            localCapabilities = local,
            supportedTypes = supportedTypes,
            readStatus = CodecReadStatus(
                localCount = local.size,
                selectableCount = selectable.size,
                currentSelectable = true,
                bluetoothState = "ON",
                a2dpProxyState = "CONNECTED",
            ),
            identityDiagnostics = IdentityDiagnostics(supportedTypesStatus = ApiReadState.OK),
        )
        val ctx = context().copy(dumpClues = clues)
        val event = VendorStackDiagnostics.format(big, ctx, snapshotSeq = 1, suppressedCount = 0)

        assertTrue("总字符应<=12000: ${event.text.length}", event.text.length <= 12_000)
        // 条数上限：第 17 条 selectable 被截断且计入 truncatedCount，其余完整存在。
        assertEquals(1, event.truncatedCount)
        assertTrue(event.text.contains("truncatedCount=1"))
        assertTrue("第 16 条 selectable 应存在", event.text.contains("t=216"))
        assertFalse("第 17 条 selectable 不应渲染", event.text.contains("t=217"))
        assertTrue("local 8 条完整", (1..8).all { event.text.contains("t=10$it") })
        assertTrue("supportedTypes 32 条完整", (1..32).all { event.text.contains("name=codec-$it") })
        assertTrue("dumpClues 24 条完整", (1..24).all { event.text.contains("clue-$it-") })
        // current 与 readStatus 必须完整存在。
        assertTrue(event.text.contains("current=c{t=19"))
        assertTrue(event.text.contains("readStatus={codecStatusNull=false"))
        // 仅第 17 条改变也产生新 signature（完整未截断能力参与签名）。
        val changed = big.copy(
            selectable = selectable.dropLast(1) + selectable.last().copy(codecType = 299),
        )
        assertNotNull(event.signature)
        assertNotNull(VendorStackDiagnostics.signature(changed, ctx.mac, emptySet()))
        assertFalse(
            "截断边界外的变化必须改变签名",
            event.signature == VendorStackDiagnostics.signature(changed, ctx.mac, VendorStackDiagnostics.reasons(changed)),
        )
    }

    @Test
    fun healthySnapshotWithoutReasonsIsNotRecorded() {
        val healthy = CodecStateSnapshot(
            current = CodecDeviceFixtures.unverified(0, 1, bitsPerSample = 1),
            selectable = listOf(CodecDeviceFixtures.unverified(0, 1, bitsPerSample = 1)),
            localCapabilities = listOf(CodecDeviceFixtures.unverified(0, 1, bitsPerSample = 1)),
            supportedTypes = listOf(SupportedCodecType(0, 1L, "SBC")),
            readStatus = CodecReadStatus(
                localCount = 1, selectableCount = 1, currentSelectable = true,
                bluetoothState = "ON", a2dpProxyState = "CONNECTED",
                localCapabilitiesState = ApiReadState.OK,
            ),
            identityDiagnostics = IdentityDiagnostics(
                supportedTypesStatus = ApiReadState.OK,
                extendedTypeStatus = ApiReadState.OK,
            ),
        )
        assertEquals(0, recorder.record(healthy, context()))
        assertTrue(lines.isEmpty())
    }

    @Test
    fun manualRecordsSnapshotEveryTimeEvenWhenHealthy() {
        val healthy = CodecStateSnapshot(
            current = CodecDeviceFixtures.unverified(1, 2, bitsPerSample = 1),
            selectable = listOf(CodecDeviceFixtures.unverified(1, 2, bitsPerSample = 1)),
            localCapabilities = listOf(CodecDeviceFixtures.unverified(1, 2, bitsPerSample = 1)),
            supportedTypes = listOf(SupportedCodecType(1, 2L, "AAC")),
            readStatus = CodecReadStatus(
                localCount = 1, selectableCount = 1, currentSelectable = true,
                bluetoothState = "ON", a2dpProxyState = "CONNECTED",
                localCapabilitiesState = ApiReadState.OK,
            ),
            identityDiagnostics = IdentityDiagnostics(
                supportedTypesStatus = ApiReadState.OK,
                extendedTypeStatus = ApiReadState.OK,
            ),
        )
        val manual = context(trigger = DiagnosticTrigger.MANUAL)
        assertEquals(1, recorder.record(healthy, manual))
        assertEquals(1, recorder.record(healthy, manual))
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.contains("trigger=manual") && it.contains("source=manual") })
        // manual force 不增加重复计数。
        assertEquals(0, recorder.drainSuppressedCount())
    }

    @Test
    fun failureRootCauseUnwrapsAtMostThreeLayersAndTruncatesEachLayer() {
        val core = RuntimeException("core-cause")
        val inner = IndexOutOfBoundsException("inner").apply { initCause(core) }
        val mid = IllegalArgumentException("mid", inner)
        val outer = IllegalStateException("y".repeat(500), mid)

        assertEquals(1, recorder.recordFailure(context(), "readConfig 解析", outer))
        assertEquals(1, lines.size)
        val line = lines.single()
        assertTrue(line.contains("VendorStackDiagFailure v=1"))
        assertTrue(line.contains("stage=readConfig 解析"))
        // 最多解包 3 层：outer/mid/inner 保留，core 不出现。
        assertTrue(line.contains("IllegalStateException"))
        assertTrue(line.contains("IllegalArgumentException"))
        assertTrue(line.contains("IndexOutOfBoundsException"))
        assertFalse(line.contains("core-cause"))
        // 每层截断 240 字符：500 字符消息不得全量出现。
        assertFalse(line.contains("y".repeat(300)))
        assertTrue(line.contains("…"))
        // systemToString 交叉证据存在。
        assertTrue(line.contains("systemToString=java.lang.IllegalStateException"))
    }

    @Test
    fun repeatedIdenticalFailureIsSuppressed() {
        val error = IllegalStateException("read failed")
        assertEquals(1, recorder.recordFailure(context(), "readConfig 解析", error))
        assertEquals(0, recorder.recordFailure(context(), "readConfig 解析", error))
        assertEquals(1, lines.size)
        assertEquals(1, recorder.drainSuppressedCount())
        assertEquals(0, recorder.drainSuppressedCount())
    }

    @Test
    fun drainSuppressedCountFeedsCopySummaryLine() {
        recorder.record(snapshot(), context())
        repeat(4) { recorder.record(snapshot(), context()) }
        assertEquals("复制前 drain 应得 4", 4, recorder.drainSuppressedCount())
        assertEquals(0, recorder.drainSuppressedCount())
        // drain 不清签名：同一快照仍被抑制。
        assertEquals(0, recorder.record(snapshot(), context()))
    }

    @Test
    fun resetAllowsSameEvidenceAgain() {
        recorder.record(snapshot(), context())
        recorder.reset()
        assertEquals(1, recorder.record(snapshot(), context()))
        assertEquals(2, lines.size)
    }

    @Test
    fun outputKeepsNullEmptyAndErrorDistinct() {
        recorder.record(snapshot(current = null, selectable = listOf(phzCurrent)), context())
        val line = lines.single()
        // current null、列表非空、无读取异常：三者文案不互相合成。
        assertTrue(line.contains("current=null"))
        assertTrue(line.contains("currentNull=true"))
        assertTrue(line.contains("reasons=[${VendorStackDiagnostics.REASON_BLUETOOTH_OFF},${VendorStackDiagnostics.REASON_CURRENT_NULL}]"))
        assertTrue(line.contains("selectable=["))
        assertFalse(line.contains("errors={current="))
        assertNull(snapshot(current = null).current)
        assertFalse(line.contains("current=c{t="))
    }
}
