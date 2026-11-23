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

    val all: List<Member> = listOf(
        startActivitySafely, emptyRunnableList, taskAnimationManager, isRecentsAnimationRunning,
        finishRunningRecentsAnimation, emptyGestureLogText, appLaunchAnimationStart, homeOpenAnimationStart, setAnimation,
    )
}

/** A method or constructor of a host class, by its declaring class, name and parameter types, as Java names. */
data class Member(val className: String, val name: String, val parameters: List<String>) {

    constructor(className: String, name: String, vararg parameters: String) : this(className, name, parameters.toList())

    /** `QuickstepLauncher.startActivitySafely(View, Intent, ItemInfo)`, as the fixtures and the log write it. */
    val signature: String get() = "$name(${parameters.joinToString(",")})"

    companion object {
        const val CONSTRUCTOR = "<init>"
    }
}
