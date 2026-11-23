package io.github.sfsmoon.animatuna

import java.io.File

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

import io.github.sfsmoon.animatuna.core.AndroidLogger
import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.XposedHooks
import io.github.sfsmoon.animatuna.host.BreakFrameInShell
import io.github.sfsmoon.animatuna.host.CatchByGesture
import io.github.sfsmoon.animatuna.host.DiagnosticTrace
import io.github.sfsmoon.animatuna.host.HomeGestureSpring
import io.github.sfsmoon.animatuna.host.HostLookup
import io.github.sfsmoon.animatuna.host.HostPatch
import io.github.sfsmoon.animatuna.host.LauncherSymbols
import io.github.sfsmoon.animatuna.host.ShellSymbols
import io.github.sfsmoon.animatuna.host.SpringLaunch
import io.github.sfsmoon.animatuna.host.WindowAnimationSpeed
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * Module entry point: hands Pixel Launcher's and SystemUI's main processes their patches. Each
 * installs on its own; one whose host members are missing stays off and says
 * so in the log, and the others carry on.
 */
class AnimatunaModule : XposedModule() {

    @Volatile private var processName = ""
    private var installed = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || installed) return
        if (processName != param.packageName) return
        val trace = TraceFile(File(param.applicationInfo.deviceProtectedDataDir, TRACE_FILE))
        val hooks = XposedHooks(this, AndroidLogger)
        val patches = when (param.packageName) {
            LauncherSymbols.PACKAGE -> launcherPatches(hooks, trace)
            ShellSymbols.PACKAGE -> mapOf("break frame in the shell" to BreakFrameInShell(hooks, trace))
            else -> return
        }
        installed = true
        val lookup = HostLookup(param.classLoader)
        patches.forEach { (name, patch) ->
            runCatching { patch.install(lookup) }
                .onSuccess {
                    AndroidLogger.info("On: $name")
                    trace.write("on: $name")
                }
                .onFailure {
                    AndroidLogger.warn("Off: $name; the host keeps its own", it)
                    trace.write("off: $name: $it")
                }
        }
    }

    private fun launcherPatches(hooks: XposedHooks, trace: TraceFile): Map<String, HostPatch> {
        val tuning = TuningStore(runCatching { getRemotePreferences(TuningStore.GROUP) }.getOrNull())
        val launch = SpringLaunch(hooks, tuning, trace)
        return mapOf(
            "diagnostic trace" to DiagnosticTrace(hooks, trace),
            "spring launch" to launch,
            "catch an opening app by the gesture" to CatchByGesture(hooks, tuning, launch, trace),
            "window animation speed" to WindowAnimationSpeed(hooks, tuning),
            "home gesture spring" to HomeGestureSpring(hooks, tuning),
        )
    }

    private companion object {
        /** What the diagnostic trace is written to, in each host's device data. */
        const val TRACE_FILE = "animatuna_trace.txt"
    }
}
