package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock

import java.util.Collections
import java.util.WeakHashMap

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks

/**
 * Writes down where an opening app's window stands when a gesture cuts its
 * launch animation short, and where the gesture then puts it, frame by frame:
 * what a launch caught by the gesture has to carry from one to the other.
 *
 * The launcher sets each window's place on its surface each frame (a matrix, a
 * crop and a corner radius, through `SurfaceTransaction.SurfaceProperties`);
 * the last values set on each surface are kept. Surfaces are told apart by
 * task: the launch and the gesture move the same app through leashes of their own.
 */
internal class LaunchHandoffProbe(private val hooks: Hooks, private val trace: TraceFile) : LauncherPatch {

    /** The last place set on a surface. */
    private class Place(var matrix: Matrix? = null, var crop: Rect? = null, var radius: Float? = null, var at: Long = 0L) {
        override fun toString(): String {
            val values = FloatArray(9).also { matrix?.getValues(it) }
            val scale = if (matrix == null) "?" else "%.3f".format(values[Matrix.MSCALE_X])
            val move = if (matrix == null) "?" else "(%.0f, %.0f)".format(values[Matrix.MTRANS_X], values[Matrix.MTRANS_Y])
            return "scale $scale move $move crop ${crop?.toShortString()} radius ${radius?.let { "%.1f".format(it) }}"
        }
    }

    private val places: MutableMap<Any, Place> = Collections.synchronizedMap(WeakHashMap())

    /** The task opening, and its surface, while a launch animation is built and runs. */
    @Volatile private var launchingTask = -1
    @Volatile private var launchingSurface: Any? = null
    private val building = ThreadLocal<Boolean>()

    /** The surface the gesture moves the app cut short through, and how many of its frames are still to write down. */
    @Volatile private var gestureSurface: Any? = null
    @Volatile private var gestureFramesLeft = 0
    @Volatile private var cutAt = 0L

    override fun install(lookup: HostLookup) {
        val surfaceOf = lookup.field(LauncherSymbols.surfaceOf)
        fun record(member: Member, set: (Place, Any?) -> Unit) {
            hooks.after(lookup.method(member)) { properties, args ->
                val surface = properties?.let(surfaceOf::get) ?: return@after
                val place = places.getOrPut(surface) { Place() }
                set(place, args.firstOrNull())
                place.at = SystemClock.uptimeMillis()
                if (surface === gestureSurface && member == LauncherSymbols.surfaceMatrix && gestureFramesLeft > 0) {
                    gestureFramesLeft--
                    trace.write("  gesture frame +${place.at - cutAt} ms: $place")
                }
            }
        }
        record(LauncherSymbols.surfaceMatrix) { place, value -> place.matrix = (value as? Matrix)?.let(::Matrix) }
        record(LauncherSymbols.surfaceCrop) { place, value -> place.crop = (value as? Rect)?.let(::Rect) }
        record(LauncherSymbols.surfaceCornerRadius) { place, value -> place.radius = value as? Float }

        hooks.around(lookup.method(LauncherSymbols.appLaunchAnimationStart)) { _, args, proceed ->
            val opening = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "mode") == MODE_OPENING }
            launchingTask = opening?.let { field(it, "taskId") as? Int } ?: -1
            launchingSurface = opening?.let { field(it, "leash") }
            building.set(true)
            try {
                proceed()
            } finally {
                building.remove()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.setAnimation)) { _, args, proceed ->
            if (building.get() == true) (args.firstOrNull() as? AnimatorSet)?.let(::follow)
            proceed()
        }
        hooks.after(lookup.method(LauncherSymbols.recentsAnimationStart)) { _, args ->
            val app = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "taskId") == launchingTask } ?: return@after
            gestureSurface = field(app, "leash")
            gestureFramesLeft = GESTURE_FRAMES
            trace.write("  gesture took task $launchingTask +${SystemClock.uptimeMillis() - cutAt} ms after the cut")
        }
    }

    private fun follow(set: AnimatorSet) {
        val surface = launchingSurface
        val task = launchingTask
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationCancel(animation: Animator) {
                cutAt = SystemClock.uptimeMillis()
                val place = surface?.let(places::get)
                trace.write("  launch of task $task cut short; window last at: ${place ?: "nothing set"}${place?.let { " (${cutAt - it.at} ms before)" } ?: ""}")
            }

            override fun onAnimationEnd(animation: Animator) {
                surface?.let(places::get)?.let { trace.write("  launch of task $task ends with window at: $it") }
            }
        })
    }

    // RemoteAnimationTarget is hidden from apps; its public fields are read by name.
    private fun field(target: Any?, name: String): Any? = runCatching { target?.javaClass?.getField(name)?.get(target) }.getOrNull()

    private companion object {
        const val MODE_OPENING = 0
        const val GESTURE_FRAMES = 4
    }
}
