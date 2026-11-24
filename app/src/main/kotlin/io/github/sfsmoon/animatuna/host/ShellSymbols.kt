package io.github.sfsmoon.animatuna.host

/**
 * Every SystemUI member this module reaches, in one place, checked against the
 * SystemUI builds recorded under `app/src/test/resources/hosts/`.
 */
object ShellSymbols {

    const val PACKAGE = "com.android.systemui"

    /** The shell starting the gesture's recents transition: (transition, info, start transaction, finish transaction, finished). */
    val recentsStartAnimation = Member(
        "com.android.wm.shell.recents.RecentsTransitionHandler", "startAnimation",
        "android.os.IBinder", "android.window.TransitionInfo", "android.view.SurfaceControl\$Transaction",
        "android.view.SurfaceControl\$Transaction", "com.android.wm.shell.transition.Transitions\$TransitionFinishCallback",
    )

    /**
     * The shell wrapping one change of a transition for a remote animation:
     * a new leash, made into the target's `leash`, with the change's own
     * surface reparented into it at its origin. (change, order, wallpaper,
     * info, start transaction, leashes).
     */
    val newRemoteTarget = Member(
        "com.android.wm.shell.shared.TransitionUtil", "newTarget",
        "android.window.TransitionInfo\$Change", "int", "boolean", "android.window.TransitionInfo",
        "android.view.SurfaceControl\$Transaction", "android.util.ArrayMap",
    )

    val all: List<Member> = listOf(recentsStartAnimation, newRemoteTarget)
}
