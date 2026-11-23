package io.github.sfsmoon.animatuna.core

import android.os.SystemClock

import java.io.File

/**
 * The last [LINES] events a diagnostic build saw, in a file of the host's own
 * device data, where `su` reads it however busy logcat is.
 */
class TraceFile(private val file: File) {

    private val lines = ArrayDeque<String>()

    @Synchronized
    fun write(event: String) {
        lines.addLast("${SystemClock.uptimeMillis()} [${Thread.currentThread().name}] $event")
        while (lines.size > LINES) lines.removeFirst()
        runCatching { file.writeText(lines.joinToString("\n", postfix = "\n")) }
    }

    private companion object {
        const val LINES = 400
    }
}
