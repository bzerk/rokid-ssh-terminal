# Handover Guide (Move To Your Local Machine)

This document is the operational handoff for `rokid-ssh-terminal`.

## Current State

- Primary branch: `main`
- Public repo: `git@github.com:bzerk/rokid-ssh-terminal.git`
- Latest release: `v0.1.0-dev-build`
- Primary app ID: `com.bzerk.rokidterminal`
- Primary launcher activity: `com.clawsses.glasses.ControlSurfaceActivity`
- Glasses app module: `glasses-app`
- Build status: `./gradlew :glasses-app:assembleDebug` succeeds on this workspace

## What Was Recently Fixed

- Startup crash guard added for disconnected SSH state in:
  - `glasses-app/src/main/java/com/clawsses/glasses/terminal/SshTerminalManager.kt`
- Removed eager startup refresh call in:
  - `glasses-app/src/main/java/com/clawsses/glasses/ControlSurfaceActivity.kt`

These changes prevent the launcher flow from crashing when SSH is not connected yet.

## Local Machine Migration Checklist

1. Clone:
   - `git clone git@github.com:bzerk/rokid-ssh-terminal.git`
   - `cd rokid-ssh-terminal`
2. Verify toolchain:
   - Java 17
   - Android SDK platform `android-36`
   - Android build-tools `35+` (tested with `35.0.0`)
   - `adb` in `PATH`
3. Build:
   - `./gradlew :glasses-app:assembleDebug`
4. Plug in glasses and confirm:
   - `adb devices -l`
5. Install and launch:
   - `adb install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk`
   - `adb shell am start -n com.bzerk.rokidterminal/com.clawsses.glasses.ControlSurfaceActivity`

## If Glasses Are Not Local To Your Dev Box

Recommended: run `adb` where the glasses are physically attached, then SSH into that helper machine.

- Do not expose raw ADB (`5037`) to the public internet.
- Prefer Tailscale + SSH to the helper.
- Then run all commands on the helper host directly.

## Secrets and Security

Never commit:

- Private keys
- `local.properties` with real credentials
- Any `.secrets/` content
- Device-specific `SharedPreferences` dumps

Repository already ignores:

- `.secrets/`
- `dist/`
- `local.properties`

## Device Configuration Notes

The app stores SSH settings in:

- SharedPreferences file name: `rokid_terminal`

Important keys include:

- `host`
- `port`
- `username`
- `password`
- `private_key`
- `passphrase`
- `auto_attach`
- `fp:<host>:<port>:<username>` (trusted host fingerprint)

## Operational Commands (Quick Reference)

Build:

```bash
./gradlew :glasses-app:assembleDebug
```

Install:

```bash
adb install -r glasses-app/build/outputs/apk/debug/glasses-app-debug.apk
```

Launch control surface:

```bash
adb shell am start -n com.bzerk.rokidterminal/com.clawsses.glasses.ControlSurfaceActivity
```

Launch terminal activity:

```bash
adb shell am start -n com.bzerk.rokidterminal/com.clawsses.glasses.TerminalActivity
```

Crash scan:

```bash
adb logcat -d | rg "FATAL EXCEPTION|AndroidRuntime|com.bzerk.rokidterminal"
```

## Release Flow (Dev Build)

1. Build debug APK:
   - `./gradlew :glasses-app:assembleDebug`
2. Copy artifact to `dist/` with versioned name
3. Generate checksum:
   - `sha256sum <apk> > <apk>.sha256`
4. Create release:
   - `gh release create <tag> <apk> <apk>.sha256 --repo bzerk/rokid-ssh-terminal --title "<tag>" --notes "..."`

## Open Priorities

- Add user font scaling or smaller default terminal density
- Optimize terminal layout for better use of display area
- Add in-app SSH key generation/import flow
- Improve first-run onboarding for SSH configuration
- Continue removing dead legacy phone-path code from active product path

## Context Transfer Reality

There is no automatic transfer of live assistant memory between machines/sessions. Practical context transfer is:

- Git history (what changed and why)
- This handover doc + README
- Release artifacts and tags
- Optional exported notes from your local workflow

If you keep your next work on `main` with clean commit messages, context continuity stays strong even when switching machines.
