# Animatuna

Vector / libxposed API 102 module that patches Pixel Launcher's window
animations. `HOOK_NOTES.md` records every host member it touches and why.

## Principles

SOLID, DRY, KISS, YAGNI, Law of Demeter, separation of concerns, Boy Scout
rule. Layout: `tuning` (pure, desk-testable), `hook` (framework glue), `host`
(launcher patches), `settings` (the module's screen).

## No hardcoding

- Every launcher member lives in `host/LauncherSymbols.kt`. Each one is checked
  against the recorded builds under `app/src/test/resources/hosts/`.
- Members R8 renames are never named. The home spring is tuned through the
  launcher's own resources (`Resources.getFloat`) for this reason.
- A missing member turns its feature off, never the launcher.

## Language

Code that runs inside Pixel Launcher (reflection into launcher classes,
libxposed callbacks, Android views) is Kotlin. XAX cannot emit classes into
this Gradle APK, and this code needs the host process.

## Device

- Record a launcher build with `dexdump` on its APK. Store only the classes in
  `LauncherSymbols` with their method names and parameter types, no code.
- Bump `-PmoduleVersionCode` above the installed one, then restart Pixel
  Launcher.
