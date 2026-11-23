package io.github.sfsmoon.animatuna.tuning

/**
 * A caught window's flight home, from where the springs had it when the finger
 * let go ([from]) to its icon.
 *
 * The springs fly it, but Pixel Launcher still decides when the flight ends:
 * it fades the window by its own flight's progress and hands the window back
 * to the shell when that flight lands, both sooner than the springs, so the
 * window faded out mid-air (seen in a device recording). So the window is
 * drawn where the springs have it, drawn in toward the icon as the launcher's
 * own flight nears its end ([shown]), and it fades, and the icon takes its
 * place, by how far the window itself has come ([progress]). Kept free of
 * Android so it can be unit tested.
 */
class HomeFlight(private val from: RectSpring.Frame) {

    /**
     * Where the window shows, given where the springs have it and how far
     * Pixel Launcher's own flight has come (0 to 1): the springs' place alone
     * at first, the [icon] by the time that flight ends.
     */
    fun shown(springs: RectSpring.Frame, icon: RectSpring.Frame, hostProgress: Float): RectSpring.Frame {
        val pull = smoothstep((hostProgress - PULL_FROM) / (1f - PULL_FROM))
        return RectSpring.Frame(
            lerp(springs.left, icon.left, pull),
            lerp(springs.top, icon.top, pull),
            lerp(springs.right, icon.right, pull),
            lerp(springs.bottom, icon.bottom, pull),
            lerp(springs.radius, icon.radius, pull),
        )
    }

    /** How far the [shown] window has come toward its [icon], by its width: 0 where it was let go, 1 at the icon. */
    fun progress(shown: RectSpring.Frame, icon: RectSpring.Frame): Float {
        val way = from.width - icon.width
        return if (way <= 1f) 1f else ((from.width - shown.width) / way).coerceIn(0f, 1f)
    }

    companion object {

        /** How far into Pixel Launcher's own flight the window starts being drawn in toward the icon. */
        const val PULL_FROM = 0.3f

        /**
         * The window's alpha at [progress], as Pixel Launcher fades it by its own flight's:
         * whole at the start, gone once the icon starts to take its shape, at [shapeFrom].
         */
        fun windowAlpha(progress: Float, shapeFrom: Float): Float =
            if (shapeFrom <= 0f) 0f else 1f - (progress / shapeFrom).coerceIn(0f, 1f)

        private fun lerp(from: Float, to: Float, fraction: Float) = from + (to - from) * fraction

        private fun smoothstep(x: Float): Float {
            val t = x.coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
    }
}
