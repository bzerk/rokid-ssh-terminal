# Rokid Glasses Touchpad — Hardware Investigation

Investigated via ADB on 2026-05-22.

## Hardware Identity

| Property | Value |
|---|---|
| Kernel name | `ROKID,PSOC-TP-R` |
| Chip | Cypress PSoC 4000R |
| Bus | I2C bus 1, address `0x08` |
| I2C controller | `a90000.i2c` (Qualcomm GPI-DMA backed) |
| Driver | `/vendor/lib/modules/psoc_ts_drv_right.ko` |
| Sysfs path | `/sys/bus/i2c/devices/1-0008/` |
| Input device | `/dev/input/event1` |
| IRQ GPIO | 32 |
| Reset GPIO | 33 |

The PSoC is a **microcontroller**, not just a sensor. It runs Cypress-signed firmware that can be reflashed over UART (`uart_en` attribute + `CyBtldr_FromHex`/`CyBtldr_VerifyRow` bootloader in the driver).

## What It Reports

The touchpad does **not** report XY position. It's a 5-way D-pad controller that emits key events:

```
KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, KEY_ENTER
KEY_BACK, KEY_PROG1, KEY_PROG2, KEY_PROG3
KEY_DASHBOARD, KEY_F13, KEY_F14
```

Swipe gestures are interpreted inside the PSoC firmware and emitted as discrete key events.

Android InputManager classifies it as: `KEYBOARD | ALPHAKEY | DPAD | VIRTUAL`

## Other Devices on the Same I2C Bus (bus 1 / a90000.i2c)

| Address | Driver | Purpose |
|---|---|---|
| `1-0008` | `psoc_ts_drv_right` | Touchpad (this device) |
| `1-0030` | `aw2110x` (Awinic) | LED controller |
| `1-003f` | `mp2724-charger` | USB power/charger IC |
| `1-0064` | `cw221x` (Cellwise) | Battery fuel gauge |

The touchpad PSOC is a **supplier** to the MP2724 charger and to `spi0.0` (an `rt600` Realtek audio DSP). Meaning the charger and audio DSP have a kernel device-link dependency on the PSOC being initialised first.

## Sysfs Control Attributes

| Attribute | Current Value | Meaning |
|---|---|---|
| `pa_en` | 0 | BT Power Amplifier coexistence (1 = BT PA active, PSOC backs off) |
| `touch_evt_disable` | 0 | Kill switch — calls `disable_irq()` when set to 1 |
| `low_power` | 1 | Low-power scanning mode (active now) |
| `uart_en` | 0 | UART enable for firmware flashing |
| `deep_sleep` | 0 | Deep sleep mode |
| `enforce_hall` | 0 | Force hall/proximity sensor behaviour |
| `enforce_psensor` | 0 | Force proximity sensor |
| `two_finger_click_en` | 0 | Two-finger click gesture |
| `two_finger_flick_en` | 0 | Two-finger flick gesture |

## Proximity / Extcon

The PSOC exposes an extcon (external connector) as `extcon3`:

```
DOCK=1   ← proximity sensor detects something close (glasses on face/surface)
JIG=0
```

`DOCK=1` currently active — the glasses are resting near something. This is used for wear-detection and power management.

Hall sensor (`/sys/bus/i2c/devices/1-0008/hall`) currently reads `1`.

## Bluetooth Chip

| Property | Value |
|---|---|
| Driver | `kiwi_v2` (Qualcomm WCN6750) |
| Protocol | UART QCA over `ttyHS0` (`a84000.qcom,qup_uart`) |
| rfkill | `rfkill0`, type=bluetooth |
| Current state | OFF (rfkill state=0) |

## Why the Touchpad Stops Working with Certain BT Devices

Three distinct mechanisms, any of which can be the cause:

### 1. RF Coexistence via `pa_en` (hardware level)

When the WCN6750 BT chip transmits at 2.4GHz, it asserts a coexistence signal that sets `pa_en=1` on the PSOC (`Set state 0x8` visible in dmesg). The PSOC reduces its capacitive scan rate to avoid false triggers from RF interference.

For short-burst BT traffic this is fine — the PA toggles quickly. For **continuous-streaming profiles** (A2DP audio, HFP calls), the PA stays active almost constantly, keeping the PSOC in "back off" mode where it stops reporting events reliably.

**Affected device types**: BT audio devices (headphones, earbuds, speakers), BT headsets on a call.

### 2. Android InputManager Priority (software level)

Android routes D-pad navigation to whichever input device it considers highest priority. When a **BT HID device** (keyboard, mouse, gamepad) is connected, Android may prefer it over the built-in PSOC (which is classified `DPAD`), making the touchpad appear dead even though it's still generating kernel events.

There are 100+ vendor-specific `.kl` keylayout files in `/system/usr/keylayout/` — if the paired device matches one, it gets even more specific handling.

**Affected device types**: BT keyboards, mice, gamepads, any BT HID class device.

### 3. `touch_evt_disable` kill switch (firmware/init level)

The PSOC driver has a `touch_evt_disable` sysfs attribute. When written `1`, the driver calls `disable_irq()`, completely halting all touchpad events at the kernel interrupt level. This is the most likely cause of the touchpad appearing **completely dead** (as opposed to just unreliable).

Something in `/vendor/etc/init/hw/init.rokid.rc` (root-only, could not read) or the BT HAL may write `1` to this when certain BT profiles activate.

## Diagnosing a Dead Touchpad

Run this immediately after pairing the problematic BT device:

```bash
adb shell cat /sys/bus/i2c/devices/1-0008/touch_evt_disable
# 0 = IRQ active, 1 = IRQ killed
adb shell cat /sys/bus/i2c/devices/1-0008/pa_en
# 0 = normal, 1 = BT PA coexistence mode active
adb shell getevent /dev/input/event1
# shows raw key events from the touchpad — if silent but pa_en=0, check touch_evt_disable
```

## Temporary Fix

If `touch_evt_disable` is 1 after pairing:

```bash
adb shell "echo 0 > /sys/bus/i2c/devices/1-0008/touch_evt_disable"
```

This re-enables the IRQ without a reboot. Does not survive reboot or BT reconnect.

## ADB Commands Used

```bash
adb shell getevent -il                                    # list input devices + capabilities
adb shell cat /proc/bus/input/devices                     # kernel input device registry
adb shell ls /sys/bus/i2c/devices/                        # I2C bus devices
adb shell cat /sys/bus/i2c/devices/1-0008/uevent          # PSOC driver/compatible info
adb shell dmesg | grep -i psoc                            # kernel driver boot messages
adb shell dumpsys input                                   # Android InputManager state
adb shell strings /vendor/lib/modules/psoc_ts_drv_right.ko  # driver binary strings
```
