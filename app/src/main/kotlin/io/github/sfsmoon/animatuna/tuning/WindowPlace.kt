package io.github.sfsmoon.animatuna.tuning

/**
 * Where a window stands on screen, as the launcher sets it on the window's
 * surface: a matrix (nine values, as `android.graphics.Matrix` holds them), a
 * crop of the window in its own pixels (left, top, right, bottom) and a corner
 * radius. Kept free of Android so the blending can be unit tested.
 */
data class WindowPlace(val matrix: FloatArray?, val crop: FloatArray?, val radius: Float?) {

    override fun equals(other: Any?): Boolean = other is WindowPlace &&
        matrix.contentEquals(other.matrix) && crop.contentEquals(other.crop) && radius == other.radius

    override fun hashCode(): Int = 31 * (31 * matrix.contentHashCode() + crop.contentHashCode()) + radius.hashCode()

    companion object {

        /** Each of [to]'s values, moved back toward [from]'s by `1 - share`: [from] at share 0, [to] at 1. */
        fun blend(from: FloatArray?, to: FloatArray, share: Float): FloatArray {
            if (from == null || from.size != to.size) return to
            return FloatArray(to.size) { from[it] + (to[it] - from[it]) * share }
        }

        fun blend(from: Float?, to: Float, share: Float): Float = if (from == null) to else from + (to - from) * share

        /** How far along a hand-over [elapsedMs] into one [durationMs] long is: eased out, so it leaves fast and settles. */
        fun share(elapsedMs: Long, durationMs: Long): Float {
            val t = (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)
            val rest = 1f - t
            return 1f - rest * rest * rest
        }
    }
}
