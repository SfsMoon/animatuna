package io.github.sfsmoon.animatuna.host

import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks

/**
 * Writes down what Pixel Launcher does around a launch during the gesture
 * home: the recents animation starting, tasks appearing in it, how it ends,
 * and the app launch animation's runner, with what [LaunchDuringHomeGesture]
 * decided. A member a build lacks is left out of the trace.
 */
internal class DiagnosticTrace(private val hooks: Hooks, private val trace: TraceFile) : LauncherPatch {

    override fun install(lookup: HostLookup) {
        trace.write("trace on")
        (LauncherSymbols.traced + LauncherSymbols.appLaunchAnimationStart + LauncherSymbols.finishRunningRecentsAnimation).forEach { member ->
            val method = runCatching { lookup.method(member) }.getOrElse {
                trace.write("missing ${member.className}.${member.signature}")
                return@forEach
            }
            hooks.around(method) { target, args, proceed ->
                trace.write("> ${target?.javaClass?.simpleName ?: member.className.substringAfterLast('.')}.${member.name}(${args.joinToString(transform = ::describe)})")
                proceed().also { trace.write("< ${member.name}") }
            }
        }
        val setAnimation = lookup.method(LauncherSymbols.setAnimation)
        val finished = runCatching { setAnimation.declaringClass.getDeclaredField("mFinished").apply { isAccessible = true } }.getOrNull()
        hooks.around(setAnimation) { result, args, proceed ->
            trace.write("> setAnimation(${describe(args.firstOrNull())}) alreadyFinished=${runCatching { finished?.get(result) }.getOrNull()}")
            proceed()
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { target ->
            // RemoteAnimationTarget is hidden from apps; its public fields are read by name.
            if (target?.javaClass?.simpleName == "RemoteAnimationTarget") "task ${field(target, "taskId")} mode ${field(target, "mode")}" else describe(target)
        }
        is Boolean, is Number -> value.toString()
        else -> value.javaClass.simpleName
    }

    private fun field(target: Any, name: String): Any? = runCatching { target.javaClass.getField(name).get(target) }.getOrNull()
}
