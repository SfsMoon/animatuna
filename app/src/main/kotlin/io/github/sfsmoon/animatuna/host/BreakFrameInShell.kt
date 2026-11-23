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
 */
internal class BreakFrameInShell(private val hooks: Hooks, private val trace: TraceFile) : HostPatch {

    override fun install(lookup: HostLookup) {
        hooks.around(lookup.method(ShellSymbols.recentsStartAnimation)) { _, args, proceed ->
            runCatching { place(args.getOrNull(1), args.getOrNull(2) as? SurfaceControl.Transaction) }
                .onFailure { trace.write("break frame failed: $it") }
            proceed()
        }
    }

    private fun place(info: Any?, start: SurfaceControl.Transaction?) {
        if (info == null || start == null) return
        val resolver = application()?.contentResolver ?: return
        val frame = BreakFrame.decode(Settings.Global.getString(resolver, BreakFrame.KEY)) ?: return
        if (!frame.freshAt(SystemClock.uptimeMillis())) return
        @Suppress("UNCHECKED_CAST")
        val changes = info.javaClass.getMethod("getChanges").invoke(info) as? List<Any> ?: return
        val change = changes.firstOrNull { change ->
            val task = change.javaClass.getMethod("getTaskInfo").invoke(change) ?: return@firstOrNull false
            task.javaClass.getField("taskId").get(task) == frame.task
        } ?: return trace.write("break frame for task ${frame.task}: not in the gesture's transition")
        val leash = change.javaClass.getMethod("getLeash").invoke(change) as? SurfaceControl ?: return
        val placed = SurfacePlacer.put(start, leash, frame.fit)
        // One hand-over, one transition: a later gesture finds the window whole again.
        runCatching { Settings.Global.putString(resolver, BreakFrame.KEY, null) }
        trace.write("break frame: task ${frame.task} starts at scale ${frame.fit.scale}, ${SystemClock.uptimeMillis() - frame.at} ms after the hand-over, placed=$placed")
    }

    private fun application(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Application?
    }.getOrNull()
}
