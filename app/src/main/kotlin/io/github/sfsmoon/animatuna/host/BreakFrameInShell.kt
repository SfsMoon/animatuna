package io.github.sfsmoon.animatuna.host

import android.app.Application
import android.os.SystemClock
import android.provider.Settings
import android.view.SurfaceControl

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.BreakFrame

/**
 * SystemUI's half of catching an opening app by the gesture: when the shell
 * starts the gesture's recents transition right after an app's launch was cut
 * short, the caught window starts where the launch had it ([BreakFrame], left
 * by the launcher), set in the transition's start transaction, rather than at
 * the full screen the shell would show between the two. ColorOS's shell does
 * the same with the launcher's `BreakParam`.
 *
 * The place goes on the leash the shell wraps the window in for the launcher
 * (`TransitionUtil.newTarget`), the one the launcher then moves, not on the
 * window's own surface inside it: the shell sets that one back to the
 * wrapper's origin but keeps its scale, so the launcher's place was scaled
 * again, toward the top left, for the whole gesture (vc21 video).
 */
internal class BreakFrameInShell(private val hooks: Hooks, private val trace: TraceFile) : HostPatch {

    /** The break frame of the recents transition starting on this thread, until it is placed. */
    private val pending = ThreadLocal<BreakFrame?>()

    override fun install(lookup: HostLookup) {
        hooks.around(lookup.method(ShellSymbols.recentsStartAnimation)) { _, _, proceed ->
            val frame = runCatching { freshFrame() }.onFailure { trace.write("break frame failed: $it") }.getOrNull()
            pending.set(frame)
            try {
                proceed()
            } finally {
                pending.get()?.let { trace.write("break frame for task ${it.task}: not in the gesture's transition") }
                pending.remove()
            }
        }
        hooks.replaceResult(lookup.method(ShellSymbols.newRemoteTarget)) { _, args, target ->
            pending.get()?.let { frame ->
                runCatching { place(frame, args.getOrNull(0), args.getOrNull(4) as? SurfaceControl.Transaction, target) }
                    .onFailure { trace.write("break frame failed: $it") }
            }
            target
        }
    }

    /** The launcher's break frame, if one is fresh; taken, so a later gesture finds the window whole again. */
    private fun freshFrame(): BreakFrame? {
        val resolver = application()?.contentResolver ?: return null
        val frame = BreakFrame.decode(Settings.Global.getString(resolver, BreakFrame.KEY)) ?: return null
        if (!frame.freshAt(SystemClock.uptimeMillis())) return null
        runCatching { Settings.Global.putString(resolver, BreakFrame.KEY, null) }
        return frame
    }

    private fun place(frame: BreakFrame, change: Any?, start: SurfaceControl.Transaction?, target: Any?) {
        if (change == null || start == null || target == null) return
        val task = change.javaClass.getMethod("getTaskInfo").invoke(change) ?: return
        if (task.javaClass.getField("taskId").get(task) != frame.task) return
        val leash = target.javaClass.getField("leash").get(target) as? SurfaceControl ?: return
        val placed = SurfacePlacer.put(start, leash, frame.fit)
        pending.set(null)
        trace.write("break frame: task ${frame.task} starts at scale ${frame.fit.scale}, ${SystemClock.uptimeMillis() - frame.at} ms after the hand-over, on the launcher's leash, placed=$placed")
    }

    private fun application(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
    }.getOrNull()
}
