# Setup

## Install and permissions

Download an installer or APK from [GitHub releases](https://github.com/thies2005/AnkiMywhooshControl/releases), or build locally following [Development](DEVELOPMENT.md).

- **Android (API 29+)**: Install the APK and grant requested Bluetooth permissions when prompted.
  - Enable the **ClickControl accessibility service** in Android Settings when using **AnkiDroid**, **AnkiWeb (Browser)**, **Touch swipe / scroll**, **Screen tap**, or Android **MyWhoosh Touch shift fallback** (and optional Floating Status HUD).
- **Desktop (Linux, macOS, Windows)**: Needs Bluetooth enabled (BlueZ on Linux). For Wayland desktop clicks, keyboard shortcuts, and wheel scrolling on Ubuntu/GNOME, see [Wayland setup](WAYLAND.md). Linux also provides an autostart toggle in Settings when running on Linux.

## Controller connection mode & presets

In **Settings**, pick how ClickControl claims hardware:

1. **Right controller only**: Connects only the right Zwift Click controller, leaving the left controller for MyWhoosh/Zwift.
2. **Both controllers**: Connects both right and left controllers through ClickControl.
3. **Any single controller (auto-connect)**: Connects whichever single controller wakes first — handy for single-controller presentations or reading.

If your hardware advertisement does not identify left/right reliably, pin verified Bluetooth addresses in **Settings**. Never connect the same physical controller to Android and desktop simultaneously.

In **Presets**, choose an existing preset or use **New from template** to add a ready-made setup:

- **Presentations** (Desktop: arrow keys + `F5` + `B`) / **Presentations (Swipe)** (Android: left/right touch swipes)
- **Document Scrolling** (Desktop: coalesced wheel scroll up/down + Page Down/Up; Android: coalesced vertical touch scroll up/down + next/previous swipes)
- **Anki Desktop** (Desktop) or **AnkiDroid** (Android)
- **AnkiWeb (Browser)** (Works on Desktop browsers via keyboard input and Android browsers via Accessibility)
- **Ride + Anki** (Right controller Anki, left controller MyWhoosh)
- **Blank**

Unsaved preset edits prompt before switching presets so changes aren't lost accidentally.

## Presentations and document scrolling

1. Create a **Presentations** or **Document Scrolling** preset (or customize any button's **Single press** or **Hold**).
2. For smooth document scrolling, turn on **Repeat while held** on **Single press** (`Mouse wheel scroll` on Desktop or `Touch swipe / scroll` on Android). Scroll repeats while you hold the button and stops immediately on release; Long Press pauses automatically for any button configured with `Repeat while held`.
3. Focus your presentation app, slide deck, PDF reader, or document window and press/hold your controller button. See [Input actions](INPUT_ACTIONS.md).

## Anki (Desktop, AnkiDroid, and AnkiWeb)

- **Anki Desktop (via AnkiConnect)**: Run Anki with AnkiConnect installed.
  - On the same computer as desktop ClickControl: leave host at `127.0.0.1` and port `8765`.
  - From Android (**Desktop Anki (LAN)**): enter your computer's private LAN IPv4 address and port in Settings, and configure AnkiConnect and your firewall to allow connections from the LAN. (Loopback `127.0.0.1` only accepts local connections.)
- **AnkiDroid (Android)**: Select **AnkiDroid** as the preset's Anki target and enable the ClickControl accessibility service.
- **AnkiWeb (Browser — Desktop & Android)**:
  - Select **AnkiWeb (Browser)** as the preset's Anki target.
  - On Desktop, keep `ankiweb.net` focused in your browser (`Space` reveals/grades default or `1`–`4` grade cards; Undo is unavailable on AnkiWeb).
  - On Android, enable the ClickControl accessibility service and open `ankiweb.net` in your mobile browser (such as Chrome or Firefox). Note that **Undo** is unavailable on AnkiWeb, and the accessibility listener never reads password fields or logs background events.

## MyWhoosh

ClickControl advertises an OpenBikeControl TCP service (`_openbikecontrol._tcp` on port `21587`) so MyWhoosh can connect over the network for shifting, steering, camera, HUD, and emotes.

- On **Android Settings**, choose where MyWhoosh is running:
  - **On another device (PC / iPad / Apple TV)**: ClickControl uses OpenBikeControl over LAN; Android touch shift calibration options are hidden automatically.
  - **On this Android device**: Enables the optional **Touch shift fallback** and screen calibration controls alongside OpenBikeControl.
- On **Desktop**, background network control uses OpenBikeControl; focused-window keyboard shortcuts remain available under `Keyboard fallback` in Settings when needed.

Physical hardware and live target-app behavior still require real-device verification; see [Troubleshooting](TROUBLESHOOTING.md) and [AUDIT.md](../AUDIT.md).
