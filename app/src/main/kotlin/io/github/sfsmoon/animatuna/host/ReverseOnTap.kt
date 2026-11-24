package io.github.sfsmoon.animatuna.host

import android.content.Context
import android.content.Intent

import io.github.sfsmoon.animatuna.hook.Hooks

/**
 * A tap on an app's icon while that app's window flies home turns the window
 * back ([CatchByGesture.reverseToApp]) instead of launching the app anew,
 * which would cut the flight short, hide the window, and only open it again
 * about 100 ms later. ColorOS listens for that tap on its flying icon layer
 * (`IconHolder.setOnClickListener` in `OplusLauncherSwipeHandlerV2Impl`); on
 * Pixel Launcher the tap still reaches the icon, so it is taken where the
 * launcher starts the app (`QuickstepLauncher.startActivitySafely`).
 */
internal class ReverseOnTap(private val hooks: Hooks, private val flights: CatchByGesture) : HostPatch {

    override fun install(lookup: HostLookup) {
        hooks.around(lookup.method(LauncherSymbols.startActivitySafely)) { launcher, args, proceed ->
            if (flights.reverseToApp(args.getOrNull(1) as? Intent, launcher as? Context)) null else proceed()
        }
    }
}
