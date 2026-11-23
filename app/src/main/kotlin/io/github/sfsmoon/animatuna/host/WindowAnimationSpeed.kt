package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorSet

import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.Tuning
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * How long an app takes to open from its icon, and to close home outside the
 * gesture, as chosen ([Tuning.openDurationFactor], [Tuning.closeDurationFactor]).
 *
 * Pixel Launcher builds each as a set of animators in a runner and hands it
 * to `AnimationResult.setAnimation` to start; the set is stretched there by
 * the factor of the runner building it. A set a runner starts later, once its
 * own call has returned, keeps Pixel Launcher's timing.
 */
internal class WindowAnimationSpeed(private val hooks: Hooks, private val tuning: TuningStore) : LauncherPatch {

    private enum class Kind { OPEN, CLOSE }

    private val building = ThreadLocal<Kind>()

    override fun install(lookup: HostLookup) {
        val setAnimation = lookup.method(LauncherSymbols.setAnimation)
        val open = lookup.method(LauncherSymbols.appLaunchAnimationStart)
        val close = lookup.method(LauncherSymbols.homeOpenAnimationStart)
        hooks.around(open) { _, _, proceed -> building(Kind.OPEN, proceed) }
        hooks.around(close) { _, _, proceed -> building(Kind.CLOSE, proceed) }
        hooks.around(setAnimation) { _, args, proceed ->
            val set = args.firstOrNull() as? AnimatorSet
            val factor = when (building.get()) {
                Kind.OPEN -> tuning.read().openDurationFactor
                Kind.CLOSE -> tuning.read().closeDurationFactor
                null -> null
            }
            if (set != null && factor != null && factor != 1f) stretch(set, factor)
            proceed()
        }
    }

    private fun building(kind: Kind, proceed: () -> Any?): Any? {
        building.set(kind)
        try {
            return proceed()
        } finally {
            building.remove()
        }
    }

    /** Multiplies every duration and delay in [animator] by [factor]. */
    private fun stretch(animator: Animator, factor: Float) {
        animator.startDelay = (animator.startDelay * factor).toLong()
        if (animator is AnimatorSet) animator.childAnimations.forEach { stretch(it, factor) }
        // A set's own duration, where one is set, overrides its children's when it starts.
        if (animator.duration >= 0) animator.duration = (animator.duration * factor).toLong()
    }
}
