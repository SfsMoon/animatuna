package io.github.sfsmoon.animatuna.host

import android.graphics.Matrix
import android.graphics.Rect

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.TuningStore
import io.github.sfsmoon.animatuna.tuning.WindowFit

/**
 * A gesture started while an app opens takes its window where the launch's
 * springs have it, moving as they move it, and the same springs then follow
 * the finger ([Motion.FOLLOW]): one movement, retargeted, as on ColorOS.
 *
 * On Pixel Launcher three things stood in the way, each found in a device trace:
 *
 * 1. Touching the gesture bar puts back the handler of the gesture before,
 *    which sets the launcher back to rest (`AbsSwipeUpHandler.resetStateForAnimationCancel`)
 *    and on the way cancels the state manager's current animation: the
 *    launch, which then jumps to its end. That cancel is skipped while a
 *    launch moves its window on springs.
 * 2. The shell then hands the window to the gesture, telling the launch's
 *    runner it is cancelled (`LauncherAnimationRunner.onAnimationCancelled`):
 *    there the launch hands its window over ([SpringLaunch.handOver]).
 * 3. The gesture's recents animation moves the window through a surface of
 *    its own, from the full screen. Each place it sets there becomes the
 *    springs' target instead, until the window has caught up with it.
 *
 * Where the gesture gives the window back to the app rather than taking it
 * on, the springs let it go.
 */
internal class CatchByGesture(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val launch: SpringLaunch,
    private val trace: TraceFile,
) : LauncherPatch {

    private val resetting = ThreadLocal<Boolean>()

    /** The window caught from a launch, until the gesture has it where it wants it. */
    @Volatile private var caught: MovingWindow? = null

    // The gesture's surface for the caught window, the crop and corner it last set there, and how it is shown now.
    @Volatile private var surface: Any? = null
    @Volatile private var hostCrop: FloatArray? = null
    @Volatile private var hostRadius: Float? = null
    @Volatile private var hostScale = 1f
    @Volatile private var fit: WindowFit? = null

    override fun install(lookup: HostLookup) {
        val surfaceOf = lookup.field(LauncherSymbols.surfaceOf)
        hooks.around(lookup.method(LauncherSymbols.staleGestureReset)) { _, _, proceed ->
            resetting.set(true)
            try {
                proceed()
            } finally {
                resetting.remove()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.cancelStateAnimation)) { _, _, proceed ->
            if (resetting.get() == true && launch.isLaunching && tuning.read().springLaunch) {
                trace.write("  catch: the launch keeps moving past the gesture's reset")
                null
            } else {
                proceed()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.launchRunnerCancelled)) { _, _, proceed ->
            if (tuning.read().springLaunch) launch.handOver()?.let(::catch)
            proceed()
        }
        hooks.after(lookup.method(LauncherSymbols.recentsAnimationStart)) { _, args ->
            val window = caught ?: return@after
            val app = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "taskId") == window.task }
            if (app == null) {
                letGo("the gesture took another window")
                return@after
            }
            surface = field(app, "leash")
            hostCrop = null
            hostRadius = null
            window.use(Motion.FOLLOW)
            trace.write("  catch: the gesture took task ${window.task}")
        }
        hooks.after(lookup.method(LauncherSymbols.finishRecents)) { _, args ->
            // Back to the app: the shell shows it whole again, which the springs no longer know.
            if (caught != null && args.firstOrNull() == false) letGo("the gesture gave the window back to the app")
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceMatrix)) { properties, args ->
            val window = caught ?: return@replaceArguments args
            if (properties?.let(surfaceOf::get) !== surface || surface == null) return@replaceArguments args
            val matrix = args.firstOrNull() as? Matrix ?: return@replaceArguments args
            val values = FloatArray(MATRIX_VALUES).also(matrix::getValues)
            hostScale = values[Matrix.MSCALE_X]
            val crop = hostCrop ?: floatArrayOf(0f, 0f, window.screen.width().toFloat(), window.screen.height().toFloat())
            window.spring.target = WindowFit.shown(values, crop, hostRadius ?: window.spring.frame().radius)
            val now = window.step()
            if (window.spring.near(CAUGHT_UP_PX)) {
                letGo("the window caught up with the gesture")
                return@replaceArguments args
            }
            fit = now
            listOf(Matrix().apply {
                setScale(now.scale, now.scale)
                postTranslate(now.translateX, now.translateY)
            })
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCrop)) { properties, args ->
            if (caught == null || properties?.let(surfaceOf::get) !== surface || surface == null) return@replaceArguments args
            val crop = args.firstOrNull() as? Rect ?: return@replaceArguments args
            hostCrop = floatArrayOf(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())
            val now = fit ?: return@replaceArguments args
            listOf(Rect(now.crop[0].toInt(), now.crop[1].toInt(), now.crop[2].toInt(), now.crop[3].toInt()))
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCornerRadius)) { properties, args ->
            if (caught == null || properties?.let(surfaceOf::get) !== surface || surface == null) return@replaceArguments args
            val radius = args.firstOrNull() as? Float ?: return@replaceArguments args
            // The surface's corner is in the window's own pixels; the springs hold it as it shows.
            hostRadius = radius * hostScale
            val now = fit ?: return@replaceArguments args
            listOf(now.surfaceRadius)
        }
    }

    private fun catch(window: MovingWindow) {
        caught = window
        surface = null
        fit = null
        trace.write("  catch: holding task ${window.task} for the gesture")
    }

    private fun letGo(why: String) {
        caught = null
        surface = null
        fit = null
        hostCrop = null
        hostRadius = null
        trace.write("  catch: let go, $why")
    }

    // RemoteAnimationTarget is hidden from apps; its public fields are read by name.
    private fun field(target: Any?, name: String): Any? = runCatching { target?.javaClass?.getField(name)?.get(target) }.getOrNull()

    private companion object {
        const val MATRIX_VALUES = 9

        /** How close, in pixels, the window must be to where the gesture puts it before the gesture's own places stand. */
        const val CAUGHT_UP_PX = 1.5f
    }
}
