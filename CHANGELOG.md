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
- Settings for the duration of opening from an icon and closing home with
  Back, and for the stiffness and damping of the gesture home spring.
