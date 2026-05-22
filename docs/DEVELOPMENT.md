# Development Notes

## Build & Deploy

### Prerequisites
- JDK 17: `sudo apt install openjdk-17-jdk`
- Android SDK at `~/android-sdk` with `local.properties` containing `sdk.dir=/home/charlie/android-sdk`
- ADB connected (USB or WiFi — see below)

### Build
```
./gradlew :glasses-app:assembleDebug
```
Output: `glasses-app/build/outputs/apk/debug/glasses-app-debug.apk`

### Install
```
adb install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```
If you get a signature mismatch error, uninstall first:
```
adb uninstall com.bzerk.rokidterminal
adb install glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

### Launch
```
adb shell am force-stop com.bzerk.rokidterminal
adb shell am start -n com.bzerk.rokidterminal/com.clawsses.glasses.ControlSurfaceActivity
```

## ADB over WiFi

You need USB connected just once to enable TCP mode, then never again until next reboot:
```
adb tcpip 5555
adb connect 10.0.0.21:5555
```
After that all adb commands work over WiFi. On reboot, `adb tcpip 5555` over USB once more.

## Pushing SSH Config Without a Keyboard

The app stores SSH credentials in SharedPreferences. To push them from the dev machine:

1. Write the XML file (example below)
2. Push to the staging area and copy into the app's private data dir:
```
adb push rokid_terminal.xml /data/local/tmp/rokid_terminal.xml
adb shell run-as com.bzerk.rokidterminal cp /data/local/tmp/rokid_terminal.xml /data/data/com.bzerk.rokidterminal/shared_prefs/rokid_terminal.xml
adb shell am force-stop com.bzerk.rokidterminal
```

The force-stop is required — Android caches SharedPreferences in memory and won't see a file change while the process is running.

Example XML:
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="host">10.0.0.21</string>
    <int name="port" value="22" />
    <string name="username">charlie</string>
    <string name="password">yourpassword</string>
    <string name="private_key"></string>
    <string name="passphrase"></string>
    <string name="auto_attach">tmux attach || tmux new</string>
    <float name="font_size" value="10.0" />
</map>
```

## App Architecture

- `ControlSurfaceActivity` — launcher activity, shows SSH session list / menu
- `TerminalActivity` — full-screen SSH terminal using connectbot termlib
- `InteractiveTerminalController` — manages JSch SSH session + shell channel, drives the terminal emulator
- `SettingsStore` — SharedPreferences wrapper (host, port, credentials, font size, active session)
- connectbot termlib 0.0.22 — terminal emulator library, renders via Jetpack Compose `Terminal` composable

## Bugs Fixed

### Bug 1 — Keyboard input ignored on ControlSurfaceActivity (committed)
Compose `onPreviewKeyEvent` only fires on a composable that holds focus. The root `Box` was missing `focusRequester`/`focusable()` and `LaunchedEffect` to request focus on launch.

Fix in `ControlSurfaceActivity.kt`:
```kotlin
val focusRequester = remember { FocusRequester() }
LaunchedEffect(Unit) { focusRequester.requestFocus() }

Box(
    modifier = Modifier
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { ... }
)
```

### Bug 2 — Touchpad input (no fix needed)
Initially suspected the temple touchpad sent `MotionEvent`s that the app wasn't handling. Turned out the Rokid firmware already maps touchpad gestures to D-pad `KeyEvent`s, so the Bug 1 focus fix was sufficient.

## UI Changes

- Removed the large "TERMINAL" header and the bottom bluetooth keyboard hint footer
- Status bar condensed to a single line: `font:<size>  <connection status>`
- Removed the white border around the terminal surface
- Font size: `Ctrl++` / `Ctrl+-` to adjust (6–24sp), persisted to SharedPreferences

## Display & Color Handling

The Rokid glasses display is a monochrome green phosphor waveguide — effectively 4 luminance levels: bright green, medium green, dim green, black.

Rather than trying to suppress colors in the shell (which is fragile — every program has its own color config), the app intercepts the raw byte stream from SSH and remaps all ANSI color escape sequences to 3 grayscale levels before they reach the terminal emulator:

- **Bright** (bright green, yellow, cyan, white) → `38;2;255;255;255` (white → max brightness on display)
- **Medium** (green, cyan, yellow, gray) → `38;2;160;160;160`
- **Dim** (red, blue, magenta, dark gray, black) → `38;2;70;70;70`
- **Background colors** → reset to default (black)

This works for 16-color, 256-color, and 24-bit truecolor sequences. The luminance formula `0.299R + 0.587G + 0.114B` is used for truecolor and 256-color inputs.

The default foreground color is white (max brightness = brightest green on the display).

The prompt is also cleaned up on connect:
```bash
unset PROMPT_COMMAND; export PS1='\u@\h:\w\$ '
```
`PROMPT_COMMAND` is the main culprit for colored prompts re-appearing — many distros use it to dynamically rebuild PS1 with colors after each command.

## Scrollback

The connectbot terminal library does not expose its internal scrollback buffer externally — there's no API to scroll from outside the `Terminal` composable.

Practical solution: **tmux copy mode**. Press `Ctrl+B` then `[` to enter scroll mode. Arrow keys scroll. `q` exits back to the live terminal.
