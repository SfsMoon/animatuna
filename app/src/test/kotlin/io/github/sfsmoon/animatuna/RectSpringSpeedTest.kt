package io.github.sfsmoon.animatuna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.RectSpring

class RectSpringSpeedTest {

    private val frame = RectSpring.Frame(100f, 500f, 700f, 1800f, 40f)

    @Test
    fun aFlungWindowMovesTheWayTheFingerThrewIt() {
        val spring = RectSpring(frame, Motion.HOME).apply { fling(0f, 3000f) }
        spring.step(0.016f)
        assertTrue(spring.frame().top > frame.top)
        assertEquals(frame.centerX, spring.frame().centerX, 1e-3f)
    }

    @Test
    fun theNextSceneMovesOnAsFastAsTheLast() {
        val flying = RectSpring(frame, Motion.HOME).apply {
            target = RectSpring.Frame(68f, 2382f, 325f, 2639f, 60f)
            step(0.05f)
        }
        val opening = RectSpring(flying.frame(), Motion.LAUNCH).apply { keepSpeedOf(flying) }
        assertEquals(flying.topVelocity, opening.topVelocity, 1e-3f)
        assertEquals(flying.centerXVelocity, opening.centerXVelocity, 1e-3f)
        assertEquals(flying.heightVelocity, opening.heightVelocity, 1e-2f)
    }
}
