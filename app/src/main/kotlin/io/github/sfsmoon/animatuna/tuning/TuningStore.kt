package io.github.sfsmoon.animatuna.tuning

import android.content.SharedPreferences

/**
 * [Tuning] in shared preferences: the settings screen writes through the
 * framework's remote preferences, and the launcher reads the same group live,
 * so a change takes effect at the next animation.
 */
class TuningStore(private val preferences: SharedPreferences?) {

    fun read(): Tuning {
        val prefs = preferences ?: return Tuning()
        val stock = Tuning()
        return runCatching {
            Tuning(
                springLaunch = prefs.getBoolean(SPRING_LAUNCH, stock.springLaunch),
                openDurationPercent = prefs.getInt(OPEN_DURATION, stock.openDurationPercent),
                closeDurationPercent = prefs.getInt(CLOSE_DURATION, stock.closeDurationPercent),
                homeSpringStiffnessPercent = prefs.getInt(HOME_SPRING_STIFFNESS, stock.homeSpringStiffnessPercent),
                homeSpringDamping = prefs.getInt(HOME_SPRING_DAMPING, stock.homeSpringDamping),
            )
        }.getOrDefault(stock)
    }

    fun write(tuning: Tuning) {
        preferences?.edit()
            ?.putBoolean(SPRING_LAUNCH, tuning.springLaunch)
            ?.putInt(OPEN_DURATION, tuning.openDurationPercent)
            ?.putInt(CLOSE_DURATION, tuning.closeDurationPercent)
            ?.putInt(HOME_SPRING_STIFFNESS, tuning.homeSpringStiffnessPercent)
            ?.putInt(HOME_SPRING_DAMPING, tuning.homeSpringDamping)
            ?.apply()
    }

    companion object {
        /** The remote preferences group both sides share. */
        const val GROUP = "tuning"

        // Keys. Renaming one silently resets that choice on every device.
        private const val SPRING_LAUNCH = "spring_launch"
        private const val OPEN_DURATION = "open_duration_percent"
        private const val CLOSE_DURATION = "close_duration_percent"
        private const val HOME_SPRING_STIFFNESS = "home_spring_stiffness_percent"
        private const val HOME_SPRING_DAMPING = "home_spring_damping"
    }
}
