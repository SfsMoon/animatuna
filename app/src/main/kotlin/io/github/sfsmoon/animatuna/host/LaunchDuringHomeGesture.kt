package io.github.sfsmoon.animatuna.host

import android.view.View

import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

import io.github.sfsmoon.animatuna.core.Logger
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * An app tapped on the home screen while the window just left still flies
 * home opens with its own animation.
 *
 * After the gesture home, Pixel Launcher keeps the recents animation running
 * until the window has landed on its icon. An app started in that time is
 * taken into that animation as a task that appeared during the gesture
 * (`AbsSwipeUpHandler.onTasksAppeared`), which finishes it without animating:
 * the app is just there. So a tap in that time first ends the recents
 * animation home, at once, and starts the app once it has ended, as a launch
 * from the home screen that animates out of its icon.
 */
internal class LaunchDuringHomeGesture(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val logger: Logger,
    /** Where each decision is written down, for a diagnostic build. */
    private val note: (String) -> Unit = {},
) : LauncherPatch {

    private val managers: MutableSet<Any> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** True while a tap held back is started again, so it goes through. */
    private var relaunching = false

    override fun install(lookup: HostLookup) {
        val start = lookup.method(LauncherSymbols.startActivitySafely)
        val running = lookup.method(LauncherSymbols.isRecentsAnimationRunning)
        val finish = lookup.method(LauncherSymbols.finishRunningRecentsAnimation)
        val why = lookup.method(LauncherSymbols.emptyGestureLogText)
        val emptyResult = lookup.constructor(LauncherSymbols.emptyRunnableList)
        val manager = lookup.constructor(LauncherSymbols.taskAnimationManager)

        hooks.after(manager) { created, _ ->
            created?.let(managers::add)
            note("task animation manager created; ${managers.size} known")
        }
        hooks.around(start) { launcher, args, proceed ->
            if (relaunching || !tuning.read().launchDuringHomeGesture) {
                note("tap: ${if (relaunching) "started again" else "feature off"}")
                return@around proceed()
            }
            val view = args.firstOrNull() as? View ?: return@around proceed()
            val states = synchronized(managers) { managers.map { it to (running.invoke(it) == true) } }
            note("tap: managers running ${states.map { it.second }}")
            val flying = states.firstOrNull { it.second }?.first ?: return@around proceed()
            val started = AtomicBoolean(false)
            val launch = Runnable {
                if (started.compareAndSet(false, true)) {
                    note("tap held back: recents animation over, starting the app")
                    view.post { relaunch(start, launcher, args) }
                }
            }
            finish.invoke(flying, true, true, launch, why.invoke(null))
            // Should the launcher never say the animation ended, the tap still goes through.
            view.postDelayed(launch, GIVE_UP_MS)
            emptyResult.newInstance()
        }
    }

    private fun relaunch(start: Method, launcher: Any?, args: List<Any?>) {
        relaunching = true
        try {
            start.invoke(launcher, *args.toTypedArray())
        } catch (error: ReflectiveOperationException) {
            logger.warn("Starting the app tapped during the gesture home failed", error)
        } finally {
            relaunching = false
        }
    }

    private companion object {
        const val GIVE_UP_MS = 400L
    }
}
