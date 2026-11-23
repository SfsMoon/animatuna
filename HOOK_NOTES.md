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
- vc12 showed what still goes wrong (trace and video, frame by frame):
  - The gesture does move the caught surface, and the hold between the
    hand-over and its first move works.
  - The home screen blinks to bare wallpaper for about 50 ms. Skipping the
    stale reset and cancelling the launch ourselves leaves the launcher's
    content hidden, as mid-launch.
  - The window lands smaller than where the launch had it. The gesture's
    surface does not seem to share the launch surface's coordinates.
  - From vc13 the catch has its own switch, off by default. It traces the
    gesture's own place next to the springs' for each caught frame.
- What ColorOS 16 does here (OplusLauncher's `RemoteAnimationRunnerCompat.mergeAnimation`,
  read with jadx):
  - It does not end the launch when the shell merges the gesture into it. It
    reads the gesture's recents controller from its framework's
    `TransitionInfo.mExt` and keeps the same spring running under the finger
    (`continueNextAnimDirectlyIfCould`).
  - It hands the window's place (`BreakParam`: position, scale, crop, corner,
    alpha) and an interrupt flag (`WindowContainerTransaction.mExt`) to its
    shell, which sets that place on the task's surface between the two
    transitions.
  - Stock Android has none of these extensions, so the window stands at full
    screen between the launch ending and the gesture starting.
- vc14 does ColorOS's hand-over across two processes. The module's scope now
  takes SystemUI too:
  - Launcher: at the hand-over, `CatchByGesture` writes the window's place
    (`BreakFrame`) to the global setting `animatuna_break_frame`. The launcher
    holds `WRITE_SECURE_SETTINGS`.
  - SystemUI: `BreakFrameInShell`, around
    `RecentsTransitionHandler.startAnimation(IBinder, TransitionInfo, Transaction, Transaction, TransitionFinishCallback)`.
    When a fresh break frame (under 1 s old) names a task in the transition,
    its place is set on that change's leash in the start transaction, so the
    gesture's transition starts with the window there.
- vc15 fixes three things found on the device:
  - A crash (`StackOverflowError`, from the user's crash buffer). The keeper's
    `end()` runs its last update at once, back into `keep()`, which ended it
    again, forever, whenever the spring settled on a keeper frame. The
    keeper's listeners are now removed before it ends.
  - The catch felt late. It followed the finger on `FOLLOW` (400 / 1, about
    50 ms behind) until within 1.5 px, almost never. It now catches up on
    `CATCH_UP` (1200 / 1) and lets go within 6 px.
  - The catch felt torn. The springs' own transactions kept coming beside the
    launcher's synced ones. They now stop at the gesture's first frame.
- vc15's traces showed SystemUI placing the break frame 5 to 20 ms after the
  hand-over, as meant. Two things were wrong:
  - Nearly every frame was placed twice. The second time came 400 to 900 ms
    later, on the next gesture's transition, against a window already whole
    again. The frame is now one-shot (SystemUI clears it) and fresh for 200 ms.
  - The launcher let go of the window when the finger lifted
    (`finishController(true)`), before the springs had caught up, so the
    window leapt to the launcher's place. It now goes on catching up through
    the flight home. It lets go when near, or back in the app.
- vc18 tried to start Pixel Launcher's home spring from the caught window's
  place and speed. Its trace showed that hook never ran.
  - The catch kept following the launcher's flight frames. During the flight
    those crop the window toward the icon in a way `WindowFit.shown` misreads.
  - In the video the window flew up and left, away from its icon.
- vc19 flies a caught window home on the same springs, as ColorOS does:
  - `RectFSpringAnim.start`, around: while a window is caught, its target is
    set to the spring's `getTargetRect()` (the icon) and the springs switch to
    `Motion.HOME`. From then on the launcher's frames on that surface are only
    overwritten, never read.
  - `FloatingIconView.update` while closing (`isOpening` false), arguments
    replaced: the icon's rectangle and corner follow the springs, and its
    corner becomes the springs' target corner.
  - Every home flight start is traced. If none starts within 120 ms of
    `finishController(true)`, the catch lets go.
- vc19 on the device (trace and video, 23 Nov): every caught flight logged
  `catch: flying home on the same springs`, and the user found it felt like
  ColorOS. Three things were still wrong:
  - In the video the window faded out mid-air, halfway to its icon. Pixel
    Launcher fades the window, and lets its icon take the window's shape, by
    its own flight's progress (window alpha 1 to 0 by the icon's
    shape-progress start, about 0.9), and its flight runs ahead of the
    springs. It also ends the gesture (`finishController(true)`) when its own
    flight lands, about 250 ms in, before the springs got there.
  - A catch outlived its gesture. After the window landed, or after the shell
    cancelled the gesture (`RecentsAnimationCallbacks.onAnimationCanceled`, a
    tap on an icon), the next gesture on the same task took the old springs,
    and the window leapt to where they had been (near the icon, or near full
    size).
  - Four of twelve launches were cancelled by another path than the stale
    reset: `DefaultAnimationFactory.initBackgroundStateUI` from the new
    gesture's `prepareRecentsUI`, before the shell handed the window over.
    Those launches jumped to their end and were never caught.
- vc20:
  - `HomeFlight` draws the window where the springs have it and pulls it in
    toward the icon over the last 70 % of the launcher's own flight
    (smoothstep), so it lands as that flight ends. The window's alpha
    (`SurfaceProperties.setAlpha`, replaced on the caught surface) and the
    icon's progress (`FloatingIconView.update`'s third argument) follow how
    far the window itself has come, by its width.
  - A catch lasts one gesture: it lets go on `onAnimationCanceled`, on
    `finishController(true)` once flying (the window has landed), and when a
    second recents start finds it already used.
  - `initBackgroundStateUI` is wrapped like the stale reset, so its cancel of
    a spring launch is skipped too. The skip now needs a launch whose spring
    has started (`SpringLaunch.isLaunching`), so a launch caught before its
    first frame still ends as before.
- Not a bug: the bare wallpaper behind a caught window during the gesture is
  stock. Pixel Launcher shows the "behind an app" state, workspace hidden,
  for any gesture from an app, until the window is let go home.
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
- With `Tuning.springHome` (vc17, on by default), every
  `swipe_up_rect_*stiffness*` reads ColorOS's swipe-home stiffness (220) and
  every `*damping_ratio*` its damping (0.85), in place of Pixel Launcher's
  (450 / 0.965 for x, 400 / 0.95 for y, 500 / 0.99 for scale). The sliders
  then adjust these.
  - Pixel Launcher's own spring keeps flying the window, so the finger's
    speed (`RectFSpringAnim.start(..., velocityPxPerMs)`) still carries in,
    and the flying icon and the home screen stay in step with it.
  - Moving the window on this module's own springs would have needed all
    three re-synced.
