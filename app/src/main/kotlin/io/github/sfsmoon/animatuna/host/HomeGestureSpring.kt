package io.github.sfsmoon.animatuna.host

import android.content.res.Resources

import java.util.concurrent.ConcurrentHashMap

import io.github.sfsmoon.animatuna.hook.Hooks
import io.github.sfsmoon.animatuna.tuning.SpringResource
import io.github.sfsmoon.animatuna.tuning.TuningStore

/**
 * The spring a window flies home on after the gesture, as chosen: its
 * stiffness and its damping.
 *
 * Pixel Launcher reads them from its own resources each time it builds the
 * spring (`swipe_up_rect_*`, through `Resources.getFloat`), so those reads are
 * answered with the chosen values; the code that builds the spring, renamed in
 * a release build, is never reached.
 */
internal class HomeGestureSpring(private val hooks: Hooks, private val tuning: TuningStore) : LauncherPatch {

    /** What each resource id read is, found once by its name. */
    private val kinds = ConcurrentHashMap<Int, SpringResource>()

    override fun install(lookup: HostLookup) {
        val getFloat = Resources::class.java.getDeclaredMethod("getFloat", Int::class.javaPrimitiveType)
        hooks.replaceResult(getFloat) { resources, args, result ->
            val value = result as? Float ?: return@replaceResult result
            val id = args.firstOrNull() as? Int ?: return@replaceResult result
            val kind = kinds.getOrPut(id) { kindOf(resources as Resources, id) }
            kind.tune(value, tuning.read())
        }
    }

    private fun kindOf(resources: Resources, id: Int): SpringResource = try {
        if (resources.getResourcePackageName(id) != LauncherSymbols.PACKAGE) SpringResource.OTHER
        else SpringResource.of(resources.getResourceEntryName(id))
    } catch (_: Resources.NotFoundException) {
        SpringResource.OTHER
    }
}
