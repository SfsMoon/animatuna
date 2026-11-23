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

    private const val LAUNCHER = "com.android.launcher3.uioverrides.QuickstepLauncher"
    private const val ITEM_INFO = "com.android.launcher3.model.data.ItemInfo"
    private const val RUNNABLE_LIST = "com.android.launcher3.util.RunnableList"
    private const val TASK_ANIMATION_MANAGER = "com.android.quickstep.TaskAnimationManager"
    private const val GESTURE_LOG_TEXT = "com.android.quickstep.util.ActiveGestureLog\$CompoundString"
    private const val TRANSITION_MANAGER = "com.android.launcher3.QuickstepTransitionManager"
    private const val ANIMATION_RESULT = "com.android.launcher3.LauncherAnimationRunner\$AnimationResult"
    private const val TARGETS = "[Landroid.view.RemoteAnimationTarget;"

    /** Starts an app from a view of the home screen, as a tap on its icon does. */
    val startActivitySafely = Member(LAUNCHER, "startActivitySafely", "android.view.View", "android.content.Intent", ITEM_INFO)

    val emptyRunnableList = Member(RUNNABLE_LIST, Member.CONSTRUCTOR)

    /** Holds the recents animation the gesture home plays in; one per display. */
    val taskAnimationManager = Member(
        TASK_ANIMATION_MANAGER, Member.CONSTRUCTOR,
        "android.content.Context", "int", "com.android.launcher3.display.DisplayController", "com.android.app.displaylib.PerDisplayRepository",
    )
    val isRecentsAnimationRunning = Member(TASK_ANIMATION_MANAGER, "isRecentsAnimationRunning")

    /** Ends the recents animation: (to home, at once, then run, why). */
    val finishRunningRecentsAnimation = Member(
        TASK_ANIMATION_MANAGER, "finishRunningRecentsAnimation", "boolean", "boolean", "java.lang.Runnable", GESTURE_LOG_TEXT,
    )
    val emptyGestureLogText = Member(GESTURE_LOG_TEXT, "newEmptyString")

    /** The gesture's handler taking the recents animation; it decides there whether to hand the way home to the shell. */
    val swipeHandlerRecentsStart = Member(
        "com.android.quickstep.AbsSwipeUpHandler", "onRecentsAnimationStart",
        "com.android.quickstep.RecentsAnimationController", "com.android.quickstep.RecentsAnimationTargets", "android.window.TransitionInfo",
    )

    /**
     * Whether the window's way home after the gesture is handed to the shell
     * (Android 17's long-lived return animations), which ends the recents
     * animation as the window starts home rather than once it lands.
     */
    val handsOffHome = HostField("com.android.quickstep.AbsSwipeUpHandler", "mHandOffAnimationToHome")

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

    // What the launcher does around a launch during the gesture home, followed by [DiagnosticTrace].
    private const val SWIPE_HANDLER = "com.android.quickstep.AbsSwipeUpHandler"
    private const val RECENTS_CALLBACKS = "com.android.quickstep.RecentsAnimationCallbacks"
    private const val RECENTS_CONTROLLER = "com.android.quickstep.RecentsAnimationController"
    private const val TRANSITION_INFO = "android.window.TransitionInfo"

    val traced: List<Member> = listOf(
        Member(RECENTS_CALLBACKS, "onAnimationStart", "com.android.systemui.shared.system.RecentsAnimationControllerCompat", TARGETS, TARGETS, "android.graphics.Rect", "android.os.Bundle", TRANSITION_INFO),
        Member(RECENTS_CALLBACKS, "onTasksAppeared", TARGETS, TRANSITION_INFO),
        Member(RECENTS_CALLBACKS, "onAnimationCanceled", "java.util.HashMap"),
        Member(SWIPE_HANDLER, "onTasksAppeared", TARGETS, TRANSITION_INFO),
        Member(SWIPE_HANDLER, "onRecentsAnimationFinished", RECENTS_CONTROLLER),
        Member("com.android.quickstep.LauncherSwipeHandlerV2", "finishRecentsControllerToHome", "java.lang.Runnable"),
        Member("com.android.quickstep.window.RecentsSurfaceSwipeHandler", "finishRecentsControllerToHome", "java.lang.Runnable"),
        Member(RECENTS_CONTROLLER, "handOffAnimation", TARGETS, "[Landroid.window.WindowAnimationState;"),
        Member(RECENTS_CONTROLLER, "finishController", "boolean", "java.lang.Runnable", "boolean", "boolean", GESTURE_LOG_TEXT),
        Member("com.android.launcher3.LauncherAnimationRunner", "onAnimationCancelled"),
    )

    val fields: List<HostField> = listOf(handsOffHome)

    val all: List<Member> = traced + listOf(swipeHandlerRecentsStart,
        startActivitySafely, emptyRunnableList, taskAnimationManager, isRecentsAnimationRunning,
        finishRunningRecentsAnimation, emptyGestureLogText, appLaunchAnimationStart, homeOpenAnimationStart, setAnimation,
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
