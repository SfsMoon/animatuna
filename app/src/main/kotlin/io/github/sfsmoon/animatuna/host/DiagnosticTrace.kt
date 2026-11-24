package io.github.sfsmoon.animatuna.host

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.os.SystemClock

import io.github.sfsmoon.animatuna.core.Callers
import io.github.sfsmoon.animatuna.core.TraceFile
import io.github.sfsmoon.animatuna.hook.Hooks

/**
 * Writes down what Pixel Launcher does around an app's launch and a gesture
 * that cuts it short: the launch animation, who ends it before its time, and
 * the recents animation the gesture starts and how it ends. A member a build
 * lacks is left out of the trace.
 */
internal class DiagnosticTrace(private val hooks: Hooks, private val trace: TraceFile) : HostPatch {

    override fun install(lookup: HostLookup) {
        trace.write("trace on")
        (LauncherSymbols.traced + LauncherSymbols.appLaunchAnimationStart + LauncherSymbols.recentsAnimationStart).forEach { member ->
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
            val set = args.firstOrNull() as? AnimatorSet
            trace.write("> setAnimation(${describe(set)}, ${set?.totalDuration} ms) alreadyFinished=${runCatching { finished?.get(result) }.getOrNull()}")
            set?.let(::follow)
            proceed()
        }
    }

    /** Writes down how [set] runs, and who ends or cancels it before its time. */
    private fun follow(set: AnimatorSet) {
        var startedAt = 0L
        set.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                startedAt = SystemClock.uptimeMillis()
                trace.write("  animation started")
            }

            override fun onAnimationCancel(animation: Animator) {
                trace.write("  animation cancelled after ${SystemClock.uptimeMillis() - startedAt} ms by ${Callers.here()}")
            }

            override fun onAnimationEnd(animation: Animator) {
                val ran = SystemClock.uptimeMillis() - startedAt
                val early = ran + EARLY_SLACK_MS < set.totalDuration
                trace.write("  animation ended after $ran ms${if (early) " (early) by ${Callers.here()}" else ""}")
            }
        })
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

    private companion object {
        const val EARLY_SLACK_MS = 50L
    }

    private fun field(target: Any, name: String): Any? = runCatching { target.javaClass.getField(name).get(target) }.getOrNull()
}
