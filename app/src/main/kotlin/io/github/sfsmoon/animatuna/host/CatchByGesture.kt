package io.github.sfsmoon.animatuna.host

import android.graphics.Matrix
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.BreakFrame
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
) : HostPatch {

    private val resetting = ThreadLocal<Boolean>()

    /** The window caught from a launch, until the gesture has it where it wants it. */
    @Volatile private var caught: MovingWindow? = null

    // The gesture's surface for the caught window, the crop and corner it last set there, and how it is shown now.
    @Volatile private var surface: Any? = null
    @Volatile private var hostCrop: FloatArray? = null
    @Volatile private var hostRadius: Float? = null
    @Volatile private var hostScale = 1f
    @Volatile private var fit: WindowFit? = null
    @Volatile private var hostWroteAt = 0L
    private var framesTraced = 0
    private val choreographer by lazy { Choreographer.getInstance() }

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
            if (resetting.get() == true && launch.isLaunching && tuning.read().let { it.springLaunch && it.catchOpeningApp }) {
                trace.write("  catch: the launch keeps moving past the gesture's reset")
                null
            } else {
                proceed()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.launchRunnerCancelled)) { _, _, proceed ->
            if (tuning.read().let { it.springLaunch && it.catchOpeningApp }) launch.handOver()?.let(::catch)
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
            framesTraced = 0
            window.use(Motion.FOLLOW)
            trace.write("  catch: the gesture took task ${window.task}, surface ${surface?.let(System::identityHashCode)}")
            main.post { hold(window) }
        }
        hooks.after(lookup.method(LauncherSymbols.finishRecents)) { _, args ->
            // The gesture's recents animation is over: the shell has the window again.
            if (caught != null) letGo(if (args.firstOrNull() == false) "the gesture gave the window back to the app" else "the gesture took the window home")
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceMatrix)) { properties, args ->
            val window = caught ?: return@replaceArguments args
            val written = properties?.let(surfaceOf::get)
            if (framesTraced < TRACED_FRAMES && surface != null) {
                framesTraced++
                trace.write("  catch: host sets a matrix on ${if (written === surface) "the caught" else "another (${written?.let(System::identityHashCode)})"} surface")
            }
            if (written !== surface || surface == null) return@replaceArguments args
            hostWroteAt = SystemClock.uptimeMillis()
            val matrix = args.firstOrNull() as? Matrix ?: return@replaceArguments args
            val values = FloatArray(MATRIX_VALUES).also(matrix::getValues)
            val hostValues = values
            hostScale = values[Matrix.MSCALE_X]
            val crop = hostCrop ?: floatArrayOf(0f, 0f, window.screen.width().toFloat(), window.screen.height().toFloat())
            window.spring.target = WindowFit.shown(values, crop, hostRadius ?: window.spring.frame().radius)
            val now = window.step()
            if (window.spring.near(CAUGHT_UP_PX)) {
                letGo("the window caught up with the gesture")
                return@replaceArguments args
            }
            fit = now
            if (framesTraced < TRACED_FRAMES * 2) {
                framesTraced++
                trace.write("  catch: host scale ${hostValues[0]} at (${hostValues[2].toInt()}, ${hostValues[5].toInt()}) crop ${hostCrop?.joinToString { it.toInt().toString() }}; springs scale ${now.scale} at (${now.translateX.toInt()}, ${now.translateY.toInt()}) crop ${now.crop.joinToString { it.toInt().toString() }}")
            }
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

    /**
     * Each frame while a window is caught: where Pixel Launcher sets no place
     * on the gesture's surface, as between the shell taking the window and the
     * gesture's first move, the springs set it, so the window never stands at
     * the full screen the shell would show.
     */
    private fun hold(window: MovingWindow) {
        if (caught !== window) return
        if ((surface as? android.view.SurfaceControl)?.isValid == false) {
            letGo("the gesture's surface is gone")
            return
        }
        if (SystemClock.uptimeMillis() - hostWroteAt > HOST_QUIET_MS) {
            val now = window.step()
            fit = now
            if (runCatching { SurfacePlacer.place(surface, now) }.getOrDefault(false) && framesTraced < TRACED_FRAMES) {
                framesTraced++
                trace.write("  catch: held the window at ${now.scale}")
            }
        }
        choreographer.postFrameCallback { hold(window) }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun catch(window: MovingWindow) {
        caught = window
        surface = null
        fit = null
        trace.write("  catch: holding task ${window.task} for the gesture")
        // The shell shows the window at the full screen until the gesture's transition starts; SystemUI starts it here instead.
        val frame = BreakFrame(window.task, SystemClock.uptimeMillis(), window.step())
        runCatching { Settings.Global.putString(launch.context?.contentResolver, BreakFrame.KEY, frame.encode()) }
            .onFailure { trace.write("  catch: could not hand the frame to SystemUI: $it") }
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

        /** How long Pixel Launcher must have set nothing on the caught surface before the springs set it. */
        const val HOST_QUIET_MS = 24L

        /** How many of a catch's frames are written to the trace. */
        const val TRACED_FRAMES = 8

        /** How close, in pixels, the window must be to where the gesture puts it before the gesture's own places stand. */
        const val CAUGHT_UP_PX = 1.5f
    }
}
