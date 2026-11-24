package io.github.sfsmoon.animatuna.host

import android.content.Intent
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.Choreographer

import io.github.sfsmoon.animatuna.core.Callers
import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.BreakFrame
import io.github.sfsmoon.animatuna.tuning.HomeFlight
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
 *    which sets the launcher back to rest (`AbsSwipeUpHandler.resetStateForAnimationCancel`),
 *    or has the new gesture set the launcher's state behind the app
 *    (`DefaultAnimationFactory.initBackgroundStateUI`); either cancels the
 *    state manager's current animation on the way: the launch, which then
 *    jumps to its end. That cancel is skipped while a launch moves its window
 *    on springs.
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
 * window lands as follows them. The window fades, and the icon takes its
 * place, by how far it has come, and it lands as Pixel Launcher's own
 * flight ends ([HomeFlight]). A catch lasts one gesture: where the gesture
 * gives the window back to the app, is cancelled, or the window has landed,
 * the springs let it go.
 *
 * Every other gesture home flies on the same engine too (`Tuning.springHome`):
 * the springs take the window where Pixel Launcher's own flight starts it, at
 * the finger's speed. Should its app be opened again before the window lands,
 * the launch starts where the window is ([SpringLaunch.returnFrom]).
 *
 * Tapped while it flies home, its own app's icon turns the window back to its
 * app, as ColorOS does (`OplusLauncherSwipeHandlerV2Impl.tryReverseRecentAnim`:
 * `CustomRectFSpringAnim.reverseToOpen`, then the gesture's end target set
 * back to the app): no new launch, so no gap in which the system hides the
 * window. The same springs retarget the full window ([reverseToApp]), and the
 * gesture, which Pixel Launcher would end home, ends back in the app once
 * they have settled.
 */
