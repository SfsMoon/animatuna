package io.github.sfsmoon.animatuna

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.WindowPlace

class WindowPlaceTest {

    @Test
    fun handoverStartsAtTheLaunchAndEndsAtTheGesture() {
        val launch = floatArrayOf(0.9f, 0f, -25f, 0f, 0.9f, 616f, 0f, 0f, 1f)
        val gesture = floatArrayOf(0.95f, 0f, 30f, 0f, 0.95f, 50f, 0f, 0f, 1f)
        assertArrayEquals(launch, WindowPlace.blend(launch, gesture, 0f), 1e-4f)
        assertArrayEquals(gesture, WindowPlace.blend(launch, gesture, 1f), 1e-4f)
        assertEquals(155f, WindowPlace.blend(155f, 90f, 0f), 1e-4f)
    }

    @Test
    fun nothingCaughtLeavesTheGestureAlone() {
        val gesture = floatArrayOf(0f, 0f, 1344f, 2991f)
        assertArrayEquals(gesture, WindowPlace.blend(null, gesture, 0.3f), 0f)
        assertEquals(90f, WindowPlace.blend(null, 90f, 0.3f), 0f)
    }

    @Test
    fun handoverEasesOutAndEnds() {
        assertEquals(0f, WindowPlace.share(0, 300), 0f)
        assertEquals(1f, WindowPlace.share(300, 300), 0f)
        assertEquals(1f, WindowPlace.share(900, 300), 0f)
        // Eased out: most of the way is gone by half the time.
        assertTrue(WindowPlace.share(150, 300) > 0.8f)
    }
}
