# RaceWall for Android

RaceWall, the F1 timing wall, as a native app for Android tablets: Kotlin and Jetpack Compose,
rebuilt from the desktop app's design and data code.

## Build

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew test lint assembleDebug
```

The debug app is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Layout

| Module | What it is |
|---|---|
| `core/bus` | The F1 feed merge, the timeline of changes, and the bus that picks a data source per topic |
| `core/clock` | The replay clock |
| `core/topics` | The standard topics widgets read |
| `data/archive` | F1's public timing archive: download, cache, parse, and turn into topics |
| `app` | The window: title bar, panel canvas, timing tower, transport bar |

Fonts and icons: Russo One (logo), Selawik (text), and Fluent UI System Icons. Their licences are
in `third_party/`.
