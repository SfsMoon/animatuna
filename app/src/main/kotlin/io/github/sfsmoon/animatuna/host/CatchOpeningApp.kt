package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.content.res.Resources
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.TuningStore
import io.github.sfsmoon.animatuna.tuning.WindowSpring

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
 * 3. The gesture starts the window from the full screen. As on vivo's
 *    launcher, which breaks a launch the same way, the window instead springs
 *    from where the launch left it toward each place the gesture sets
 *    ([WindowSpring]), until it has settled on the gesture's.
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

    /** What showed of the window where the launch left it when the shell took it. */
    @Volatile private var caught: WindowSpring.Shown? = null

    // The gesture's surface for the caught window, the crop and corner it last set there,
    // and the window springing from the launch's place to the gesture's.
    @Volatile private var gestureSurface: Any? = null
    @Volatile private var gestureCrop: FloatArray? = null
    @Volatile private var gestureRadius: Float? = null
    private var spring: WindowSpring? = null
    private var steppedAt = 0L
    @Volatile private var shown: WindowSpring.Shown? = null

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
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceMatrix)) { properties, args ->
            val surface = properties?.let(surfaceOf::get)
            val matrix = args.firstOrNull() as? Matrix ?: return@replaceArguments args
            val values = FloatArray(MATRIX_VALUES).also(matrix::getValues)
            when {
                surface != null && surface === launchSurface -> launchMatrix = values
                surface != null && surface === gestureSurface -> {
                    val shown = follow(values) ?: return@replaceArguments args
                    return@replaceArguments listOf(Matrix().apply { setValues(shown.matrix()) })
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
                    gestureCrop = values
                    val set = shown?.cropToSet() ?: return@replaceArguments args
                    return@replaceArguments listOf(Rect(set[0].toInt(), set[1].toInt(), set[2].toInt(), set[3].toInt()))
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
                    gestureRadius = radius
                    val now = shown ?: return@replaceArguments args
                    return@replaceArguments listOf(now.radius)
                }
            }
            args
        }
    }

    /**
     * Springs the caught window one frame toward where the gesture's [matrix]
     * puts it, and says what shows now; null once the window has settled on
     * the gesture, or none is caught.
     */
    private fun follow(matrix: FloatArray): WindowSpring.Shown? {
        val from = caught ?: return null
        val target = WindowSpring.Shown.of(matrix, gestureCrop ?: fullWindow(), gestureRadius ?: from.radius)
        val now = SystemClock.uptimeMillis()
        val window = spring ?: WindowSpring(from).also {
            spring = it
            steppedAt = now
        }
        val seconds = (now - steppedAt).coerceIn(0L, MAX_FRAME_MS) / MS_PER_S
        steppedAt = now
        val next = window.step(target, seconds)
        if (window.settled) {
            trace.write("  catch: window settled on the gesture")
            release()
            return null
        }
        shown = next
        return next
    }

    private fun fullWindow(): FloatArray {
        val metrics = Resources.getSystem().displayMetrics
        return floatArrayOf(0f, 0f, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
    }

    private fun release() {
        caught = null
        gestureSurface = null
        spring = null
        shown = null
        gestureCrop = null
        gestureRadius = null
    }

    /** Keeps where the launch has the window, and stops the launch, now the shell has handed the window on. */
    private fun catch(running: AnimatorSet) {
        val matrix = launchMatrix ?: return
        release()
        caught = WindowSpring.Shown.of(matrix, launchCrop ?: fullWindow(), launchRadius ?: 0f)
        trace.write("  catch: shell took the window from the launch, ${running.currentPlayTime} ms in")
        main.post {
            if (launch === running && running.isRunning) running.cancel()
        }
    }

    private fun follow(set: AnimatorSet) {
        launch = set
        release()
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

        /** The longest frame a spring steps over, so a pause in the gesture never makes the window leap. */
        const val MAX_FRAME_MS = 32L
        const val MS_PER_S = 1000f
    }
}
