package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

import java.lang.reflect.Method

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.Motion
import io.github.sfsmoon.animatuna.tuning.RectSpring
import io.github.sfsmoon.animatuna.tuning.TuningStore
import io.github.sfsmoon.animatuna.tuning.WindowFit

/**
 * An app opens from its icon on springs, as on ColorOS
 * ([io.github.sfsmoon.animatuna.tuning.Tuning.springLaunch]).
 *
 * Pixel Launcher still runs its own launch: it decides when the launch starts
 * and ends, fades the window in, moves the home screen and the flying icon.
 * Only where the window is, each frame, is this module's: the place Pixel
 * Launcher sets on the window's surface (`SurfaceProperties.setMatrix`,
 * `setWindowCrop`, `setCornerRadius`) is replaced by a [RectSpring]'s, which
 * starts where Pixel Launcher's first frame put the window (the icon) and
 * springs to the full window ([Motion.LAUNCH]); the flying icon
 * (`FloatingIconView.update`) is moved along with it.
 *
 * Pixel Launcher's own window animation lasts 508 ms; the spring may need
 * longer. A keeper animator in the launch's set holds the launch open until
 * the spring has settled, and sets the window's place itself once Pixel
 * Launcher no longer does.
 */
internal class SpringLaunch(
    private val hooks: Hooks,
    private val tuning: TuningStore,
    private val trace: TraceFile,
) : HostPatch {

    /** One launch's window, from its first frame on. */
    private class Launch(
        val task: Int,
        val surface: Any,
        val screen: Rect,
        val endRadius: Float,
        val durationFactor: Float,
        val set: AnimatorSet,
    ) {
        var firstMatrix: FloatArray? = null
        var firstCrop: FloatArray? = null
        var window: MovingWindow? = null
        var fit: WindowFit? = null
        var hostWroteAt = 0L
    }

    /**
     * A window that was flying home when its app was opened again: its
     * springs, from where it showed, and since when. [takeOver] tells whoever
     * still moves them that the launch takes them, and whether they were still
     * moving, so the launch carries their speed.
     */
    private class Return(val task: Int, val window: MovingWindow, val at: Long, val takeOver: () -> Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val building = ThreadLocal<Any?>()
    @Volatile private var launch: Launch? = null
    @Volatile private var returning: Return? = null

    private lateinit var cornerRadius: Method

    override fun install(lookup: HostLookup) {
        val surfaceOf = lookup.field(LauncherSymbols.surfaceOf)
        cornerRadius = lookup.method(LauncherSymbols.windowCornerRadius)

        hooks.around(lookup.method(LauncherSymbols.appLaunchAnimationStart)) { _, args, proceed ->
            building.set((args.getOrNull(1) as? Array<*>)?.firstOrNull { field(it, "mode") == MODE_OPENING })
            try {
                proceed()
            } finally {
                building.remove()
            }
        }
        hooks.around(lookup.method(LauncherSymbols.setAnimation)) { _, args, proceed ->
            val opening = building.get()
            val set = args.getOrNull(0) as? AnimatorSet
            val context = args.getOrNull(1) as? Context
            if (opening != null && set != null && context != null && tuning.read().springLaunch) begin(opening, set, context)
            proceed()
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceMatrix)) { properties, args ->
            val now = launch ?: return@replaceArguments args
            if (properties?.let(surfaceOf::get) !== now.surface) return@replaceArguments args
            val matrix = args.firstOrNull() as? Matrix ?: return@replaceArguments args
            now.hostWroteAt = SystemClock.uptimeMillis()
            val window = now.window
            if (window == null) {
                now.firstMatrix = FloatArray(MATRIX_VALUES).also(matrix::getValues)
                return@replaceArguments args
            }
            listOf(SurfacePlacer.matrixOf(window.step().also { now.fit = it }))
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCrop)) { properties, args ->
            val now = launch ?: return@replaceArguments args
            if (properties?.let(surfaceOf::get) !== now.surface) return@replaceArguments args
            val crop = args.firstOrNull() as? Rect ?: return@replaceArguments args
            val fit = now.fit
            if (now.window == null) {
                now.firstCrop = floatArrayOf(crop.left.toFloat(), crop.top.toFloat(), crop.right.toFloat(), crop.bottom.toFloat())
                return@replaceArguments args
            }
            if (fit == null) args else listOf(SurfacePlacer.rectOf(fit.crop))
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.surfaceCornerRadius)) { properties, args ->
            val now = launch ?: return@replaceArguments args
            if (properties?.let(surfaceOf::get) !== now.surface) return@replaceArguments args
            val radius = args.firstOrNull() as? Float ?: return@replaceArguments args
            if (now.window == null) {
                start(now, radius)
                return@replaceArguments args
            }
            val fit = now.fit ?: return@replaceArguments args
            listOf(fit.surfaceRadius)
        }
        hooks.replaceArguments(lookup.method(LauncherSymbols.floatingIconUpdate)) { _, args ->
            val now = launch ?: return@replaceArguments args
            val frame = now.window?.spring?.frame() ?: return@replaceArguments args
            if (args.getOrNull(5) != true) return@replaceArguments args
            args.toMutableList().apply {
                this[1] = RectF(frame.left, frame.top, frame.right, frame.bottom)
                this[4] = frame.radius
            }
        }
    }

    /** Takes the window of a launch about to start, and keeps the launch open until its spring settles. */
    private fun begin(opening: Any, set: AnimatorSet, context: Context) {
        val surface = field(opening, "leash") ?: return
        val task = field(opening, "taskId") as? Int ?: return
        val screen = field(opening, "screenSpaceBounds") as? Rect ?: return
        if (screen.isEmpty || (field(opening, "rotationChange") as? Int ?: 0) != 0) return
        this.context = context.applicationContext
        val endRadius = runCatching { cornerRadius.invoke(null, context) as Float }.getOrDefault(0f)
        val factor = tuning.read().openDurationFactor * ValueAnimator.getDurationScale().takeIf { it > 0f }.let { it ?: 1f }
        val next = Launch(task, surface, Rect(screen), endRadius, factor, set)
        val keeper = ValueAnimator.ofFloat(0f, 1f).setDuration(KEEP_OPEN_MS)
        keeper.addUpdateListener { keep(next, it) }
        set.play(keeper)
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (launch === next) launch = null
            }
        })
        launch = next
    }

    /** Starts the spring where Pixel Launcher's first frame put the window, now its corner is known too. */
    private fun start(now: Launch, firstRadius: Float) {
        val matrix = now.firstMatrix ?: return
        val crop = now.firstCrop ?: return
        // As on ColorOS, an app opened again while its window flies home opens from where the window is, not from its icon.
        val time = SystemClock.uptimeMillis()
        val back = returnFor(now.task, time)
        returning = null
        val from = back?.window?.spring?.frame() ?: WindowFit.shown(matrix, crop, firstRadius)
        if (from.width <= 0f || from.height <= 0f) return
        now.window = windowFrom(now, from, back, time)
        val whence = if (back == null) "" else " (back from its flight home, ${time - back.at} ms after)"
        trace.write("  spring launch from ${from.width.toInt()}x${from.height.toInt()} at (${from.left.toInt()}, ${from.top.toInt()})$whence")
    }

    /** The flight home [task]'s window stopped in lately, if any: where its launch starts. */
    private fun returnFor(task: Int, time: Long): Return? = returning?.takeIf { it.task == task && time - it.at < RETURN_FRESH_MS }

    /** The launch's window on springs from [from] to the full window, at the speed it came back with if fresh. */
    private fun windowFrom(now: Launch, from: RectSpring.Frame, back: Return?, time: Long): MovingWindow {
        val to = RectSpring.Frame(now.screen.left.toFloat(), now.screen.top.toFloat(), now.screen.right.toFloat(), now.screen.bottom.toFloat(), now.endRadius)
        val moving = back?.takeOver?.invoke() == true
        val spring = RectSpring(from, Motion.LAUNCH).apply {
            target = to
            use(Motion.LAUNCH, now.durationFactor)
            if (back != null && (moving || time - back.at < KEEP_SPEED_MS)) keepSpeedOf(back.window.spring)
        }
        return MovingWindow(now.task, now.screen, now.durationFactor, spring)
    }

    /**
     * The window of [task] stops flying home, on [window]'s springs: should
     * its app open again soon, its launch starts where they have it then.
     * [takeOver] is called as the launch takes them; it says whether they
     * were still moving.
     */
    fun returnFrom(task: Int, window: MovingWindow, takeOver: () -> Boolean = { false }) {
        returning = Return(task, window, SystemClock.uptimeMillis(), takeOver)
    }

    /** Each frame of the launch: sets the window's place where Pixel Launcher no longer does, and lets the launch end once settled. */
    private fun keep(now: Launch, keeper: ValueAnimator) {
        if (launch !== now) return
        val window = now.window ?: return
        val time = SystemClock.uptimeMillis()
        if (time - now.hostWroteAt > HOST_QUIET_MS) {
            val fit = window.step().also { now.fit = it }
            runCatching { SurfacePlacer.place(now.surface, fit) }
        }
        if (window.spring.settled) {
            trace.write("  spring launch settled")
            // Ending the keeper runs its last frame at once, back into here: let it go first, or it ends again, forever.
            keeper.removeAllUpdateListeners()
            keeper.end()
        }
    }

    /** The launcher's application, once a launch has shown it, to write the hand-over to. */
    @Volatile var context: Context? = null
        private set

    /**
     * Whether an app's launch runs on springs now: from the moment it has
     * taken its window, before its first frame too. A gesture started that
     * early must not stop it, or the app shows at once, with no launch.
     */
    val isLaunching: Boolean
        get() = launch?.set?.isRunning == true

    /**
     * Hands the window of the launch running on to whoever takes it from the
     * shell, as it stands: the launch no longer moves it, and is stopped on
     * the main thread. Null where no launch has a window on springs.
     */
    fun handOver(): MovingWindow? {
        val now = launch ?: return null
        // Taken before its first frame, a launch that starts where a flight home stopped is handed on from there.
        val window = now.window ?: run {
            val time = SystemClock.uptimeMillis()
            returnFor(now.task, time)?.let { back ->
                returning = null
                windowFrom(now, back.window.spring.frame(), back, time).also { trace.write("  spring launch taken before its first frame, back from its flight home") }
            }
        }
        launch = null
        if (window == null) {
            // Taken before its first frame, from its icon: nothing on springs yet to hand on; the launch stops all the same.
            main.post { if (now.set.isRunning) now.set.cancel() }
            trace.write("  spring launch taken before its first frame, task ${now.task}: nothing to hand over")
            return null
        }
        main.post { if (now.set.isRunning) now.set.cancel() }
        trace.write("  spring launch handed over, task ${now.task}")
        return window
    }

    // RemoteAnimationTarget is hidden from apps; its public fields are read by name.
    private fun field(target: Any?, name: String): Any? = runCatching { target?.javaClass?.getField(name)?.get(target) }.getOrNull()

    private companion object {
        const val MODE_OPENING = 0
        const val MATRIX_VALUES = 9

        /** The longest a launch is held open for its spring. */
        const val KEEP_OPEN_MS = 2000L

        /** How long Pixel Launcher must have set nothing before the window's place is set here. */
        const val HOST_QUIET_MS = 24L

        /** How long after a flight home stops its app's launch starts from the window; the shell takes about 300 ms. */
        const val RETURN_FRESH_MS = 1000L

        /** How long after it stops the launch still carries the flight's speed. */
        const val KEEP_SPEED_MS = 100L

    }
}
