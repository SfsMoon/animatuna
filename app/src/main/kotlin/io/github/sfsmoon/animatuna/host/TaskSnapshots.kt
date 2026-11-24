package io.github.sfsmoon.animatuna.host

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

import java.util.concurrent.Executors

/**
 * Pictures of an app's window, taken by the system as it shows now
 * (`ActivityManagerWrapper.takeTaskThumbnail`, the call Pixel Launcher's
 * recents use), off the main thread, the picture handed back on it.
 */
internal class TaskSnapshots(lookup: HostLookup) {

    private val instance = lookup.method(LauncherSymbols.activityManagerWrapper)
    private val take = lookup.method(LauncherSymbols.takeTaskThumbnail)
    private val bitmapOf = lookup.method(LauncherSymbols.thumbnailBitmap)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Takes a picture of [task]'s window; [done] gets it on the main thread, or null where there is none. */
    fun take(task: Int, done: (Bitmap?) -> Unit) {
        worker.execute {
            val picture = runCatching { bitmapOf.invoke(take.invoke(instance.invoke(null), task)) as? Bitmap }.getOrNull()
            main.post { done(picture) }
        }
    }
}
