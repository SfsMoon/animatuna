# Animatuna

A Vector / LSPosed module (libxposed API 102) for Pixel Launcher's app
animations on Android 17.

- **Flying home on springs:** ColorOS's swipe-home springs (220 / 0.85).
  Pixel Launcher's own spring stays, so the finger's speed carries into the
  flight.
- **Opening from an icon:** 50% to 200% of Pixel Launcher's duration.
- **Closing home with Back:** 50% to 200% of Pixel Launcher's duration.
- **Gesture home spring:** stiffness 50% to 300%, and damping from bouncy (0.40)
  to none (1.00), or stock.

## Install

1. Install the APK and enable it in Vector or LSPosed. Its scope is Pixel
   Launcher (`com.google.android.apps.nexuslauncher`) and, for the
   experimental catch by the gesture, System UI (`com.android.systemui`).
2. Restart Pixel Launcher: `adb shell am force-stop com.google.android.apps.nexuslauncher`.
3. Open Animatuna to choose. Changes apply at the next animation.

Logs: `adb logcat -s Animatuna`. On start it says which features are on or off.

## Build

```
./gradlew :app:testDebugUnitTest :app:assembleRelease -PmoduleVersionCode=<n>
```

Release signing reads `ANDROID_KEYSTORE_*` from the environment.
`HOOK_NOTES.md` lists every Pixel Launcher member the module touches and why.
