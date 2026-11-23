package io.github.sfsmoon.animatuna

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.BreakFrame
import io.github.sfsmoon.animatuna.tuning.WindowFit

class BreakFrameTest {

    @Test
    fun aFrameCrossesBetweenProcessesUnchanged() {
        val frame = BreakFrame(9900, 6831500L, WindowFit(0.65f, 120.5f, 340f, floatArrayOf(0f, 400f, 1344f, 2592f), 120f))
        val back = BreakFrame.decode(frame.encode())!!
        assertEquals(frame.task, back.task)
        assertEquals(frame.at, back.at)
        assertEquals(frame.fit.scale, back.fit.scale, 1e-6f)
        assertArrayEquals(frame.fit.crop, back.fit.crop, 1e-6f)
    }

    @Test
    fun onlyAFreshFrameStandsAndGarbageIsNone() {
        val frame = BreakFrame(1, 1000L, WindowFit(1f, 0f, 0f, floatArrayOf(0f, 0f, 1f, 1f), 0f))
        assertTrue(frame.freshAt(1150L))
        // A gesture half a second on is another gesture, its window whole again.
        assertFalse(frame.freshAt(1500L))
        assertFalse(frame.freshAt(900L))
        assertNull(BreakFrame.decode(null))
        assertNull(BreakFrame.decode("not;a;frame"))
    }
}
