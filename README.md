# Rokid SSH Terminal

Glasses-native SSH terminal and tmux control surface for Rokid glasses.

This project now targets a single sideloaded glasses app. There is no phone companion in the active launcher flow. The app connects directly over Wi-Fi to a remote machine via SSH and provides two focused surfaces:

- `ControlSurfaceActivity` for directional-navigation session management
- `TerminalActivity` for a real interactive SSH terminal

## Features

- Direct SSH from the glasses over Wi-Fi
- Password auth or pasted private-key auth
- TOFU host fingerprint persistence and mismatch rejection
- tmux-first workflow for list/create/select/attach
- Full terminal rendering through `org.connectbot:termlib`
- Green-on-black glasses-native UI with explicit boxed focus states
- Bluetooth-keyboard-friendly terminal path

## Current App Structure

- `glasses-app`
  - active launcher and active product surface
- `shared`
  - retained protocol models and shared support code
- `phone-app`
  - legacy code from the original `clawsses` architecture, not part of the current glasses-only launcher flow

## Build

```bash
./gradlew :glasses-app:assembleDebug
```

APK output:

```text
glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

## Install

```bash
adb install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
adb shell am start -n com.bzerk.rokidterminal/com.clawsses.glasses.ControlSurfaceActivity
```

## Runtime Notes

- `tmux` should be installed on the remote host
- SSH settings are stored on-device in `SharedPreferences` under `rokid_terminal`
- Bluetooth keyboard input is strongly recommended for real terminal use
- The current dev build is debug-signed and intended for sideload testing

## Public Repo Notes

- Local secrets are intentionally not committed
- `local.properties` is ignored
- Rokid SDK credentials, if needed for legacy code paths, must be supplied locally and are not included here

## TODO

- Make terminal font size user-scalable from the on-device UI, or reduce the default density for the Rokid display
- Use the display width and height more efficiently so shell content wastes less screen real estate
- Add an in-app SSH key generation/import flow instead of ad-hoc provisioning
- Add a better first-run settings experience for host, username, and auth mode
- Tighten the remaining legacy-module cleanup so the repo reflects the glasses-only architecture more clearly
