package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * A gesture started while an app opens leaves its launch animation running.
 *
 * Touching the gesture bar puts back the handler of the gesture before it,
 * which sets the launcher back to its resting state
 * (`AbsSwipeUpHandler.resetStateForAnimationCancel`) and on the way cancels
 * the state manager's current animation: the app's launch, which then jumps
 * to its end, the window to the full screen, before the new gesture takes it.
 * That cancel is skipped while a launch runs, so the gesture takes the window
 * where the launch has it. Experimental ([io.github.sfsmoon.animatuna.tuning.Tuning.catchOpeningApp]).
 */
internal class CatchOpeningApp(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val trace: TraceFile,
) : LauncherPatch {

    private val resetting = ThreadLocal<Boolean>()
    private val building = ThreadLocal<Boolean>()

    /** The launch animation running, if one is. */
    @Volatile private var launch: AnimatorSet? = null

    override fun install(lookup: HostLookup) {
        val reset = lookup.method(LauncherSymbols.staleGestureReset)
        val cancel = lookup.method(LauncherSymbols.cancelStateAnimation)
        hooks.around(lookup.method(LauncherSymbols.appLaunchAnimationStart)) { _, _, proceed ->
            building.set(true)
            try {
                proceed()
            } finally {
                building.remove()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.setAnimation)) { _, args, proceed ->
            val set = args.firstOrNull() as? AnimatorSet
            if (building.get() == true && set != null) follow(set)
            proceed()
        }
        hooks.around(reset) { _, _, proceed ->
            resetting.set(true)
            try {
                proceed()
            } finally {
                resetting.remove()
            }
        }
        hooks.around(cancel) { _, _, proceed ->
            val running = launch
            if (resetting.get() == true && running?.isRunning == true && tuning.read().catchOpeningApp) {
                trace.write("  catch: launch kept running past the gesture's reset (${running.currentPlayTime} ms in)")
                null
            } else {
                proceed()
            }
        }
    }

    private fun follow(set: AnimatorSet) {
        launch = set
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (launch === set) launch = null
            }
        })
    }
}
