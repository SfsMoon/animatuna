package io.github.sfsmoon.animatuna.host

import android.graphics.Rect
import android.os.SystemClock

import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.RectSpring
import io.github.sfsmoon.animatuna.tuning.WindowFit

/**
 * One app's window moving on springs, from its launch on: the same
 * [RectSpring] carries it through the launch and, when a gesture takes it
 * mid-launch, through the gesture, so the window keeps its place and speed
 * from one to the other.
 */
internal class MovingWindow(
    /** The app's task, as the shell names it in each transition's targets. */
    val task: Int,
    /** Where the window stands at full size. */
    val screen: Rect,
    /** How long the springs take, against ColorOS's: the opening duration chosen times the system animator scale. */
    val durationFactor: Float,
    val spring: RectSpring,
) {
    private var steppedAt = SystemClock.uptimeMillis()

    /** How the window is to be shown now, after moving the springs on to now. */
    fun step(): WindowFit {
        val time = SystemClock.uptimeMillis()
        val seconds = (time - steppedAt).coerceIn(0L, MAX_FRAME_MS) / MS_PER_S
        if (seconds > 0f) {
            spring.step(seconds)
            steppedAt = time
        }
        return WindowFit.of(spring.frame(), screen.width().toFloat(), screen.height().toFloat())
    }

    /** Moves on [motion]'s springs from here, keeping the window's speed. */
    fun use(motion: Motion) = spring.use(motion, durationFactor)

    private companion object {
        /** The longest frame the springs step over at once, so a pause never makes the window leap. */
        const val MAX_FRAME_MS = 32L
        const val MS_PER_S = 1000f
    }
}
