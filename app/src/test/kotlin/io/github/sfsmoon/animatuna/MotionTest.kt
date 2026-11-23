package io.github.sfsmoon.animatuna

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.RectSpring
import io.github.sfsmoon.animatuna.tuning.RectSpring.Frame
import io.github.sfsmoon.animatuna.tuning.WindowFit

class MotionTest {

    private val icon = Frame(540f, 2200f, 720f, 2380f, 40f)
    private val screen = Frame(0f, 0f, 1344f, 2992f, 86f)

    @Test
    fun launchSettlesOnTheScreenWithoutOvershoot() {
        val spring = RectSpring(icon, Motion.LAUNCH).apply { target = screen }
        var topMost = Float.MAX_VALUE
        var frames = 0
        while (!spring.settled && frames < 600) {
            val frame = spring.step(1f / 120f)
            topMost = minOf(topMost, frame.top)
            frames++
        }
        assertTrue("never settled", spring.settled)
        // Damping above 1: the window never passes the top of the screen.
        assertTrue(topMost > -1f)
        // ColorOS's launch takes about half a second.
        assertTrue("took $frames frames", frames in 30..90)
    }

    @Test
    fun retargetingKeepsTheWindowMoving() {
        val spring = RectSpring(icon, Motion.LAUNCH).apply { target = screen }
        repeat(10) { spring.step(1f / 120f) }
        val before = spring.frame()
        spring.target = icon
        spring.use(Motion.HOME)
        val next = spring.step(1f / 120f)
        // Still carried up by the launch's speed for a frame, rather than turning at once.
        assertTrue(next.top < before.top)
        assertFalse(spring.settled)
    }

    @Test
    fun aCaughtWindowFollowsTheFingerUntilItCatchesUp() {
        // Caught a tenth of the way into a launch, then held where the gesture puts it.
        val spring = RectSpring(icon, Motion.LAUNCH).apply { target = screen }
        repeat(4) { spring.step(1f / 120f) }
        val finger = Frame(100f, 200f, 1244f, 2747f, 90f)
        spring.target = finger
        spring.use(Motion.FOLLOW)
        assertFalse(spring.near(1.5f))
        var frames = 0
        while (!spring.near(1.5f) && frames < 240) {
            spring.step(1f / 120f)
            frames++
        }
        assertTrue("never caught up", spring.near(1.5f))
        assertTrue("took $frames frames", frames < 90)
    }

    @Test
    fun theSpringTellsHowFastTheWindowMoves() {
        val spring = RectSpring(icon, Motion.LAUNCH).apply { target = screen }
        repeat(6) { spring.step(1f / 120f) }
        // Opening from a low icon: rising, widening, growing taller.
        assertTrue(spring.topVelocity < 0f)
        assertTrue(spring.heightVelocity > 0f)
        val before = spring.frame()
        spring.step(1f / 1000f)
        val after = spring.frame()
        assertEquals(spring.topVelocity / 1000f, after.top - before.top, 2f)
    }

    @Test
    fun slowerAnimationsSoftenTheSprings() {
        assertEquals(105f, Motion.LAUNCH.rect.slowedBy(2f).stiffness, 1e-3f)
        assertEquals(Motion.LAUNCH.rect, Motion.LAUNCH.rect.slowedBy(0f))
    }

    @Test
    fun fullScreenFitIsTheWholeWindow() {
        val fit = WindowFit.of(screen, 1344f, 2992f)
        assertEquals(1f, fit.scale, 1e-4f)
        assertEquals(0f, fit.translateX, 1e-3f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1344f, 2992f), fit.crop, 1e-2f)
        assertEquals(86f, fit.surfaceRadius, 1e-3f)
    }

    @Test
    fun anIconSizedFitCropsTheMiddleOfTheWindowAndShowsItWhereAsked() {
        val fit = WindowFit.of(icon, 1344f, 2992f)
        val matrix = floatArrayOf(fit.scale, 0f, fit.translateX, 0f, fit.scale, fit.translateY, 0f, 0f, 1f)
        val shown = WindowFit.shown(matrix, fit.crop, fit.surfaceRadius)
        assertEquals(icon.left, shown.left, 0.5f)
        assertEquals(icon.top, shown.top, 0.5f)
        assertEquals(icon.right, shown.right, 0.5f)
        assertEquals(icon.bottom, shown.bottom, 0.5f)
        assertEquals(icon.radius, shown.radius, 1e-3f)
        // Square icon on a tall window: the full width shows, the height is cropped around the centre.
        assertEquals(0f, fit.crop[0], 0.5f)
        assertEquals((2992f - 1344f) / 2f, fit.crop[1], 0.5f)
    }
}
