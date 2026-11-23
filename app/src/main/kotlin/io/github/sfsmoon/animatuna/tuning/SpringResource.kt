package io.github.sfsmoon.animatuna.tuning

/**
 * What a float resource of Pixel Launcher's is to the spring a window flies
 * home on, by its entry name, and how a [Tuning] changes it. With
 * [Tuning.springHome], every part of that spring takes ColorOS's swipe-home
 * values ([Motion.HOME]) in place of Pixel Launcher's; the sliders then
 * stiffen it, or replace its damping, from there.
 */
enum class SpringResource {
    STIFFNESS {
        override fun tune(value: Float, tuning: Tuning) =
            (if (tuning.springHome) Motion.HOME.rect.stiffness else value) * tuning.homeSpringStiffnessFactor
    },
    DAMPING {
        override fun tune(value: Float, tuning: Tuning) =
            tuning.homeSpringDampingRatio ?: if (tuning.springHome) Motion.HOME.rect.damping else value
    },
    OTHER {
        override fun tune(value: Float, tuning: Tuning) = value
    };

    abstract fun tune(value: Float, tuning: Tuning): Float

    companion object {
        /** Entries the spring is read from: `swipe_up_rect_x_stiffness`, `swipe_up_rect_scale_damping_ratio_v2` and the like. */
        private const val PREFIX = "swipe_up_rect_"

        fun of(entryName: String): SpringResource = when {
            !entryName.startsWith(PREFIX) -> OTHER
            "stiffness" in entryName -> STIFFNESS
            "damping_ratio" in entryName -> DAMPING
            else -> OTHER
        }
    }
}
