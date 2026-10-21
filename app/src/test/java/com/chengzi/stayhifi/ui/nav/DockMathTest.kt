package com.chengzi.stayhifi.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DockMathTest {

    @Test
    fun dockLayoutSingleDestination() {
        val (pitch, lens, inset) = DockMath.dockLayout(200f, 1)
        assertEquals(192f, pitch, 1e-4f)
        assertEquals(200f, lens, 1e-4f)
        assertEquals(4f, inset, 1e-4f)
    }

    @Test
    fun dockLayoutRegularSpacing() {
        // pitch + 2*BarPadding >= MinLensWidth → 常规分支
        val (pitch, lens, inset) = DockMath.dockLayout(400f, 4)
        assertEquals(98f, pitch, 1e-4f)
        assertEquals(106f, lens, 1e-4f)
        assertEquals(4f, inset, 1e-4f)
    }

    @Test
    fun dockLayoutMinLensTightens() {
        // pitch + 2*BarPadding < MinLensWidth → lens 收紧到 84
        val (pitch, lens, inset) = DockMath.dockLayout(200f, 5)
        assertEquals(29f, pitch, 1e-4f)
        assertEquals(84f, lens, 1e-4f)
        assertEquals(27.5f, inset, 1e-4f)
    }

    @Test
    fun rubberBandIsZeroAtOrigin() {
        assertEquals(0f, DockMath.rubberBand(0f, 10f), 1e-6f)
        assertEquals(0f, DockMath.rubberBand(5f, 0f), 1e-6f)
    }

    @Test
    fun rubberBandBoundedAndSignPreserving() {
        val limit = 8f
        var prev = 0f
        for (i in 0..2000) {
            val x = i / 100f // 0 .. 20
            val y = DockMath.rubberBand(x, limit)
            assertTrue("|$y| > limit at x=$x", abs(y) <= limit)
            assertTrue(y >= 0f)
            assertTrue("not monotone at x=$x", y >= prev)
            prev = y
        }
        assertEquals(-prev, DockMath.rubberBand(-20f, limit), 1e-4f)
        // 渐近接近 limit
        assertTrue(DockMath.rubberBand(1e6f, limit) > limit - 1e-3f)
    }

    @Test
    fun indexAtClamps() {
        assertEquals(0, DockMath.indexAt(-0.4f, 2))
        assertEquals(0, DockMath.indexAt(0.4f, 2))
        assertEquals(1, DockMath.indexAt(0.5f, 2))
        assertEquals(2, DockMath.indexAt(2.6f, 2))
        assertEquals(2, DockMath.indexAt(99f, 2))
    }

    @Test
    fun positionAtRubberBandsPastEdges() {
        // pitch=100, inset=4: position = (x - 8)/100 - 0.5；x=0 → -0.58 → rubberBand
        val atZero = DockMath.positionAt(0f, 100f, 4f, 2)
        assertTrue(atZero < 0f)
        assertTrue(atZero > -DockMath.Overdrag)
        val atEnd = DockMath.positionAt(300f, 100f, 4f, 2) // (300-8)/100-0.5 = 2.42
        assertTrue(atEnd > 2f)
        assertTrue(atEnd < 2f + DockMath.Overdrag)
        assertEquals(1f, DockMath.positionAt(158f, 100f, 4f, 2), 1e-4f)
    }

    @Test
    fun flingStaysPutWithoutVelocity() {
        assertEquals(1, DockMath.flingIndex(1.2f, 0f, 100f, 1, 2))
    }

    @Test
    fun flingAdvancesAtMostOne() {
        // 正方向大速度：最多 pressed+1
        assertEquals(2, DockMath.flingIndex(1.2f, 3000f, 100f, 1, 2))
        assertEquals(2, DockMath.flingIndex(1.2f, 1e9f, 100f, 1, 4))
        // 负方向大速度：最多 pressed-1
        assertEquals(0, DockMath.flingIndex(0.8f, -1e9f, 100f, 1, 4))
        // pressed=0 时负方向被 clamp 到 0
        assertEquals(0, DockMath.flingIndex(0.2f, -1e9f, 100f, 0, 4))
        // pressed=last 时正方向被 clamp 到 last
        assertEquals(4, DockMath.flingIndex(3.8f, 1e9f, 100f, 4, 4))
    }

    @Test
    fun flingRespectsRoundingLead() {
        // lead = v/pitch*0.1：v=900,pitch=100 → lead 0.9，lensTarget+lead 四舍五入决定结果
        assertEquals(2, DockMath.flingIndex(1.2f, 900f, 100f, 1, 4))
        assertEquals(1, DockMath.flingIndex(1.2f, 100f, 100f, 1, 4))
    }
}
