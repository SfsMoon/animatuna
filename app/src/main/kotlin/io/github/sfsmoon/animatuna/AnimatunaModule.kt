package io.github.sfsmoon.animatuna

import java.io.File

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

import io.github.sfsmoon.animatuna.core.AndroidLogger
import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.XposedHooks
import io.github.sfsmoon.animatuna.host.DiagnosticTrace
import io.github.sfsmoon.animatuna.host.HomeGestureSpring
import io.github.sfsmoon.animatuna.host.HostLookup
import io.github.sfsmoon.animatuna.host.LaunchHandoffProbe
import io.github.sfsmoon.animatuna.host.LauncherPatch
import io.github.sfsmoon.animatuna.host.LauncherSymbols
import io.github.sfsmoon.animatuna.host.WindowAnimationSpeed
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * Module entry point: hands Pixel Launcher's main process its patches. Each
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
        if (param.packageName != LauncherSymbols.PACKAGE || processName != param.packageName) return
        installed = true

        val logger = AndroidLogger
        val hooks = XposedHooks(this, logger)
        val tuning = TuningStore(runCatching { getRemotePreferences(TuningStore.GROUP) }.getOrNull())
        val lookup = HostLookup(param.classLoader)
        val trace = TraceFile(File(param.applicationInfo.deviceProtectedDataDir, TRACE_FILE))
        val patches: Map<String, LauncherPatch> = mapOf(
            "diagnostic trace" to DiagnosticTrace(hooks, trace),
            "launch hand-off probe" to LaunchHandoffProbe(hooks, trace),
            "window animation speed" to WindowAnimationSpeed(hooks, tuning),
            "home gesture spring" to HomeGestureSpring(hooks, tuning),
        )
        patches.forEach { (name, patch) ->
            runCatching { patch.install(lookup) }
                .onSuccess {
                    logger.info("On: $name")
                    trace.write("on: $name")
                }
                .onFailure {
                    logger.warn("Off: $name; Pixel Launcher keeps its own", it)
                    trace.write("off: $name: $it")
                }
        }
    }

    private companion object {
        /** What the diagnostic trace is written to, in the launcher's device data. */
        const val TRACE_FILE = "animatuna_trace.txt"
    }
}
