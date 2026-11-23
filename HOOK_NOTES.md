# Hook notes

Every Pixel Launcher member Animatuna touches, checked against
`app/src/test/resources/hosts/CP4A.261105.040.A1/` (Pixel Launcher 17,
versionCode 907).

## A launch cut short by the gesture (diagnostics)

- Traces vc2 to vc4 showed what happens on Pixel Launcher 17. An app's launch
  animation runs its full 508 ms unless the gesture bar is touched while it
  runs.
- A touch cancels it, so the window jumps to full screen:
  `AbsSwipeUpHandler.resetStateForAnimationCancel` calls
  `BaseContainerInterface.onTransitionCancelled`, which calls
  `StateManager.goToState`, then `StateManager.cancelAnimation`. The launch
  animation is the state manager's current animation.
- The gesture then starts from the full-screen window.
- `LaunchHandoffProbe` records where the window stands at that cut, and where
  the gesture's first frames put it:
  - `SurfaceTransaction$SurfaceProperties.setMatrix(Matrix)`,
    `setWindowCrop(Rect)` and `setCornerRadius(float)`, after: the last values
    set on each surface (`mSurface`).
  - `QuickstepTransitionManager$AppLaunchAnimationRunner.onAnimationStart`,
    around: the opening task and its leash.
  - `RecentsAnimationCallbacks.onAnimationStart(...)`, after: the gesture's
    leash for that task.

## Catch an opening app (`CatchOpeningApp`, experimental)

- `AbsSwipeUpHandler.resetStateForAnimationCancel()` is hooked around. It
  marks the thread while a gesture's handler, put back by a newer gesture,
  resets the launcher.
- `StateManager.cancelAnimation()` is hooked around. It is skipped while that
  mark is set and an app's launch animation runs, so the launch keeps the
  window until the new gesture takes it.
- vc5 measured the cost of the cancel. The gesture's first frame came 70 to
  90 ms after it, and the window stood full screen in between.
- vc6 showed what happens once the cancel is skipped. The flash is gone, but
  the launch animation keeps moving the window toward full screen after the
  shell hands it to the gesture, and the two alternate frames.
- `LauncherAnimationRunner.onAnimationCancelled()` is hooked around. While a
  launch runs, the window's last place on the launch surface is kept and the
  launch animation is cancelled on the main thread.
- `RecentsAnimationCallbacks.onAnimationStart(...)` is hooked after. It finds
  the gesture's leash for the caught task.
- `SurfaceTransaction$SurfaceProperties.setMatrix`, `setWindowCrop` and
  `setCornerRadius` are hooked to replace their arguments:
  - on the launch's surface they record the place;
  - on the gesture's surface, for 300 ms from its first frame, the place is
    blended from the kept one (`WindowPlace.blend`, cubic ease-out).

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
