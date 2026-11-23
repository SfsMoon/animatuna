package io.github.sfsmoon.animatuna.tuning

/**
 * Where a caught window stands when the shell takes it from its launch, as
 * Pixel Launcher hands it to SystemUI's shell: so the gesture's transition
 * starts with the window there rather than at the full screen. ColorOS hands
 * the same over through its own framework (`BreakParam`); here it rides in a
 * global setting, [KEY], written by the launcher (which may write secure
 * settings) and read by SystemUI. Kept free of Android so it can be unit tested.
 */
data class BreakFrame(
    /** The caught app's task. */
    val task: Int,
    /** When it was written, in `SystemClock.uptimeMillis`, the same clock in every process. */
    val at: Long,
    val fit: WindowFit,
) {

    fun encode(): String = listOf(
        task, at, fit.scale, fit.translateX, fit.translateY, fit.crop[0], fit.crop[1], fit.crop[2], fit.crop[3], fit.surfaceRadius,
    ).joinToString(SEPARATOR)

    /** Whether it was written within [ageMs] of [now]. */
    fun freshAt(now: Long, ageMs: Long = FRESH_MS): Boolean = now - at in 0..ageMs

    companion object {
        const val KEY = "animatuna_break_frame"
        private const val SEPARATOR = ";"
        private const val FIELDS = 10

        /** How long after the hand-over the shell's gesture transition may still start from it: it starts within tens of milliseconds. */
        const val FRESH_MS = 200L

        fun decode(text: String?): BreakFrame? {
            val parts = text?.split(SEPARATOR)?.takeIf { it.size == FIELDS } ?: return null
            return runCatching {
                val f = parts.drop(2).map(String::toFloat)
                BreakFrame(parts[0].toInt(), parts[1].toLong(), WindowFit(f[0], f[1], f[2], floatArrayOf(f[3], f[4], f[5], f[6]), f[7]))
            }.getOrNull()
        }
    }
}
