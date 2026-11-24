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
- vc21, two things ColorOS does that Pixel Launcher did not:
  - Every gesture home flies on the springs, not only a caught window
    (`Tuning.springHome`). Around `RectFSpringAnim.start`, with nothing caught,
    the springs start at the flight's `mStartRect` (the window's visible
    rectangle as the finger lets go) with the finger's speed (`start`'s
    `PointF`, pixels a millisecond), aim at `getTargetRect()`, and take the
    gesture's app window (`RecentsAnimationCallbacks.onAnimationStart`'s app
    target in mode 1) through the same `HomeFlight` as a caught one. The
    window's corner at the start is the device's window corner
    (`QuickStepContract.getWindowCornerRadius`) scaled to that rectangle.
  - The launcher's own flight's progress is now read from its spring
    (`RectFSpringAnim.mCurrentScaleProgress`, what it passes its listeners),
    not from the icon, so a flight with no icon lands too.
  - Opening an app again while its window flies home: the shell cancels the
    gesture (`onAnimationCanceled`) and starts the launch about 300 ms later
    (vc19 trace). The window's last shown place and its springs are handed to
    `SpringLaunch.returnFrom`; a launch of the same task within 1 s starts
    there instead of at the icon, keeping the flight's speed if within 100 ms,
    as ColorOS's `copyNextAnimState` and `inheritVelocity` do.
  - Not known yet: what the screen shows in those 300 ms.
- vc21 on the device (trace and video, 24 Nov): every gesture home logged
  `home flight on the springs`, and every flight landed at 100 %. Two things
  were wrong:
  - In the video a caught window showed tiny, near the top left, for the whole
    gesture, while its icon flew big beside it. The break frame was set on the
    change's own surface. For the launcher the shell wraps that surface in a new
    leash (`TransitionUtil.newTarget`, with the leash map), reparents it there
    and sets it back to the wrapper's origin, keeping its scale. So the
    launcher's place, set on the wrapper, was scaled again by the break frame's
    0.2 to 0.5, toward the top left. Every catch since vc14 had this, less
    visibly at larger scales. From vc22 SystemUI sets the break frame on the
    wrapper: around `RecentsTransitionHandler.startAnimation` it takes the
    fresh frame; after `newTarget` returns the target for that task, it puts the
    frame on the target's `leash` in the same start transaction.
  - Tapping the app's icon while its window flew home never logged `back from
    its flight home`. The shell does not cancel the gesture then: Pixel Launcher
    ends its flight at once (`RectFSpringAnim.end()`, flights of 48 to 120 ms
    against 220 ms and more untouched), then finishes the gesture home, and the
    launch follows about 90 ms later. The jump of its progress to 1 also pulled
    the window onto its icon in one frame. From vc22, `end()` on the flight
    marks it cut short: the window is no longer pulled home, and on
    `finishController(true)` a window whose springs are under 90 % of the way
    is handed to `SpringLaunch.returnFrom`, as on a cancel.
- vc22 on the device (24 Nov), SystemUI restarted: every break frame logged
  `on the launcher's leash`, and the user saw the tiny top-left window gone.
  SystemUI only takes a new module build when it restarts itself; Vector's
  hot reload restarts the launcher alone.
- Still in rapid open and close:
  - Pixel Launcher calls `RectFSpringAnim.end()` at the end of every flight,
    not only a cut one, so every flight was marked cut short. From vc23 only
    an `end()` before its own flight reaches 95 % counts, and its callers are
    traced, to learn what cuts it (a touch down, by the timing).
  - A launch the next gesture took before its first frame (`initBackgroundStateUI`
    or the stale reset, 4 to 15 ms in) was let jump to its end, so the window
    flashed whole between a cut flight and the next gesture. When such a
    launch starts where a flight home stopped, the cancel is now skipped and
    `SpringLaunch.handOver` hands on a window on springs from that place.
  - Between a cut flight's `finishController(true)` and the next launch the
    window is gone for 85 to 110 ms. The flight is cut on the touch, the launch
    starts on the click, and the shell hides the app in between. Not solved.
