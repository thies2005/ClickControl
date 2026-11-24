# ClickControl verification status

Local verification on 24 November 2026 recorded **7** `:core` tests, **68** `:app` debug unit tests and **120** `:desktop` unit tests with **0 failures, 0 errors and 0 skipped**. Android debug lint and APK build passed with JDK 17. The desktop distributable and Ubuntu `.deb` built with JDK 21, and the packaged launcher passed `--self-test`. Both Python suites passed, with **4** worker tests and **11** release-script tests.

See [Setup](docs/SETUP.md), [Development](docs/DEVELOPMENT.md), and [Troubleshooting](docs/TROUBLESHOOTING.md).

## Android emulator QA

- **Emulator walkthrough (23 November 2026)**: `emulator-5554` (`Pixel7_API35`), API `35`, package `com.ankiride.bridge`, build variant `debug`, commit `9f29464`, driven via Antigravity (`agent-device`, `device_screenshot`, and `adb -s emulator-5554`):
  - **Control**: Material 3 system layout, active preset card, connection badge, and zero desktop-only terms or legacy 5-mode selectors.
  - **Presets**: `New from template` menu lists `Presentations (Swipe)` (with Android swipe description), `Document Scrolling`, `AnkiDroid` (no `Anki Desktop`), `AnkiWeb (Browser)`, `Ride + Anki`, and `Blank`. The Touch swipe / scroll editor displays a 2×2 direction chip grid (`Next →`, `Prev ←`, `Scroll ↓`, `Scroll ↑`) without multi-line wrapping, rounded integer area percentages (`20`, `60`), `"Paused while Single Press repeats on hold"` under `Long Press (450ms)` when `Repeat while held` is active, and no `Keyboard shortcut` option in the action picker.
  - **Settings**: Displays the 3 shared controller connection options (`Right controller only`, `Both controllers`, `Any single controller (auto-connect)`), hides Android touch shift fallback and calibration when `MyWhoosh Cycling` is set to `On another device` and shows them when toggled to `On this device`, and exposes no Linux/Wayland/desktop-only settings.
  - **Persistence & stability**: Upgraded APK reinstall preserved existing preset/settings data (`Document Scrolling` active preset retained across `--relaunch`); `adb -s emulator-5554 logcat -d -s AndroidRuntime:E` recorded zero crashes.
- **24 November 2026 setup walkthrough**: Screenshots from Antigravity on `emulator-5554` (`Pixel7_API35`, API 35, `com.ankiride.bridge` debug, working tree based on `9f29464`) show the Option C single-controller setup, the Option B Wi-Fi setup, the disconnected-MyWhoosh warning with a link to Logs, and the saved default preset taking effect after relaunch. Codex confirmed the installed APK matched the local debug APK byte for byte and found no crash entries for ClickControl in the emulator crash buffer. A second live walkthrough was blocked by `DEVICE_IN_USE`.

## Generic input, BLE controller isolation & AnkiWeb bridges

- **Shared `:core` platform isolation**: Extracted shared contracts (`Contracts.kt`, `PresetStore.kt`, `PodSelectionPolicy.kt`) with `PlatformCapabilities` separating Desktop (Ubuntu GNOME Adwaita Dark + Yaru accent) and Android (Material 3 System UI), removing 15 duplicated files across `:app` and `:desktop` and adding v1/v2/v3 preset serialization/templates.
- **Hold-to-repeat & preset-switch safety**: Per-button repeat cancellation (`cancelPendingRepeats(button)` vs `cancelAllPending()`) in both desktop and Android `GenericInput.kt` ensures releasing a button cancels only held repeats (`isRepeat == true`) for that button without dropping initial taps (`isRepeat == false`) or other buttons' inputs; preset switches call `gestureEngine.reset()` and clear pending repeats. Android `computeSwipeSegment` clamps swipe segments strictly inside `[5%, 95%]` of the configured box.
- **Zwift `0x0B` 4-arrow pod & single-controller cap**: Added `DEVICE_ID_RIDE_LEFT = 0x0B` across `PodSelectionPolicy.kt`, `ZwiftBleController.kt`, `DesktopZwiftBleController.kt`, and `zwift_ble_worker.py` (protocol `CLICKCONTROL_BLE_PROTOCOL_3`), plus a strict single-pod cap in `PodAssignment.LEGACY_SHARED` ("Any single controller") so a second controller stays free for bike gears or ERG control.
- **AnkiWeb & UI bridges**: `AnkiWebAccessibilityBridge.kt` checks browser page markers (`containsAnkiWebMarker` / `isAnkiWebMarkerText` for `ankiweb.net`, `ankiuser.net`, `ankiweb`) instead of blind coordinate taps (`dispatchWebBarTap` removed); `DesktopAnkiDispatcher.kt` resets `AnkiWebKeyboardBridge` state on preset/target changes (preserving Space bounce guard and `1`–`4` grading); `ANKI_UNDO` is filtered when `AnkiTarget == ANKIWEB`; desktop UI adds `#C74312` `Start bridge` contrast, stopped-state `MyWhoosh OpenBikeControl` status text, and a cancellable 3s `Test Anki in 3s` browser-focus countdown for `ANKIWEB`.

