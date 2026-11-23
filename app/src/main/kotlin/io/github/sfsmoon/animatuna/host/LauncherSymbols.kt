package io.github.sfsmoon.animatuna.host

/**
 * Every Pixel Launcher member this module reaches, in one place. Each is named
 * as Pixel Launcher ships it unrenamed; [HostLookup] finds it at run time, and
 * the desk tests check each against the launcher builds recorded under
 * `app/src/test/resources/hosts/`. A member a build lacks turns off the patch
 * that needs it, never the launcher.
 */
object LauncherSymbols {

    const val PACKAGE = "com.google.android.apps.nexuslauncher"

    private const val GESTURE_LOG_TEXT = "com.android.quickstep.util.ActiveGestureLog\$CompoundString"
    private const val TRANSITION_MANAGER = "com.android.launcher3.QuickstepTransitionManager"
    private const val ANIMATION_RESULT = "com.android.launcher3.LauncherAnimationRunner\$AnimationResult"
    private const val TARGETS = "[Landroid.view.RemoteAnimationTarget;"

    /** Builds the animation an app opens with from its icon. */
    val appLaunchAnimationStart = Member(
        "$TRANSITION_MANAGER\$AppLaunchAnimationRunner", "onAnimationStart", "int", TARGETS, TARGETS, TARGETS, ANIMATION_RESULT,
    )

    /** Builds the animation an app closes home with outside the gesture. */
    val homeOpenAnimationStart = Member(
        "$TRANSITION_MANAGER\$WallpaperOpenLauncherAnimationRunner", "onAnimationStart", "int", TARGETS, TARGETS, TARGETS, ANIMATION_RESULT,
    )

    /** Starts the animation a runner built. */
    val setAnimation = Member(ANIMATION_RESULT, "setAnimation", "android.animation.AnimatorSet", "android.content.Context", "java.lang.Runnable", "boolean")

    private const val RECENTS_CALLBACKS = "com.android.quickstep.RecentsAnimationCallbacks"
    private const val RECENTS_CONTROLLER = "com.android.quickstep.RecentsAnimationController"
    private const val SURFACE = "com.android.quickstep.util.SurfaceTransaction\$SurfaceProperties"

    /** The gesture's recents animation starting, with the windows it moves. */
    val recentsAnimationStart = Member(
        RECENTS_CALLBACKS, "onAnimationStart",
        "com.android.systemui.shared.system.RecentsAnimationControllerCompat", TARGETS, TARGETS, "android.graphics.Rect", "android.os.Bundle", "android.window.TransitionInfo",
    )

    /**
     * A gesture's handler, put back by a newer gesture's, setting the
     * launcher back to its resting state; on the way it cancels the launcher's
     * current animation, an app's launch among them.
     */
    val staleGestureReset = Member("com.android.quickstep.AbsSwipeUpHandler", "resetStateForAnimationCancel")
    val cancelStateAnimation = Member("com.android.launcher3.statemanager.StateManager", "cancelAnimation")

    // What the launcher sets on a window's surface each frame, launching or under the gesture.
    val surfaceMatrix = Member(SURFACE, "setMatrix", "android.graphics.Matrix")
    val surfaceCrop = Member(SURFACE, "setWindowCrop", "android.graphics.Rect")
    val surfaceCornerRadius = Member(SURFACE, "setCornerRadius", "float")
    val surfaceOf = HostField(SURFACE, "mSurface")

    /** What else [DiagnosticTrace] follows: how recents animations and launch animations end. */
    val traced: List<Member> = listOf(
        Member(RECENTS_CALLBACKS, "onAnimationCanceled", "java.util.HashMap"),
        Member(RECENTS_CONTROLLER, "finishController", "boolean", "java.lang.Runnable", "boolean", "boolean", GESTURE_LOG_TEXT),
        Member("com.android.launcher3.LauncherAnimationRunner", "onAnimationCancelled"),
    )

    val fields: List<HostField> = listOf(surfaceOf)

    val all: List<Member> = traced + listOf(
        appLaunchAnimationStart, homeOpenAnimationStart, setAnimation, recentsAnimationStart, staleGestureReset, cancelStateAnimation, surfaceMatrix, surfaceCrop, surfaceCornerRadius,
    )
}

/** A field of a host class, by its declaring class and name. */
data class HostField(val className: String, val name: String)

/** A method or constructor of a host class, by its declaring class, name and parameter types, as Java names. */
data class Member(val className: String, val name: String, val parameters: List<String>) {

    constructor(className: String, name: String, vararg parameters: String) : this(className, name, parameters.toList())

    /** `QuickstepLauncher.startActivitySafely(View, Intent, ItemInfo)`, as the fixtures and the log write it. */
    val signature: String get() = "$name(${parameters.joinToString(",")})"

    companion object {
        const val CONSTRUCTOR = "<init>"
    }
}
