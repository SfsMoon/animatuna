package io.github.sfsmoon.animatuna.core

/** Who called into a hook, for a trace: the host's own frames, the innermost first. */
object Callers {

    private const val FRAMES = 8

    /** Frames from the animation framework, reflection, this module and the hook framework, which say nothing of the host. */
    private val NOT_HOST = listOf("android.animation.", "java.lang.reflect.", "io.github.sfsmoon.", "io.github.libxposed.", "org.matrix.", "org.lsposed.", "de.robv.")

    fun here(): String = Throwable().stackTrace
        .filter { frame -> NOT_HOST.none { frame.className.startsWith(it) } }
        .take(FRAMES)
        .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}" }
}
