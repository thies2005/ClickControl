# Troubleshooting

- **No controller or wrong side:** Wake the controller, enable Bluetooth, and check OS permissions. Ensure another app or device is not connected to the same pod. Use **Any single controller (auto-connect)** in Settings if you only use one unit and don't care about side, or pin a verified Bluetooth address if your pod does not advertise its side clearly. Firmware-specific advertisements and sleep behavior remain to be tested on hardware.
- **AnkiConnect (Desktop / LAN) cannot be reached:** Confirm Anki is open with AnkiConnect enabled. Use `127.0.0.1:8765` when ClickControl runs on the same laptop. From Android (**Desktop Anki (LAN)**), use the computer's private LAN IPv4 address, permit LAN access in AnkiConnect settings, and open port `8765` in your firewall.
- **AnkiWeb (Browser) does not flip or grade:**
  - **Desktop**: Make sure the browser tab open to `ankiweb.net` is currently focused. Undo is not supported by AnkiWeb and logs a clear notice instead of guessing.
  - **Android**: Ensure the **ClickControl accessibility service** is enabled in Android Settings, wait for the card or answer buttons (`Again` / `Hard` / `Good` / `Easy` or `Show Answer`) to finish rendering on `ankiweb.net`, and retry.
- **Document scroll moves the wrong way or doesn't repeat:**
  - Make sure **Repeat while held** is enabled on **Single press** (not Hold) for continuous scrolling; holding then pauses Long Press on that button and cancels immediately on release.
  - On Desktop Wayland, **At current pointer** works with your cursor inside the document/reader window even without GNOME `flat` mouse acceleration; fixed-area clicks/scrolls require single-monitor GNOME with `flat` acceleration profile and `ydotool` + `python3-gi` + `gir1.2-gtk-4.0`.
- **Android preset warns about imported keyboard shortcuts:** ClickControl keeps Android strictly free of unsupported desktop shortcuts. Replace any legacy/imported **Keyboard shortcut** binding in **Presets** with **Touch swipe / scroll** (use `Next →` / `Prev ←` for presentations, or `Scroll ↓` / `Scroll ↑` for documents) or **Screen tap**.
- **MyWhoosh does not respond:** Check that MyWhoosh connects to OpenBikeControl (`_openbikecontrol._tcp`, port `21587`). On Android, verify **MyWhoosh location** in Settings: choose **On this Android device** to access Touch shift fallback and calibration, or **On another device** when MyWhoosh runs on a PC/iPad/Apple TV.
- **Desktop Bluetooth worker fails:** Check Bluetooth/BlueZ access on Linux and Diagnostics in Settings. Without a bundled worker, Python 3 `venv` + Bleak (plus `dbus-fast` on Linux) is needed.
- **Android module missing during Gradle build:** Configure SDK 35 via `ANDROID_HOME` or `local.properties`, and do not pass `-PskipAndroid=true` when invoking `:app:*` tasks.

See [AUDIT.md](../AUDIT.md) for verified checks, live probes, and remaining hardware-testing limits.
