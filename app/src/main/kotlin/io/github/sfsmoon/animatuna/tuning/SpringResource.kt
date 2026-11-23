package io.github.sfsmoon.animatuna.tuning

/**
 * What a float resource of Pixel Launcher's is to the spring a window flies
 * home on, by its entry name, and how a [Tuning] changes it.
 */
enum class SpringResource {
    STIFFNESS {
        override fun tune(value: Float, tuning: Tuning) = value * tuning.homeSpringStiffnessFactor
    },
    DAMPING {
        override fun tune(value: Float, tuning: Tuning) = tuning.homeSpringDampingRatio ?: value
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
