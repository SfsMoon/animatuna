package io.github.sfsmoon.animatuna

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.WindowSpring
import io.github.sfsmoon.animatuna.tuning.WindowSpring.Shown

class WindowSpringTest {

    // A launch caught a fifth of the way (vc5's trace) and the gesture's first place.
    private val launchMatrix = floatArrayOf(0.895f, 0f, -25f, 0f, 0.895f, 616f, 0f, 0f, 1f)
    private val launchCrop = floatArrayOf(195f, 567f, 1148f, 2424f)
    private val gestureMatrix = floatArrayOf(0.93f, 0f, 53f, 0f, 0.93f, 75f, 0f, 0f, 1f)
    private val fullWindow = floatArrayOf(0f, 0f, 1344f, 2991f)

    @Test
    fun whatShowsComesBackAsTheSamePlace() {
        val shown = Shown.of(launchMatrix, launchCrop, 155f)
        val matrix = shown.matrix()
        assertArrayEquals(launchMatrix, matrix, 1e-3f)
        assertArrayEquals(launchCrop, shown.cropToSet(), 1e-2f)
    }

    @Test
    fun theWindowStartsWhereTheLaunchLeftIt() {
        val caught = Shown.of(launchMatrix, launchCrop, 155f)
        val spring = WindowSpring(caught)
        val first = spring.step(Shown.of(gestureMatrix, fullWindow, 90f), 0f)
        assertArrayEquals(caught.visible, first.visible, 1e-3f)
        assertFalse(spring.settled)
    }

    @Test
    fun theWindowSettlesOnTheGesture() {
        val spring = WindowSpring(Shown.of(launchMatrix, launchCrop, 155f))
        val gesture = Shown.of(gestureMatrix, fullWindow, 90f)
        repeat(240) { spring.step(gesture, 1f / 120f) }
        assertTrue(spring.settled)
        assertArrayEquals(gesture.visible, spring.shown().visible, 0.5f)
        assertEquals(90f, spring.shown().radius, 0.5f)
    }

    @Test
    fun theWindowMovesTowardTheGestureFromTheFirstFrame() {
        val caught = Shown.of(launchMatrix, launchCrop, 155f)
        val spring = WindowSpring(caught)
        val gesture = Shown.of(gestureMatrix, fullWindow, 90f)
        val next = spring.step(gesture, 1f / 120f)
        val topBefore = caught.visible[WindowSpring.TOP]
        assertTrue(next.visible[WindowSpring.TOP] < topBefore)
        assertTrue(next.visible[WindowSpring.TOP] > gesture.visible[WindowSpring.TOP])
    }
}
