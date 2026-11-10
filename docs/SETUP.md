# Setup

## Install and permissions

Download a suitable asset from [GitHub releases](https://github.com/thies2005/AnkiMywhooshControl/releases). Android requires API 29 or later; install the APK and grant the requested Bluetooth permissions. AnkiDroid accessibility and the optional touch fallback require enabling the ClickControl accessibility service. Floating status and touch calibration use accessibility overlays and require the accessibility service; there is no separate draw-over-other-apps permission in the manifest. Desktop releases are packaged for Linux, macOS and Windows; availability and platform behavior depend on the release. Linux needs Bluetooth enabled and access to BlueZ. See [Development](DEVELOPMENT.md) for local builds and the worker dependency.

## Controller ownership and presets

On the device running ClickControl, choose **Right controller only** to leave the left controller for the cycling app, or **Both controllers** to route both through ClickControl. Do not connect the same pod from Android and desktop simultaneously. If a side is not identifiable, pin its verified Bluetooth address in Settings. Pins identify hardware; preset bindings specify actions independently.

Choose or create a preset under **Presets**. Blank presets start without actions; standard presets include Anki on the right and bike actions on the left. Edit press and hold actions and save. Duplicating and renaming are available; deletion retains at least one preset. Existing per-mode settings are imported as editable presets. On Android, set the preset's Anki destination to AnkiDroid or Laptop Anki.

## Anki

Run Anki with AnkiConnect for desktop or laptop review. Use `127.0.0.1` and port `8765` when ClickControl runs on the same laptop. From Android, use the laptop's private LAN IPv4 address and port; both devices must reach each other, and AnkiConnect and the firewall must permit the connection. Its default loopback binding only accepts local clients. For local Android review, choose AnkiDroid and enable the accessibility service. Keep the target app ready for review. Desktop Control offers **Test Anki**; on Android, verify a saved Anki binding during review and inspect the diagnostic log if it fails.

## MyWhoosh

ClickControl advertises an OpenBikeControl TCP service as `_openbikecontrol._tcp` on port `21587`; the cycling app must support and connect to it. Android also offers a configured accessibility touch fallback. Desktop keyboard/mouse fallback depends on focus and desktop-session input support; background control is better served by OpenBikeControl. Actual interoperability and firmware behavior still require physical testing; see [Troubleshooting](TROUBLESHOOTING.md).

## Other applications

Use Screen click or Keyboard input actions in the preset editor to control other apps on the device running ClickControl. Configure the target area or key combination separately for Press and Hold. See [Input actions](INPUT_ACTIONS.md) for setup, testing and platform limits.
