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

## Spring launch (`SpringLaunch`)

Pixel Launcher still runs its own launch: it decides when the launch starts
and ends, fades the window in, and moves the home screen and the flying icon.
Only the window's place, each frame, comes from this module's springs.

- `QuickstepTransitionManager$AppLaunchAnimationRunner.onAnimationStart`,
  around: notes the opening target (`leash`, `screenSpaceBounds`,
  `rotationChange`, read by name).
- `LauncherAnimationRunner$AnimationResult.setAnimation`, around: for an app
  launch, adds a keeper `ValueAnimator` to the set. The keeper holds the
  launch open until the spring settles, then ends.
  - The end corner radius comes from
    `QuickStepContract.getWindowCornerRadius(Context)`.
  - Rotated launches keep Pixel Launcher's animation.
- `SurfaceTransaction$SurfaceProperties.setMatrix` / `setWindowCrop` /
  `setCornerRadius` on the launch leash, with arguments replaced:
  - The first frame is Pixel Launcher's own, and the spring starts from it
    (the icon).
  - Every later frame shows the spring's frame (`RectSpring`, `Motion.LAUNCH`),
    fitted to the window by `WindowFit`. The window is scaled evenly to cover
    the visible rectangle, never above 1, and cropped around its centre.
- Once Pixel Launcher stops setting the window's place (its animator lasts
  508 ms), the keeper sets it itself through `SurfaceControl.Transaction`.
  The hidden `setMatrix`, `setWindowCrop` and `setCornerRadius` are reached by
  name.
- `FloatingIconView.update(float, RectF, float, float, float, boolean, int)`,
  arguments replaced: while opening, the icon's bounds and corner follow the
  spring's frame.
- The springs are ColorOS 16's (`SpringAnimConstants` in `OplusLauncher` from
  the public `oplus/ossi` dump, read with jadx, not copied). Each is
  stiffness / damping:

  | Scene | Position and size | Shape | Corner |
  |---|---|---|---|
  | Launch | 420 / 1.04 | 420 / 1.04 | 400 / 1 |
  | Follow | 400 / 1 | 400 / 1 | 400 / 1 |
  | Home | 220 / 0.85 | 220 / 0.85 | 400 / 1 |

  As ColorOS does, stiffness is divided by the square of the duration factor:
  the chosen opening duration times the system animator scale.
- vc9 was recorded on the device and stepped through frame by frame
  (about 90 fps), next to stock. The spring launch worked: the window grew
  from the icon, the flying icon went with it, and frames were evenly paced.
  - Stock fills the screen about 240 ms after the tap; the springs took
    about 280 ms.
  - The springs had a slow last stretch. The window stood full height a while
    before full width, because the vertical position was sprung by its
    centre.
  - ColorOS's `CustomRectFSpringAnim` springs the top edge instead (tracking
    0) and grows the height down from it. vc10 does the same.
- `CatchByGesture` (vc11) carries the same springs (`MovingWindow`) into a
  gesture started mid-launch:
  - `AbsSwipeUpHandler.resetStateForAnimationCancel()`, around, and
    `StateManager.cancelAnimation()`, around: the cancel is skipped while a
    spring launch runs (vc6 showed this removes the flash).
  - `LauncherAnimationRunner.onAnimationCancelled()`, around: the shell has
    taken the window, so `SpringLaunch.handOver()` stops the launch and gives
    up its `MovingWindow`, place and speed included.
  - `RecentsAnimationCallbacks.onAnimationStart(...)`, after: the gesture's
    leash for that task; the springs switch to `Motion.FOLLOW`.
  - `SurfaceProperties.setMatrix` / `setWindowCrop` / `setCornerRadius` on
    that leash, arguments replaced: each place the gesture sets becomes the
    springs' target, and the window shows the springs' place until it is
    within 1.5 px of the gesture's, after which the gesture's own places
    stand.
  - `RecentsAnimationController.finishController(false, ...)`, after: back
    to the app, the springs let go.
- vc6 to vc8 tried to catch a launch cut short by the gesture by stitching two
  animators together. That was dropped: the engine is to carry the gesture
  next, as one retargeted movement.

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
