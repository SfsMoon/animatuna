package io.github.sfsmoon.animatuna.tuning

/**
 * What a person has chosen for Pixel Launcher's window animations. Kept free
 * of Android so its steps can be unit tested; [TuningStore] reads and writes it.
 */
data class Tuning(
    /**
     * A gesture started while an app opens leaves its launch animation
     * running, rather than jumping it to the end, so the gesture takes the
     * window where it stands. Experimental.
     */
    val catchOpeningApp: Boolean = true,
    /** How long an app takes to open from its icon, in percent of Pixel Launcher's own. */
    val openDurationPercent: Int = STOCK_PERCENT,
    /** How long an app takes to close home outside the gesture (Back), in percent of Pixel Launcher's own. */
    val closeDurationPercent: Int = STOCK_PERCENT,
    /** How stiff the spring that flies a window home after the gesture is, in percent of Pixel Launcher's own. */
    val homeSpringStiffnessPercent: Int = STOCK_PERCENT,
    /** The damping ratio of that spring, in hundredths; [STOCK_DAMPING] keeps Pixel Launcher's own. */
    val homeSpringDamping: Int = STOCK_DAMPING,
) {

    /** What an open animation's durations are multiplied by. */
    val openDurationFactor: Float get() = openDurationPercent / 100f

    /** What a close animation's durations are multiplied by. */
    val closeDurationFactor: Float get() = closeDurationPercent / 100f

    /** What the home spring's stiffness is multiplied by. */
    val homeSpringStiffnessFactor: Float get() = homeSpringStiffnessPercent / 100f

    /** The home spring's damping ratio, or null to keep Pixel Launcher's own. */
    val homeSpringDampingRatio: Float? get() = homeSpringDamping.takeIf { it != STOCK_DAMPING }?.let { it / 100f }

    companion object {
        const val STOCK_PERCENT = 100
        const val STOCK_DAMPING = 0

        /** Durations offered, in percent: from twice as fast to twice as slow. */
        val DURATION_PERCENTS: List<Int> = (5..20).map { it * 10 }

        /** Stiffness offered, in percent: from half to three times Pixel Launcher's own. */
        val STIFFNESS_PERCENTS: List<Int> = listOf(50, 60, 70, 80, 90, 100, 125, 150, 200, 250, 300)

        /** Damping ratios offered, in hundredths, after [STOCK_DAMPING]: bouncy at the low end, none at 100. */
        val DAMPINGS: List<Int> = listOf(STOCK_DAMPING, 40, 50, 60, 70, 80, 90, 100)
    }
}