## Ubuntu Wayland, Tray & Single-Instance verification

- Native GNOME Wayland (`ubuntu:GNOME`, `ydotool 1.0.4-3`, `/dev/uinput`) input checks:
  - Recorded single-click and pointer-click accuracy (`docs/test-results/wayland-native.json`, single-monitor GNOME with `accel-profile='flat'`).
  - Empirical 4-direction GTK4 `ScrolledWindow` wheel sign verification (`ydotool mousemove -w`: `0 3` scrolled down `200 -> 379.5`, `0 -3` scrolled up `379.5 -> 200`, `3 0` scrolled left `200 -> 0`, `-3 0` scrolled right `0 -> 213.4`) and packaged `ClickControl --live-input-test scroll down 3` delivery (`events: [(0.0, 3.0)]`, `vadj: 200.0 -> 477.78` with compositor `accel-profile='default'`). `WaylandInputTest` asserts the exact `ydotool mousemove -w` arguments including horizontal axis negation in `mouseWheelChecked`.
- **GNOME Wayland Tray & Single-Instance verification (24 November 2026, `scale_factor=2`)**:
  - Replaced Java/AWT `TrayIcon` (`JavaEmbeddedFrame count: 0` verified) on Linux with a D-Bus `org.kde.StatusNotifierItem` (`Id='clickcontrol'`) + `com.canonical.dbusmenu` helper (`linux_sni_tray.py`, wired via `DesktopTrayController.kt`) rendering transparent vector SVGs (`clickcontrol-idle.svg`, `clickcontrol-active.svg`, `clickcontrol-connected.svg`).
  - Verified retained `FileLock` in `DesktopInstance.kt`, close-to-tray hiding (`IsUnMapped` while keeping the BLE worker running), and all three reopen paths (`SNI Activate`, `DBusMenu` `Open ClickControl`, and secondary `ClickControl` launch wakeup -> `IsViewable` with a single running `ClickControl` PID).
- The refreshed tray icon uses a directional pad with transparent cutouts, a gray idle state, an amber scanning state and a green connection indicator. Codex verified the artwork at 16 px and checked the live GNOME tray registration and the idle and scanning pixmaps.
- Smoke tests against an extracted Ubuntu `.deb` confirmed that Option B accepted the OpenBikeControl device-status and AppInfo exchange with a test client and closed its listener when switched to Document Scrolling. Option C kept the listener closed and reopened the existing instance from the tray and from a second launch. Both profiles started the packaged BLE worker, wrote logs with mode `0600` and stopped without a spurious worker error.

## Blocked / still requiring real-environment testing

- **Physical BLE hardware**: Pairing, button delivery, sleep and reconnection with the intended controllers on Android and Linux BlueZ still need a hardware check.
- **Target applications**: End-to-end control against physical/signed-in AnkiConnect, AnkiDroid, MyWhoosh OpenBikeControl (including TCP fragmentation), and browser `ankiweb.net` review sessions.
- **Other environments**: Autostart across desktop logins and macOS and Windows builds and execution remain unverified.
