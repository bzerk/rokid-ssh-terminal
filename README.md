# Rokid Terminal

Glasses-native SSH and tmux control surface for Rokid glasses.

## Current state

This workspace has been pivoted away from the original `clawsses` phone-companion flow.

The buildable path is now:

- single sideloaded glasses app
- direct SSH over Wi-Fi
- strict green-on-black HUD
- boxed focus states for directional navigation
- control-surface launcher activity
- terminal activity for live tmux pane viewing and command send

## What works in this revision

- Launches into a new `ControlSurfaceActivity`
- Stores SSH settings locally on-device
- Connects directly to a remote host with password or pasted private key auth
- Trusts the first seen host key fingerprint and rejects later mismatches
- Lists tmux sessions
- Creates tmux sessions
- Cycles or selects the active tmux session
- Opens a terminal screen that refreshes the active tmux pane and sends commands

## Known gaps

- Terminal rendering is currently line-oriented tmux pane capture, not full ANSI/VT emulation yet
- The old phone/glasses bridge code is still present in the repo but no longer used by the launcher flow
- Voice, camera, TTS, and companion-app behaviors are intentionally out of scope in this revision

## Build

```bash
./gradlew :glasses-app:assembleDebug
```

APK output:

```text
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

## Runtime notes

- `tmux` must be installed on the remote host
- Bluetooth keyboard support is assumed for serious terminal use
- The app stores SSH settings in on-device shared preferences under `rokid_terminal`
