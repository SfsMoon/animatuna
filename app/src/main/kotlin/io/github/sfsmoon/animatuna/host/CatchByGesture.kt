package io.github.sfsmoon.animatuna.host

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.BreakFrame
import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.RectSpring
import io.github.sfsmoon.animatuna.tuning.TuningStore
import io.github.sfsmoon.animatuna.tuning.WindowFit

/**
 * A gesture started while an app opens takes its window where the launch's
 * springs have it, moving as they move it, and the same springs then catch up
 * with the finger ([Motion.CATCH_UP]): one movement, retargeted, as on ColorOS.
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
 * Let go home, the same springs fly the window to its icon
 * ([Motion.HOME]), aimed where Pixel Launcher's own flight is headed
 * (`RectFSpringAnim.getTargetRect`), its frames no longer read; the icon the
 * window lands as follows them. Where the gesture gives the window back to
 * the app rather than taking it on, the springs let it go.
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

    /** Whether the caught window flies home on the springs, aimed at its icon, rather than following the finger. */
    @Volatile private var flying = false
    @Volatile private var caughtAt = 0L
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
            window.use(Motion.CATCH_UP)
            trace.write("  catch: the gesture took task ${window.task}, surface ${surface?.let(System::identityHashCode)}")
            main.post { hold(window) }
        }
        hooks.after(lookup.method(LauncherSymbols.finishRecents)) { _, args ->
            // Back to the app, the shell shows it whole again. Home, the launcher still flies the window
            // to its icon on the same surface: the springs go on catching up with it there.
            val window = caught ?: return@after
            if (args.firstOrNull() == false) {
                letGo("the gesture gave the window back to the app")
            } else if (!flying) {
                // Should no flight home be seen, the launcher's own frames, which the springs cannot read, stand.
                main.postDelayed({ if (caught === window && !flying) letGo("no flight home seen") }, FLIGHT_WAIT_MS)
            }
        }
        val targetOf = lookup.method(LauncherSymbols.homeSpringTarget)
        hooks.around(lookup.method(LauncherSymbols.homeSpringStart)) { spring, _, proceed ->
            val window = caught
            if (window != null && surface != null && spring != null) {
                // As on ColorOS, the same springs fly the window home: from where they have it, as fast as it moves,
                // to the icon Pixel Launcher's own flight is headed for. Its frames are no longer read, only its aim.
                (targetOf.invoke(spring) as? RectF)?.let { icon ->
                    val now = window.spring.frame()
                    window.spring.target = RectSpring.Frame(icon.left, icon.top, icon.right, icon.bottom, now.radius * icon.width() / now.width)
                    window.use(Motion.HOME)
                    flying = true
                    trace.write("  catch: flying home on the same springs, to ${icon.toShortString()}")
                }
            } else {
                trace.write("  home flight starts, nothing caught")
            }
            proceed()
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.floatingIconUpdate)) { _, args ->
            // The icon the window lands as follows the window, and its corner is where the window's heads.
            val window = caught ?: return@replaceArguments args
            if (!flying || args.getOrNull(5) != false) return@replaceArguments args
            (args.getOrNull(4) as? Float)?.let { radius -> window.spring.target = window.spring.target.copy(radius = radius) }
            val now = window.spring.frame()
            args.toMutableList().apply {
                this[1] = RectF(now.left, now.top, now.right, now.bottom)
                this[4] = now.radius
            }
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
            if (!flying) {
                val crop = hostCrop ?: floatArrayOf(0f, 0f, window.screen.width().toFloat(), window.screen.height().toFloat())
                window.spring.target = WindowFit.shown(values, crop, hostRadius ?: window.spring.frame().radius)
            }
            val now = window.step()
            if (!flying && window.spring.near(CAUGHT_UP_PX)) {
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
     * Each frame from the catch until the gesture first sets the window's place:
     * the springs set it, so the window never stands at the full screen the
     * shell would show.
     */
    private fun hold(window: MovingWindow) {
        if (caught !== window) return
        if ((surface as? android.view.SurfaceControl)?.isValid == false) {
            letGo("the gesture's surface is gone")
            return
        }
        // Once the gesture sets the window's place itself, its frames carry the springs: two writers in a frame tear it.
        if (hostWroteAt >= caughtAt) return
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
        flying = false
        caughtAt = SystemClock.uptimeMillis()
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
        flying = false
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

        /** How long after the gesture ends home its flight must have started for the springs to fly the window. */
        const val FLIGHT_WAIT_MS = 120L

        /** How long Pixel Launcher must have set nothing on the caught surface before the springs set it. */
        const val HOST_QUIET_MS = 24L

        /** How many of a catch's frames are written to the trace. */
        const val TRACED_FRAMES = 8

        /** How close, in pixels, the window must be to where the gesture puts it before the gesture's own places stand. */
        const val CAUGHT_UP_PX = 6f
    }
}
