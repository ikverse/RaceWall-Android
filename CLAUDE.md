# RaceWall for Android: rules for working in this repository

A native Kotlin + Jetpack Compose rebuild of the desktop RaceWall app
(`../RaceWall`) for Android tablets. The desktop repository's `docs/design.md` and
`design/DESIGN-LOCK.md` are binding here too: colours, type, brand and burn-in rules come from them.

## Approval before implementing

Before editing or creating any source file, post a plain-language list of exactly what will change
and wait for the user's next message to be the literal word **approve**. Nothing else counts.
Anything discovered mid-work that is not on the list goes back through the same step.

## Checking

- Screens are checked as screenshots of the real app, with real race data, before they are shown to
  the user: Roborazzi images (`./gradlew :app:recordRoborazziDebug`, written to `app/screenshots/`)
  and `adb exec-out screencap` from the emulator or a tablet. They are compared with the desktop's
  `../RaceWall/screenshots/`. Never show an unchecked screen.
- Numbers shown to the user are checked against an independent source (OpenF1) for the same moment.
- `./gradlew test lint assembleDebug` must pass before reporting work as done.

## Facts worth knowing

- Ported module by module from the desktop's TypeScript: `core/bus` (packages/bus), `core/clock`
  (packages/clock), `core/topics` (packages/topics), `data/archive` (packages/f1-archive). Tests
  port the desktop's vitest cases and reuse its British GP 2025 fixture.
- One desktop CSS pixel is one dp. A 2560 x 1600 tablet at 320 dpi is a 1280 x 800 window.
- Segoe UI and Segoe Fluent Icons may not ship in an Android app. This app uses Selawik (Microsoft's
  OFL stand-in with Segoe UI's metrics and fixed-width digits) and Fluent UI System Icons (MIT).
  Russo One is only for the logo and the app icon. Licences are in `third_party/`.
- Build from Git Bash with `JAVA_HOME` set to Android Studio's JBR (`C:\Program Files\Android\Android
  Studio\jbr`). `gradlew.bat` is not found when run from PowerShell.
- The tablet emulator is `RaceWall_Tablet_API_36` (Pixel Tablet, 2560 x 1600). Run it with
  `-no-window` so it never takes focus, and stop it afterwards (`adb emu kill`).