- vc23 on the device (24 Nov) named what cuts a flight home short: a tap
  on an icon. Each cut logged two `RectFSpringAnim.end()` calls, from
  `FloatingIconView.fastFinish` (through `LauncherSwipeHandlerV2$3`'s
  lambda) and from `SwipeUpAnimationLogic$RunningWindowAnim$2.cancel`
  (through `RectFSpringAnim.cancel`). The launcher finishes the gesture home
  12 to 16 ms later, and the launch of the tapped app starts 85 to 125 ms
  after that, with the window hidden in between. `taken before its first
  frame` was seen once and worked.
- vc24 tried filling that gap with a picture of the window
  (`ActivityManagerWrapper.takeTaskThumbnail`) drawn in the home screen's
  overlay. It was never run on the device: the owner asked to follow
  ColorOS instead, and vc25 removed it. The first trace line names the
  module build since vc24.
- What ColorOS does (OplusLauncher, jadx): it never launches the app again.
  While the window flies home, its flying icon layer takes touches
  (`OplusLauncherSwipeHandlerV2Impl`'s home animation factory:
  `IconHolder.setOnTouchListener` marks `setMaybeReverseGestureAnimation` on
  down, `setOnClickListener` calls `tryReverseRecentAnim`). That turns the
  same `CustomRectFSpringAnim` back to the full window
  (`reverseToOpen`: start rect = current rect, target = the task's full
  bounds, alpha back to 1, `AnimType.REVERSE_TO_OPEN_REMOTE` springs), sets
  the gesture's end target to `LAST_TASK`, and plays the home screen's
  content animation backwards; the recents animation then ends in the app.
  A tap on a workspace icon instead connects the new launch to the running
  flight (`AnimationRecord.tryConnectExistingAnim` -> `copyNextAnimState`),
  which needs ColorOS's shell interrupt.
- vc25 does the first on Pixel Launcher:
  - `QuickstepLauncher.startActivitySafely(View, Intent, ItemInfo)`, around:
    a tap on the app whose window flies home (by package, from the gesture
    target's `taskInfo`) is spent there; `CatchByGesture.reverseToApp`
    retargets the springs, from where the window shows and at its speed, to
    the task's full bounds and corner on `Motion.LAUNCH`.
  - While it turns back: the window's alpha is 1, the floating icon's alpha
    0, the launcher's frames on the surface are only overwritten, and once
    the launcher stops writing, the springs place the window themselves.
  - `finishController(true)` from the launcher's flight is held, and called
    again with `toHome` and `sendUserLeaveHint` false once the springs settle:
    the gesture ends back in the app, as `LAST_TASK`. Should none come in
    1.5 s, the catch lets go.
  - Every tap during a flight traces whether it was taken. A cut flight
    traces 20 callers, to show whether Pixel Launcher cuts it at the touch
    down (then the tap finds nothing left to turn back) or at the click.
- vc25 on the device (24 Nov): every tap mid-flight logged `reverse: tap
  not taken, no window in flight`. The 20 callers of the cut show why: Pixel
  Launcher ends the flight at the touch down, before any click.
  `InputConsumerProxy.onInputConsumerMotionEvent` ->
  `initInputConsumerIfNeeded` -> the swipe handler's callback ->
  `AbsSwipeUpHandler.endRunningWindowAnim(true)` -> `RectFSpringAnim.cancel`,
  then `finishController(true)` 10 ms later; the click reaches the icon
  40 to 60 ms after that.
- vc26 does what ColorOS's `setMaybeReverseGestureAnimation` does on its
  flying icon layer's touch down: `endRunningWindowAnim`, around, is held
  while called from a touch down (`onInputConsumerMotionEvent` with
  `ACTION_DOWN`, around) during a flight. The flight goes on. A click on the
  app's icon then turns it back (`reverseToApp` drops the held end). Any
  other touch: 48 ms after its `ACTION_UP` or `ACTION_CANCEL`, the held
  `endRunningWindowAnim` is called as Pixel Launcher would have.
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