internal class CatchByGesture(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val launch: SpringLaunch,
    private val trace: TraceFile,
) : HostPatch {

    /** Set while a new gesture resets the launcher's state, which cancels the launch on the way. */
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

    /** The caught window's flight home on the springs, aimed at its icon, once the finger has let it go. */
    @Volatile private var flight: HomeFlight? = null
    private val flying: Boolean get() = flight != null

    // How far Pixel Launcher's own flight home has come, where its icon starts taking the window's shape, and how far the window has come.
    @Volatile private var hostProgress = 0f
    @Volatile private var shapeFrom = DEFAULT_SHAPE_FROM
    @Volatile private var flown = 0f
    @Volatile private var hostFlight: Any? = null

    /** Whether Pixel Launcher ended its flight at once, mid-way, as a tap on the home screen does: the window is not pulled home then. */
    @Volatile private var cutShort = false
    @Volatile private var shown: RectSpring.Frame? = null

    /** The app window the gesture moves, whether caught or not: its task, its app, its surface, its full-size bounds. */
    private class GestureApp(val task: Int, val packageName: String?, val surface: Any, val screen: Rect)
    @Volatile private var gestureApp: GestureApp? = null

    /** Whether the window, tapped on its flight home, is turning back to its app; the gesture it ends with, once it may. */
    @Volatile private var reversing = false
    @Volatile private var pendingFinish: Pair<Any?, List<Any?>>? = null
    @Volatile private var reverseSettled = false
    private val finishing = ThreadLocal<Boolean>()
    private lateinit var finishMethod: java.lang.reflect.Method
    private lateinit var cornerRadiusMethod: java.lang.reflect.Method
    @Volatile private var caughtAt = 0L
    private var framesTraced = 0
    private val choreographer by lazy { Choreographer.getInstance() }

    override fun install(lookup: HostLookup) {
        val surfaceOf = lookup.field(LauncherSymbols.surfaceOf)
        finishMethod = lookup.method(LauncherSymbols.finishRecents)
        cornerRadiusMethod = lookup.method(LauncherSymbols.windowCornerRadius)
        val progressOf = lookup.field(LauncherSymbols.homeSpringProgress)
        listOf(LauncherSymbols.staleGestureReset, LauncherSymbols.gestureBackgroundState).forEach { member ->
            hooks.around(lookup.method(member)) { _, _, proceed ->
                resetting.set(true)
                try {
                    proceed()
                } finally {
                    resetting.remove()
                }
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
            gestureApp = (args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "mode") == MODE_CLOSING }?.let { app ->
                val task = field(app, "taskId") as? Int
                val leash = field(app, "leash")
                val screen = field(app, "screenSpaceBounds") as? Rect
                val info = field(app, "taskInfo") as? android.app.TaskInfo
                val packageName = (info?.topActivity ?: info?.baseIntent?.component)?.packageName
                if (task == null || leash == null || screen == null || screen.isEmpty) null else GestureApp(task, packageName, leash, Rect(screen))
            }
            val window = caught ?: return@after
            if (surface != null) {
                letGo("an earlier gesture had it")
                return@after
            }
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
        hooks.around(lookup.method(LauncherSymbols.homeSpringEnd)) { spring, _, proceed ->
            // Pixel Launcher ends its flight at its end too: only an end well short of it is a cut.
            if (flying && spring === hostFlight && hostProgress < FLIGHT_DONE) {
                cutShort = true
                trace.write("  catch: the launcher cuts its flight short at ${(hostProgress * PERCENT).toInt()}%, by ${Callers.here(CUT_CALLERS)}")
            }
            proceed()
        }
        hooks.after(lookup.method(LauncherSymbols.recentsCanceled)) { _, _ ->
            val window = caught ?: return@after
            // Cancelled mid-flight, as a tap opening the app again does: its launch starts where the window is.
            if (flying) returnFromFlight(window)
            letGo("the gesture was cancelled${if (flying) ", ${(flown * PERCENT).toInt()}% of the way home" else ""}")
        }
        hooks.around(lookup.method(LauncherSymbols.finishRecents)) { controller, args, proceed ->
            // A window turning back to its app: the gesture Pixel Launcher ends home ends back in the app, once the springs have settled.
            if (finishing.get() != true && reversing && args.firstOrNull() == true) {
                pendingFinish = controller to args
                trace.write("  reverse: the launcher ends the gesture home; it ends in the app once the window is whole")
                finishReverseIfReady()
                null
            } else {
                proceed().also { if (finishing.get() != true) ended(args) }
            }
        }
        val targetOf = lookup.method(LauncherSymbols.homeSpringTarget)
        val startOf = lookup.field(LauncherSymbols.homeSpringStartRect)
        val cornerRadius = cornerRadiusMethod
        hooks.around(lookup.method(LauncherSymbols.homeSpringStart)) { spring, args, proceed ->
            val window = caught
            hostFlight = spring
            cutShort = false
            if (window == null && spring != null && tuning.read().springHome) {
                // Not caught: the springs take the window where Pixel Launcher's flight starts it, at the finger's speed.
                val app = gestureApp
                val start = startOf.get(spring) as? RectF
                val icon = targetOf.invoke(spring) as? RectF
                val corner = runCatching { cornerRadius.invoke(null, args.getOrNull(0) as? Context) as Float }.getOrNull()
                val speed = args.getOrNull(2) as? android.graphics.PointF
                if (app != null && start != null && icon != null && corner != null && !start.isEmpty && !icon.isEmpty) {
                    val shownRadius = corner * start.width() / app.screen.width()
                    val from = RectSpring.Frame(start.left, start.top, start.right, start.bottom, shownRadius)
                    val springs = RectSpring(from, Motion.HOME).apply {
                        target = RectSpring.Frame(icon.left, icon.top, icon.right, icon.bottom, shownRadius * icon.width() / start.width())
                        fling((speed?.x ?: 0f) * MS_PER_S, (speed?.y ?: 0f) * MS_PER_S)
                    }
                    val scale = ValueAnimator.getDurationScale().takeIf { it > 0f } ?: 1f
                    val flier = MovingWindow(app.task, app.screen, scale, springs).also { it.use(Motion.HOME) }
                    caught = flier
                    surface = app.surface
                    fit = null
                    hostCrop = null
                    hostRadius = null
                    hostProgress = 0f
                    flown = 0f
                    shown = from
                    flight = HomeFlight(from)
                    framesTraced = TRACED_FRAMES
                    trace.write("  home flight on the springs, task ${app.task}, from ${start.toShortString()} to ${icon.toShortString()}")
                } else {
                    trace.write("  home flight starts, left to the launcher")
                }
            } else if (window != null && surface != null && spring != null) {
                // As on ColorOS, the same springs fly the window home: from where they have it, as fast as it moves,
                // to the icon Pixel Launcher's own flight is headed for. Its frames are no longer read, only its aim.
                (targetOf.invoke(spring) as? RectF)?.let { icon ->
                    val now = window.spring.frame()
                    window.spring.target = RectSpring.Frame(icon.left, icon.top, icon.right, icon.bottom, now.radius * icon.width() / now.width)
                    window.use(Motion.HOME)
                    hostProgress = 0f
                    flown = 0f
                    shown = now
                    flight = HomeFlight(now)
                    framesTraced = TRACED_FRAMES
                    trace.write("  catch: flying home on the same springs, to ${icon.toShortString()}")
                }
            } else {
                trace.write("  home flight starts, left to the launcher")
            }
            proceed()
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.floatingIconUpdate)) { _, args ->
            // The icon the window lands as follows the window, takes its shape as far as the window has come,
            // and its corner is where the window's heads.
            val window = caught ?: return@replaceArguments args
            if (args.getOrNull(5) != false) return@replaceArguments args
            // Turning back to its app, the window covers where it flies; the icon it would have landed as is gone.
            if (reversing) return@replaceArguments args.toMutableList().apply { this[0] = 0f }
            if (flight == null) return@replaceArguments args
            (args.getOrNull(3) as? Float)?.let { shapeFrom = it }
            (args.getOrNull(4) as? Float)?.let { radius -> window.spring.target = window.spring.target.copy(radius = radius) }
            val now = shown ?: return@replaceArguments args
            args.toMutableList().apply {
                this[1] = RectF(now.left, now.top, now.right, now.bottom)
                this[2] = flown
                this[4] = now.radius
            }
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceAlpha)) { properties, args ->
            // Pixel Launcher fades the window by its own flight's progress, ahead of the springs: by the window's own instead.
            if (caught == null || properties?.let(surfaceOf::get) !== surface || surface == null) return@replaceArguments args
            if (reversing) return@replaceArguments listOf(1f)
            if (flight == null) return@replaceArguments args
            listOf(HomeFlight.windowAlpha(flown, shapeFrom))
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
            hostScale = values[Matrix.MSCALE_X]
            if (!flying && !reversing) {
                val crop = hostCrop ?: floatArrayOf(0f, 0f, window.screen.width().toFloat(), window.screen.height().toFloat())
                window.spring.target = WindowFit.shown(values, crop, hostRadius ?: window.spring.frame().radius)
            }
            val stepped = window.step()
            val now = flight?.let { home ->
                if (!cutShort) hostFlight?.let { hostProgress = runCatching { progressOf.getFloat(it) }.getOrDefault(hostProgress) }
                val drawn = home.shown(window.spring.frame(), window.spring.target, hostProgress)
                flown = home.progress(drawn, window.spring.target)
                shown = drawn
                window.fit(drawn)
            } ?: stepped
            if (!flying && !reversing && window.spring.near(CAUGHT_UP_PX)) {
                letGo("the window caught up with the gesture")
                return@replaceArguments args
            }
            fit = now
            if (framesTraced < TRACED_FRAMES * 2) {
                framesTraced++
                trace.write("  catch: host scale ${values[0]} at (${values[2].toInt()}, ${values[5].toInt()}) crop ${hostCrop?.joinToString { it.toInt().toString() }}; springs scale ${now.scale} at (${now.translateX.toInt()}, ${now.translateY.toInt()}) crop ${now.crop.joinToString { it.toInt().toString() }}${if (flying) ", flown ${(flown * PERCENT).toInt()}%, launcher's flight ${(hostProgress * PERCENT).toInt()}%" else ""}")
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
        flight = null
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

    /** The gesture has ended: back in the app the shell shows it whole again; home, the launcher has landed its flight. */
    private fun ended(args: List<Any?>) {
        val window = caught ?: return
        if (args.firstOrNull() == false) {
            letGo("the gesture gave the window back to the app")
        } else if (flight != null) {
            // Ended mid-way, as a tap opening another app does: should the app open again, it opens from where the window is.
            val springs = flight?.progress(window.spring.frame(), window.spring.target) ?: 1f
            if (cutShort || springs < LANDED) returnFromFlight(window)
            letGo("the gesture ended, the window ${(flown * PERCENT).toInt()}% of the way home, its springs ${(springs * PERCENT).toInt()}%${if (cutShort) ", the launcher's flight cut short" else ""}")
        } else {
            // Should no flight home be seen, the launcher's own frames, which the springs cannot read, stand.
            main.postDelayed({ if (caught === window && !flying) letGo("no flight home seen") }, FLIGHT_WAIT_MS)
        }
    }

    /**
     * The window's flight home stops short of its icon: should its app open
     * again, the launch starts where the window shows, at the springs' speed.
     */
    private fun returnFromFlight(window: MovingWindow) {
        val from = shown ?: window.spring.frame()
        val springs = RectSpring(from, Motion.HOME).apply {
            target = window.spring.target
            keepSpeedOf(window.spring)
        }
        launch.returnFrom(window.task, MovingWindow(window.task, window.screen, window.durationFactor, springs))
    }

    /**
     * A tap on [intent]'s icon while its app's window flies home: as ColorOS,
     * the window turns back to its app on the same springs, from where it
     * shows and as fast as it moves, rather than the app being launched anew.
     * True where it does; the tap is then spent.
     */
    fun reverseToApp(intent: Intent?, context: android.content.Context?): Boolean {
        val window = caught
        val app = gestureApp?.takeIf { it.task == window?.task }
        if (window == null || app == null) {
            trace.write("  reverse: tap not taken, no window in flight")
            return false
        }
        val tapped = intent?.component?.packageName ?: intent?.`package`
        val refused = when {
            reversing -> "already turning back"
            cutShort -> "the launcher has already cut the flight"
            flight == null || surface == null -> "not flying home"
            tapped == null || tapped != app.packageName -> "another app"
            !tuning.read().springLaunch -> "spring launch off"
            else -> null
        }
        if (refused != null) {
            trace.write("  reverse: tap not taken, $refused")
            return false
        }
        val corner = runCatching { cornerRadiusMethod.invoke(null, context) as Float }.getOrDefault(0f)
        val from = shown ?: window.spring.frame()
        val springs = RectSpring(from, Motion.LAUNCH).apply {
            target = RectSpring.Frame(app.screen.left.toFloat(), app.screen.top.toFloat(), app.screen.right.toFloat(), app.screen.bottom.toFloat(), corner)
            keepSpeedOf(window.spring)
        }
        val back = MovingWindow(window.task, window.screen, window.durationFactor, springs).also { it.use(Motion.LAUNCH) }
        trace.write("  reverse: tapped ${(flown * PERCENT).toInt()}% of the way home, the window turns back to its app")
        caught = back
        flight = null
        reversing = true
        reverseSettled = false
        pendingFinish = null
        main.post { driveReverse(back) }
        main.postDelayed({ if (caught === back && reversing && pendingFinish == null) letGo("the gesture never ended, its app left to the launcher") }, REVERSE_LONGEST_MS)
        return true
    }

    /** Each frame of a window turning back to its app: sets its place where Pixel Launcher no longer does, until it has settled. */
    private fun driveReverse(window: MovingWindow) {
        if (caught !== window || !reversing) return
        if (SystemClock.uptimeMillis() - hostWroteAt > HOST_QUIET_MS) {
            val now = window.step()
            fit = now
            runCatching { SurfacePlacer.place(surface, now, alpha = 1f) }
        }
        if (window.spring.settled) {
            reverseSettled = true
            trace.write("  reverse: the window is whole")
            finishReverseIfReady()
            return
        }
        choreographer.postFrameCallback { driveReverse(window) }
    }

    /** Once the window is whole and the launcher has ended the gesture, ends it back in the app instead of home. */
    private fun finishReverseIfReady() {
        val (controller, args) = pendingFinish ?: return
        if (!reverseSettled) return
        pendingFinish = null
        val back = args.toMutableList().apply {
            this[0] = false
            this[2] = false
        }
        finishing.set(true)
        try {
            finishMethod.invoke(controller, *back.toTypedArray())
        } catch (error: Throwable) {
            trace.write("  reverse: could not end the gesture in the app: $error")
        } finally {
            finishing.remove()
        }
        letGo("the window turned back into its app")
    }

    private fun letGo(why: String) {
        caught = null
        reversing = false
        reverseSettled = false
        pendingFinish = null
        flight = null
        shown = null
        hostFlight = null
        cutShort = false
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
        const val PERCENT = 100
        const val MS_PER_S = 1000f

        /** How many callers a cut flight traces: enough to reach the input that cut it. */
        const val CUT_CALLERS = 20

        /** The longest a window turns back before the gesture must have ended. */
        const val REVERSE_LONGEST_MS = 1500L

        /** How far Pixel Launcher's own flight must have come for its end to be its own, not a cut. */
        const val FLIGHT_DONE = 0.95f

        /** How far home, by the springs' own place, a window has flown for the flight to count as landed. */
        const val LANDED = 0.9f

        /** A recents animation target's mode for the app the gesture moves away from. */
        const val MODE_CLOSING = 1

        /** Where Pixel Launcher's icon starts taking a window's shape on its flight home, until it says. */
        const val DEFAULT_SHAPE_FROM = 0.9f

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
