package io.github.sfsmoon.animatuna.tuning

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A window caught mid-launch, following the gesture on springs rather than
 * jumping to where the gesture puts it.
 *
 * The window is held as what shows of it: its visible rectangle on screen
 * (centre x and y, width and height), the crop of the window that fills it,
 * and its corner radius. Each frame the gesture sets a place ([target]), and
 * each part springs from where it stood toward that place; the gesture's moves
 * change the target, never the window directly. Once every part has settled
 * on its target, the gesture's places stand on their own ([settled]).
 *
 * The springs are the ones a launch caught by the gesture follows on vivo's
 * launcher (`RectFSpringAnim` there): stiffness 200 with damping 0.8 for the
 * rectangle and the corner, 400 with damping 1 for the crop.
 * Kept free of Android so it can be unit tested.
 */
class WindowSpring(start: Shown) {

    /** What shows of a window: its visible rectangle (left, top, right, bottom), the crop filling it, its corner radius. */
    data class Shown(val visible: FloatArray, val crop: FloatArray, val radius: Float) {

        override fun equals(other: Any?): Boolean =
            other is Shown && visible.contentEquals(other.visible) && crop.contentEquals(other.crop) && radius == other.radius

        override fun hashCode(): Int = 31 * (31 * visible.contentHashCode() + crop.contentHashCode()) + radius.hashCode()

        /**
         * The matrix (nine values) that puts [crop] on [visible]: one scale for
         * both axes, from the widths, so the window is never stretched.
         */
        fun matrix(): FloatArray {
            val scale = (visible[RIGHT] - visible[LEFT]) / (crop[RIGHT] - crop[LEFT])
            return floatArrayOf(scale, 0f, visible[LEFT] - crop[LEFT] * scale, 0f, scale, visible[TOP] - crop[TOP] * scale, 0f, 0f, 1f)
        }

        /** The crop as set: as tall as [visible] shows at the scale its width sets. */
        fun cropToSet(): FloatArray {
            val scale = (visible[RIGHT] - visible[LEFT]) / (crop[RIGHT] - crop[LEFT])
            return floatArrayOf(crop[LEFT], crop[TOP], crop[RIGHT], crop[TOP] + (visible[BOTTOM] - visible[TOP]) / scale)
        }

        companion object {
            /** What a window placed by [matrix] (nine values, scale and move only) and cropped to [crop] shows. */
            fun of(matrix: FloatArray, crop: FloatArray, radius: Float): Shown {
                val sx = matrix[0]
                val sy = matrix[4]
                val visible = floatArrayOf(
                    crop[LEFT] * sx + matrix[2], crop[TOP] * sy + matrix[5],
                    crop[RIGHT] * sx + matrix[2], crop[BOTTOM] * sy + matrix[5],
                )
                return Shown(visible, crop.copyOf(), radius)
            }
        }
    }

    private class Spring(var value: Float, val stiffness: Float, val damping: Float, val settleWithin: Float) {
        var velocity = 0f

        fun step(target: Float, seconds: Float) {
            // Semi-implicit Euler in small steps: steady at the stiffness used here.
            var left = seconds
            while (left > 0f) {
                val dt = minOf(left, MAX_STEP_S)
                val force = -stiffness * (value - target) - 2f * damping * sqrt(stiffness) * velocity
                velocity += force * dt
                value += velocity * dt
                left -= dt
            }
        }

        fun settledOn(target: Float) = abs(value - target) < settleWithin && abs(velocity) < settleWithin * SETTLE_SPEED
    }

    // Visible rectangle as centre and size; crop as its four sides; the corner.
    private val centreX = rect(start.visible[LEFT] + start.visible[RIGHT], half = true)
    private val centreY = rect(start.visible[TOP] + start.visible[BOTTOM], half = true)
    private val width = rect(start.visible[RIGHT] - start.visible[LEFT])
    private val height = Spring(start.visible[BOTTOM] - start.visible[TOP], CLIP_STIFFNESS, CLIP_DAMPING, SETTLE_PX)
    private val crop = Array(SIDES) { Spring(start.crop[it], CLIP_STIFFNESS, CLIP_DAMPING, SETTLE_PX) }
    private val radius = Spring(start.radius, RECT_STIFFNESS, RECT_DAMPING, SETTLE_PX)

    private var target: Shown = start

    /** Whether every part has settled on the last target. */
    var settled = false
        private set

    /** Moves each part [seconds] toward [target], and says what shows now. */
    fun step(target: Shown, seconds: Float): Shown {
        this.target = target
        val v = target.visible
        centreX.step((v[LEFT] + v[RIGHT]) / 2f, seconds)
        centreY.step((v[TOP] + v[BOTTOM]) / 2f, seconds)
        width.step(v[RIGHT] - v[LEFT], seconds)
        height.step(v[BOTTOM] - v[TOP], seconds)
        crop.forEachIndexed { side, spring -> spring.step(target.crop[side], seconds) }
        radius.step(target.radius, seconds)
        settled = centreX.settledOn((v[LEFT] + v[RIGHT]) / 2f) && centreY.settledOn((v[TOP] + v[BOTTOM]) / 2f) &&
            width.settledOn(v[RIGHT] - v[LEFT]) && height.settledOn(v[BOTTOM] - v[TOP]) &&
            crop.indices.all { crop[it].settledOn(target.crop[it]) } && radius.settledOn(target.radius)
        return shown()
    }

    /** What shows of the window now. */
    fun shown(): Shown {
        val halfWidth = width.value / 2f
        val halfHeight = height.value / 2f
        return Shown(
            floatArrayOf(centreX.value - halfWidth, centreY.value - halfHeight, centreX.value + halfWidth, centreY.value + halfHeight),
            FloatArray(SIDES) { crop[it].value },
            radius.value,
        )
    }

    private fun rect(value: Float, half: Boolean = false) =
        Spring(if (half) value / 2f else value, RECT_STIFFNESS, RECT_DAMPING, SETTLE_PX)

    companion object {
        const val LEFT = 0
        const val TOP = 1
        const val RIGHT = 2
        const val BOTTOM = 3
        private const val SIDES = 4

        private const val RECT_STIFFNESS = 200f
        private const val RECT_DAMPING = 0.8f
        private const val CLIP_STIFFNESS = 400f
        private const val CLIP_DAMPING = 1f

        /** Close enough, in pixels, and slow enough, in pixels a second per pixel, to stop. */
        private const val SETTLE_PX = 0.5f
        private const val SETTLE_SPEED = 20f
        private const val MAX_STEP_S = 0.004f
    }
}
