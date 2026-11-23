package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.TuningStore
import io.github.sfsmoon.animatuna.tuning.WindowPlace

/**
 * A gesture started while an app opens takes its window where the launch has
 * it, rather than from the full screen. Experimental
 * ([io.github.sfsmoon.animatuna.tuning.Tuning.catchOpeningApp]).
 *
 * Three things stand in the way, each found on a device:
 *
 * 1. Touching the gesture bar puts back the handler of the gesture before,
 *    which sets the launcher back to rest (`AbsSwipeUpHandler.resetStateForAnimationCancel`)
 *    and on the way cancels the state manager's current animation: the
 *    launch, which then jumps to its end. That cancel is skipped while a
 *    launch runs.
 * 2. The shell then hands the window to the gesture, telling the launch's
 *    runner it is cancelled (`LauncherAnimationRunner.onAnimationCancelled`);
 *    the launch animation itself would go on moving the window, against the
 *    gesture. It is stopped there, and where it left the window is kept.
 * 3. The gesture starts the window from the full screen. For [HANDOVER_MS]
 *    after it takes the window, each place it sets is blended from where the
 *    launch left it ([WindowPlace.blend]).
 */
internal class CatchOpeningApp(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val trace: TraceFile,
) : LauncherPatch {

    private val main = Handler(Looper.getMainLooper())
    private val resetting = ThreadLocal<Boolean>()
    private val building = ThreadLocal<Boolean>()

    // The launch running, the task it opens and that window's surface, and the last place it set there.
    @Volatile private var launch: AnimatorSet? = null
    @Volatile private var launchTask = -1
    @Volatile private var launchSurface: Any? = null
    @Volatile private var launchMatrix: FloatArray? = null
    @Volatile private var launchCrop: FloatArray? = null
    @Volatile private var launchRadius: Float? = null

    /** Where the launch left the window when the shell took it, while the gesture has yet to take it. */
    @Volatile private var caught: WindowPlace? = null

    // The gesture's surface for the caught window, and when it first set a place there.
    @Volatile private var gestureSurface: Any? = null
    @Volatile private var handoverStart = 0L

    override fun install(lookup: HostLookup) {
        val surfaceOf = lookup.field(LauncherSymbols.surfaceOf)
        hooks.around(lookup.method(LauncherSymbols.appLaunchAnimationStart)) { _, args, proceed ->
            val opening = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "mode") == MODE_OPENING }
            launchTask = opening?.let { field(it, "taskId") as? Int } ?: -1
            launchSurface = opening?.let { field(it, "leash") }
            launchMatrix = null
            launchCrop = null
            launchRadius = null
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
        hooks.around(lookup.method(LauncherSymbols.staleGestureReset)) { _, _, proceed ->
            resetting.set(true)
            try {
                proceed()
            } finally {
                resetting.remove()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.cancelStateAnimation)) { _, _, proceed ->
            if (resetting.get() == true && launch?.isRunning == true && tuning.read().catchOpeningApp) {
                trace.write("  catch: launch kept running past the gesture's reset")
                null
            } else {
                proceed()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.launchRunnerCancelled)) { _, _, proceed ->
            val running = launch
            if (running != null && tuning.read().catchOpeningApp) catch(running)
            proceed()
        }
        hooks.after(lookup.method(LauncherSymbols.recentsAnimationStart)) { _, args ->
            if (caught == null) return@after
            val app = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "taskId") == launchTask }
            if (app == null) {
                caught = null
                return@after
            }
            gestureSurface = field(app, "leash")
            handoverStart = 0L
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceMatrix)) { properties, args ->
            val surface = properties?.let(surfaceOf::get)
            val matrix = args.firstOrNull() as? Matrix ?: return@replaceArguments args
            val values = FloatArray(MATRIX_VALUES).also(matrix::getValues)
            when {
                surface != null && surface === launchSurface -> launchMatrix = values
                surface != null && surface === gestureSurface -> {
                    val from = caught ?: return@replaceArguments args
                    return@replaceArguments listOf(Matrix().apply { setValues(WindowPlace.blend(from.matrix, values, share())) })
                }
            }
            args
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCrop)) { properties, args ->
            val surface = properties?.let(surfaceOf::get)
            val crop = args.firstOrNull() as? Rect ?: return@replaceArguments args
            val values = floatArrayOf(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())
            when {
                surface != null && surface === launchSurface -> launchCrop = values
                surface != null && surface === gestureSurface -> {
                    val from = caught ?: return@replaceArguments args
                    val blended = WindowPlace.blend(from.crop, values, share())
                    return@replaceArguments listOf(Rect(blended[0].toInt(), blended[1].toInt(), blended[2].toInt(), blended[3].toInt()))
                }
            }
            args
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCornerRadius)) { properties, args ->
            val surface = properties?.let(surfaceOf::get)
            val radius = args.firstOrNull() as? Float ?: return@replaceArguments args
            when {
                surface != null && surface === launchSurface -> launchRadius = radius
                surface != null && surface === gestureSurface -> {
                    val from = caught ?: return@replaceArguments args
                    return@replaceArguments listOf(WindowPlace.blend(from.radius, radius, share()))
                }
            }
            args
        }
    }

    /** Keeps where the launch has the window, and stops the launch, now the shell has handed the window on. */
    private fun catch(running: AnimatorSet) {
        val place = WindowPlace(launchMatrix, launchCrop, launchRadius)
        if (place.matrix == null) return
        caught = place
        gestureSurface = null
        trace.write("  catch: shell took the window from the launch, ${running.currentPlayTime} ms in")
        main.post {
            if (launch === running && running.isRunning) running.cancel()
        }
    }

    /** How far the hand-over from the launch's place has gone; once it is done, the gesture's own places stand. */
    private fun share(): Float {
        val now = SystemClock.uptimeMillis()
        if (handoverStart == 0L) handoverStart = now
        val share = WindowPlace.share(now - handoverStart, HANDOVER_MS)
        if (share >= 1f) {
            caught = null
            gestureSurface = null
            trace.write("  catch: hand-over done")
        }
        return share
    }

    private fun follow(set: AnimatorSet) {
        launch = set
        caught = null
        gestureSurface = null
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (launch === set) launch = null
            }
        })
    }

    // RemoteAnimationTarget is hidden from apps; its public fields are read by name.
    private fun field(target: Any?, name: String): Any? = runCatching { target?.javaClass?.getField(name)?.get(target) }.getOrNull()

    private companion object {
        const val MODE_OPENING = 0
        const val MATRIX_VALUES = 9

        /** How long the gesture's places take to leave the launch's behind. */
        const val HANDOVER_MS = 300L
    }
}
