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

    val all: List<Member> = listOf(recentsStartAnimation)
}
