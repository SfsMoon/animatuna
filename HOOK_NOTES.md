# Hook notes

Every Pixel Launcher member Animatuna touches, checked against
`app/src/test/resources/hosts/CP4A.261105.040.A1/` (Pixel Launcher 17,
versionCode 907).

## Launch during the gesture home (`LaunchDuringHomeGesture`)

- A device trace (vc2) showed what happens on Pixel Launcher 17. The recents
  animation ends as the window starts home
  (`LauncherSwipeHandlerV2.finishRecentsControllerToHome`), because the way
  home is handed to the shell.
- A tap while the window still flies home starts an ordinary launch:
  `AppLaunchAnimationRunner.onAnimationStart` runs and its animation starts
  unfinished. The app still shows without an animation.
- Hooked members:
  - `AbsSwipeUpHandler.onRecentsAnimationStart(RecentsAnimationController, RecentsAnimationTargets, TransitionInfo)`,
    after: clears `mHandOffAnimationToHome`, so the launcher flies the window
    home itself and keeps the recents animation running until it lands.
  - `TaskAnimationManager.<init>(Context, int, DisplayController, PerDisplayRepository)`,
    after: remembers each manager (one per display).
  - `QuickstepLauncher.startActivitySafely(View, Intent, ItemInfo)`, around:
    while a manager's `isRecentsAnimationRunning()`, calls
    `finishRunningRecentsAnimation(toHome = true, forceFinish = true, then, ActiveGestureLog.CompoundString.newEmptyString())`.
    It then starts the app again from `then` on the view's thread, with a
    400 ms fallback, and returns an empty `RunnableList` for the tap it holds
    back.

## Window animation speed (`WindowAnimationSpeed`)

- `QuickstepTransitionManager$AppLaunchAnimationRunner.onAnimationStart`
  (opening from an icon) and `$WallpaperOpenLauncherAnimationRunner.onAnimationStart`
  (closing home outside the gesture) are hooked around. Each marks the thread
  while it builds its animation.
- `LauncherAnimationRunner$AnimationResult.setAnimation(AnimatorSet, Context, Runnable, boolean)`
  is hooked around. Before it starts the set, it multiplies every duration and
  start delay in the set by the chosen factor.

## Gesture home spring (`HomeGestureSpring`)

- `android.content.res.Resources.getFloat(int)`, result: Pixel Launcher's
  `swipe_up_rect_*_stiffness*` entries are multiplied, and its
  `swipe_up_rect_*_damping_ratio*` entries are replaced. Entries are matched by
  name, once per id.
- `RectFSpringAnim` and androidx `SpringForce` are not reached: the release
  build renames the latter.
