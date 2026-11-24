<h1 align="center">ClickControl</h1>

<p align="center"><strong>One controller. Your workflow.</strong><br>Route Zwift Click buttons to presentations, documents, Anki, and cycling apps with clear Desktop and Android experiences.</p>

<p align="center">
  <a href="#get-started">Get started</a> ·
  <a href="docs/SETUP.md">Setup</a> ·
  <a href="docs/DEVELOPMENT.md">Build and contribute</a> ·
  <a href="https://github.com/thies2005/AnkiMywhooshControl/releases">Releases</a>
</p>

ClickControl routes Zwift Click controller buttons to **Presentations**, **Document Scrolling**, **Anki everywhere** (Anki Desktop, AnkiDroid, and AnkiWeb in your browser), and **MyWhoosh**. It provides a pure-Kotlin shared core (`:core`) paired with strictly platform-appropriate Desktop and Android apps. Android requires Android 10 (API 29) or later; desktop packages target Linux, macOS, and Windows. Local validation in [AUDIT.md](AUDIT.md) covers unit tests, Wayland input probes, and an Android emulator; physical BLE controllers and target apps still require hardware testing.

## Platform separation at a glance

ClickControl never exposes options that do not belong on your device:

| Platform | Design & UI | Supported actions & targets |
|---|---|---|
| **Desktop** | Ubuntu GNOME Adwaita Dark + Yaru accent | Keyboard shortcuts, mouse wheel scroll (`Repeat while held`), screen clicks (current pointer or fixed area), **Anki Desktop** (AnkiConnect) & **AnkiWeb** (browser), **MyWhoosh** (OpenBikeControl). Linux autostart shown only on Linux. |
| **Android** | Professional Material 3 System UI | Touch swipe / scroll (`Repeat while held`), screen taps, **AnkiDroid**, **AnkiWeb** (mobile browser), **Desktop Anki** (LAN IPv4), **MyWhoosh** (`On this Android device` or `On another device`). Keyboard shortcuts and desktop-only actions are hidden. |

## What you can do

| Workflow | Details |
|---|---|
| **Presentations** | Desktop starter maps Next slide (`Right`), Previous slide (`Left`), Start slideshow (`F5`), and Blank screen (`B`). Android starter (`Presentations (Swipe)`) maps sideways touch swipes for slides and PDF viewers. |
| **Document Scrolling** | Smooth **Repeat while held** wheel scrolling (Desktop) or touch scrolling (Android) up/down, plus page/tap navigation. |
| **Anki everywhere** | Review flashcards on **Anki Desktop** (via AnkiConnect), **AnkiDroid** (Android), or **AnkiWeb** directly in desktop and Android browsers. |
| **Control your ride** | Combine right-controller Anki review with left-controller MyWhoosh shifting, steering, camera, and emotes via OpenBikeControl. |

## A look inside

Existing Android emulator capture and design notes are tracked alongside current verification in [AUDIT.md](AUDIT.md). Hardware controllers and target apps still require real-device testing.

<table>
  <tr>
    <td align="center" width="100%"><img src="docs/screenshots/android-presets.png" width="220" alt="ClickControl Android preset editor with per-button actions"><br><strong>Your mappings</strong></td>
  </tr>
</table>

<details>
<summary>Desktop Control screen</summary>

<img src="docs/images/desktop-control.png" width="720" alt="ClickControl desktop Control screen">

</details>

## Get started

1. Install a package from [GitHub releases](https://github.com/thies2005/AnkiMywhooshControl/releases), or [build from source](docs/DEVELOPMENT.md). Enable Bluetooth and wake the controllers.
2. In **Settings**, choose one of three controller connection modes on your active device:
   - **Right controller only**: ClickControl connects to the right controller, leaving the left controller free for your cycling app.
   - **Both controllers**: ClickControl owns both left and right controllers (ideal for **Ride + Anki**).
   - **Any single controller (auto-connect)**: Connects to whichever single controller wakes up first (useful if you use one Click unit for presentations or reading).
   Pin a verified Bluetooth address in **Settings** if your hardware does not advertise its side clearly. Never connect the same controller to two devices at once.
3. In **Presets**, pick a starter template from **New from template**:
   - **Presentations** / **Presentations (Swipe)**
   - **Document Scrolling**
   - **Anki Desktop** (Desktop) / **AnkiDroid** (Android)
   - **AnkiWeb (Browser)**
   - **Ride + Anki**
   - **Blank**
4. For **Anki Desktop**: run Anki with AnkiConnect (`127.0.0.1:8765` locally, or your laptop's private LAN IPv4 address when using **Desktop Anki (LAN)** from Android). For **AnkiWeb (Browser)**: keep `ankiweb.net` active in your browser on desktop, or enable the ClickControl accessibility service on Android. Note that **Undo** is unavailable on AnkiWeb.
5. Open **Control**, verify your active preset and target, and connect.

For detailed walkthroughs see [Setup](docs/SETUP.md), [Input actions](docs/INPUT_ACTIONS.md), and [Wayland setup](docs/WAYLAND.md); for builds and tests see [Development](docs/DEVELOPMENT.md).

## Documentation and support

See the [documentation index](docs/README.md), [troubleshooting guide](docs/TROUBLESHOOTING.md), [design guide](DESIGN.md), and [validation notes](AUDIT.md).

## License

ClickControl is licensed under **GPL-3.0-or-later**. See [LICENSE](LICENSE). Preserve the included third-party notices.
