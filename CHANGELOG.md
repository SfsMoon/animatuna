# Changelog

## 0.1.0

- Apps open from their icon on springs, as on ColorOS. Size and shape move
  at their own pace, and the flying icon follows the window.
- Experimental, off by default: a gesture started while an app opens takes
  the window where the launch has it, on the same springs.
- Fixed: Pixel Launcher could restart when a spring launch settled.
- After the gesture home, the window flies into its icon on ColorOS's
  springs, carried by the finger's speed.
- A window caught mid-launch flies home on the same springs and fades into
  its icon as it arrives, not halfway there. Quick open and close no longer
  makes the window jump to where an earlier gesture left it.
- Every gesture home flies on ColorOS's springs, from the window's own
  place and the finger's speed, not only a window caught mid-launch.
- An app opened again while its window flies home opens from where the
  window is, not from its icon.
- Fixed: a window caught mid-launch showed tiny in the top left corner for the
  whole gesture.
- Tapping an app's icon while its window flies home now opens it from the
  window, rather than snapping the window into the icon first.
- Tapping an app's icon while its window flies home turns the window back
  to the app on the same springs, as ColorOS does, instead of launching the
  app anew.
- Fixed: an app turned back from its flight home ignored swipes and taps
  for about a second, as the launcher still took its touches.
- Fixed: tapping another app's icon while a window flies home opened that
  app with no animation.
- A swipe on a window turning back to its app takes the window where it
  is, as on ColorOS, instead of being ignored until the window is whole.
- Fixed: an app opened and swiped away at once showed with no launch.
- Settings for the duration of opening from an icon and closing home with
  Back, and for the stiffness and damping of the gesture home spring.
