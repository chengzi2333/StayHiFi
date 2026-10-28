package com.chengzi.stayhifi.ui.home

import com.chengzi.stayhifi.trigger.TargetAssessment
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeTargetStatusTest {

    @Test
    fun incompleteCurrentUsesNeutralStatusAndBadge() {
        assertEquals(
            "暂无法读取当前参数",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.INCOMPLETE_CURRENT,
                fullBars = false,
            ),
        )
        assertEquals(
            "待确认",
            HomeTargetStatus.badgeLabel(TargetAssessment.Status.INCOMPLETE_CURRENT, fullBars = false),
        )
    }

    @Test
    fun degradedCapabilityCannotMasqueradeAsFullBars() {
        assertEquals(
            "最高能力暂无法确认",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.CAPABILITY_DEGRADED,
                fullBars = true,
            ),
        )
        assertEquals(
            "待确认",
            HomeTargetStatus.badgeLabel(TargetAssessment.Status.CAPABILITY_DEGRADED, fullBars = true),
        )
    }

    @Test
    fun completeAssessmentUsesFullBarsResult() {
        assertEquals(
            "已达本机最高音质输出",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = true,
            ),
        )
        assertEquals(
            "满血",
            HomeTargetStatus.badgeLabel(TargetAssessment.Status.COMPLETE, fullBars = true),
        )
        assertEquals(
            "未拉满，点下方按钮重试",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = false,
            ),
        )
        assertEquals(
            "未满血",
            HomeTargetStatus.badgeLabel(TargetAssessment.Status.COMPLETE, fullBars = false),
        )
    }

    @Test
    fun disconnectedStatusTakesPriority() {
        assertEquals(
            "等待耳机连接",
            HomeTargetStatus.statusLine(
                connected = false,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = true,
            ),
        )
    }

    @Test
    fun manualActiveShowsCustomAndNeverMasqueradesFullBars() {
        // 手动降档生效中：不冒充满血/本机最高，只显示「自定义」
        assertEquals(
            "自定义参数生效中",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = false,
                manualActive = true,
            ),
        )
        assertEquals(
            "自定义",
            HomeTargetStatus.badgeLabel(
                TargetAssessment.Status.COMPLETE, fullBars = false, manualActive = true,
            ),
        )
        // 即使读回恰好等于本机最高，自定义会话仍只显示自定义（不抢占满血语义）
        assertEquals(
            "自定义",
            HomeTargetStatus.badgeLabel(
                TargetAssessment.Status.COMPLETE, fullBars = true, manualActive = true,
            ),
        )
        assertEquals(
            "自定义参数生效中",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = true,
                manualActive = true,
            ),
        )
    }

    @Test
    fun manualActiveDoesNotOverrideDisconnected() {
        assertEquals(
            "等待耳机连接",
            HomeTargetStatus.statusLine(
                connected = false, status = null, fullBars = false, manualActive = true,
            ),
        )
    }

    @Test
    fun missingAssessmentCannotShowFullBars() {
        assertEquals(
            "未拉满，点下方按钮重试",
            HomeTargetStatus.statusLine(connected = true, status = null, fullBars = true),
        )
        assertEquals("未满血", HomeTargetStatus.badgeLabel(status = null, fullBars = true))
    }

    @Test
    fun connectedButParamsUnavailableNeverPromptsRetryClick() {
        // v1.2.2：已连接但特权参数读不到——禁用按钮不能配「点下方按钮重试」文案
        assertEquals(
            "已连接，授权后可用",
            HomeTargetStatus.statusLine(
                connected = true, status = null, fullBars = false, paramsAvailable = false,
            ),
        )
        assertEquals(
            "已连接，授权后可用",
            HomeTargetStatus.statusLine(
                connected = true,
                status = TargetAssessment.Status.COMPLETE,
                fullBars = true,
                paramsAvailable = false,
            ),
        )
        assertEquals(
            "待确认",
            HomeTargetStatus.badgeLabel(
                status = TargetAssessment.Status.COMPLETE, fullBars = true, paramsAvailable = false,
            ),
        )
    }

    @Test
    fun disconnectedStillTakesPriorityOverParamsUnavailable() {
        assertEquals(
            "等待耳机连接",
            HomeTargetStatus.statusLine(
                connected = false, status = null, fullBars = false, paramsAvailable = false,
            ),
        )
    }
}
