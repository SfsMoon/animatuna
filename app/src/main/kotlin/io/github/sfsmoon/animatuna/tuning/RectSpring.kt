package io.github.sfsmoon.animatuna.tuning

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A window's visible rectangle and corner on springs, as ColorOS moves an
 * app's window: its horizontal centre, its top edge, its width, its shape
 * (height to width) and its corner radius each spring toward a target on
 * their own, so the window can change size and shape at different paces. As
 * on ColorOS (`CustomRectFSpringAnim`, tracking the top), the window hangs
 * from its top edge: its height grows downward from there. A new target, or a new [Motion],
 * keeps where each part is and how fast it moves: one movement, retargeted.
 * Kept free of Android so it can be unit tested.
 */
class RectSpring(start: Frame, private var motion: Motion) {

    /** A visible rectangle (left, top, right, bottom on screen) and its corner radius, in screen pixels. */
    data class Frame(val left: Float, val top: Float, val right: Float, val bottom: Float, val radius: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
    }

    private class Part(var value: Float, var spring: Spring, val settleWithin: Float) {
        var velocity = 0f

        fun step(target: Float, seconds: Float) {
            var left = seconds
            while (left > 0f) {
                val dt = minOf(left, MAX_STEP_S)
                val force = -spring.stiffness * (value - target) - 2f * spring.damping * sqrt(spring.stiffness) * velocity
                velocity += force * dt
                value += velocity * dt
                left -= dt
            }
        }

        fun settledOn(target: Float) = abs(value - target) < settleWithin && abs(velocity) < settleWithin * SETTLE_SPEED
    }

    private val centerX = Part(start.centerX, motion.rect, SETTLE_PX)
    private val top = Part(start.top, motion.rect, SETTLE_PX)
    private val width = Part(start.width, motion.rect, SETTLE_PX)
    private val shape = Part(start.height / start.width, motion.shape, SETTLE_SHAPE)
    private val radius = Part(start.radius, motion.corner, SETTLE_PX)

    /** Where the window is headed. */
    var target: Frame = start

    /** Whether every part has settled on [target]. */
    val settled: Boolean
        get() = centerX.settledOn(target.centerX) && top.settledOn(target.top) && width.settledOn(target.width) &&
            shape.settledOn(target.height / target.width) && radius.settledOn(target.radius)

    /** Whether the window stands within [pixels] of [target] in every part, however fast it moves. */
    fun near(pixels: Float): Boolean {
        val now = frame()
        return abs(now.left - target.left) < pixels && abs(now.top - target.top) < pixels &&
            abs(now.right - target.right) < pixels && abs(now.bottom - target.bottom) < pixels &&
            abs(now.radius - target.radius) < pixels
    }

    /** Moves on the springs of [next] from here, keeping each part's speed. */
    fun use(next: Motion, durationFactor: Float = 1f) {
        motion = next
        centerX.spring = next.rect.slowedBy(durationFactor)
        top.spring = next.rect.slowedBy(durationFactor)
        width.spring = next.rect.slowedBy(durationFactor)
        shape.spring = next.shape.slowedBy(durationFactor)
        radius.spring = next.corner.slowedBy(durationFactor)
    }

    /** Moves every part [seconds] toward [target], and says where the window is now. */
    fun step(seconds: Float): Frame {
        centerX.step(target.centerX, seconds)
        top.step(target.top, seconds)
        width.step(target.width, seconds)
        shape.step(target.height / target.width, seconds)
        radius.step(target.radius, seconds)
        return frame()
    }

    /** Sets how fast the window's horizontal centre and top edge move, in pixels a second, as a finger flings it. */
    fun fling(centerXVelocity: Float, topVelocity: Float) {
        centerX.velocity = centerXVelocity
        top.velocity = topVelocity
    }

    /** Moves on, part by part, as fast as [other] moves: the next scene inheriting the last one's speed. */
    fun keepSpeedOf(other: RectSpring) {
        centerX.velocity = other.centerX.velocity
        top.velocity = other.top.velocity
        width.velocity = other.width.velocity
        shape.velocity = other.shape.velocity
        radius.velocity = other.radius.velocity
    }

    /** How fast the window's horizontal centre moves, in pixels a second. */
    val centerXVelocity: Float get() = centerX.velocity

    /** How fast the window's top edge moves, in pixels a second. */
    val topVelocity: Float get() = top.velocity

    /** How fast the window's height changes, in pixels a second: its width's speed and its shape's together. */
    val heightVelocity: Float get() = width.velocity * shape.value + width.value * shape.velocity

    /** Where the window is now. */
    fun frame(): Frame {
        val halfWidth = width.value / 2f
        val height = width.value * shape.value
        return Frame(centerX.value - halfWidth, top.value, centerX.value + halfWidth, top.value + height, radius.value)
    }

    private companion object {
        const val SETTLE_PX = 0.5f
        const val SETTLE_SHAPE = 0.002f
        const val SETTLE_SPEED = 20f
        const val MAX_STEP_S = 0.004f
    }
}
