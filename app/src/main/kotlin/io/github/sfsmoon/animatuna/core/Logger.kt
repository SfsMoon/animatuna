package io.github.sfsmoon.animatuna.core

import android.util.Log

/** Sink for diagnostics; hook code depends on this rather than on logcat. */
interface Logger {
    fun info(message: String)
    fun warn(message: String, error: Throwable? = null)
}

/** [Logger] backed by logcat: `adb logcat -s Animatuna`. */
object AndroidLogger : Logger {

    const val TAG: String = "Animatuna"

    override fun info(message: String) {
        Log.i(TAG, message)
    }

    override fun warn(message: String, error: Throwable?) {
        if (error == null) Log.w(TAG, message) else Log.w(TAG, message, error)
    }
}
