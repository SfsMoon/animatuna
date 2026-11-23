package io.github.sfsmoon.animatuna.tuning

/**
 * How stiff and how damped one spring of a moving window is. Stiffness is in
 * (1/s)², damping a ratio: 1 settles without overshoot, below 1 overshoots.
 */
data class Spring(val stiffness: Float, val damping: Float) {

    /** This spring played [durationFactor] times as long: stiffness falls with the square of the time. */
    fun slowedBy(durationFactor: Float): Spring =
        if (durationFactor <= 0f) this else copy(stiffness = stiffness / (durationFactor * durationFactor))
}

/**
 * The springs a window moves on in one scene: its position and size, its
 * shape (height to width) and its corner. The values are ColorOS 16's
 * (`SpringAnimConstants` in its launcher, read from a public firmware dump),
 * whose motion this module follows.
 */
enum class Motion(val rect: Spring, val shape: Spring, val corner: Spring) {
    /** An app opening from its icon: quick, settling without overshoot. */
    LAUNCH(Spring(420f, 1.04f), Spring(420f, 1.04f), Spring(400f, 1f)),

    /** A window held by the gesture, following the finger. */
    FOLLOW(Spring(400f, 1f), Spring(400f, 1f), Spring(400f, 1f)),

    /** A window let go toward its icon after the gesture home: slower, a touch of overshoot. */
    HOME(Spring(220f, 0.85f), Spring(220f, 0.85f), Spring(400f, 1f)),
}
