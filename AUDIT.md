# ClickControl verification status

Local verification on 3 November 2026 passed `./verify.sh`; the test XML reports record 67 Android debug unit tests and 119 desktop unit tests with zero failures or errors. These are unit-test results, not evidence of real Bluetooth or target-application operation. The Android release test report is a separate run and should not be added to the debug count. The Python worker policy test and packaged Linux `--self-test` passed locally; the self-test exercises parser, gestures, settings, routing and local server startup.

An Android emulator walkthrough covered a blank preset, a binding edit, unsaved-draft confirmation and recreation, save and restart persistence. Linux wide and compact overview screenshots were inspected, but desktop editor interactions were not fully exercised by native pointer automation. These observations do not establish accessibility compliance.

Still requiring physical or target-environment testing: controller side advertisements, sleep and reconnection behavior, real AnkiConnect and MyWhoosh interoperability, Linux tray/autostart behavior across sessions, macOS and Windows operation, and complete keyboard/accessibility coverage. MyWhoosh TCP app-info parsing assumes a complete message per read, so fragmentation and protocol negotiation need an integration test. AnkiConnect's local answer-visibility state may become out of sync if controller review is mixed with mouse or keyboard review.

See [Setup](docs/SETUP.md), [Development](docs/DEVELOPMENT.md), and [Troubleshooting](docs/TROUBLESHOOTING.md).

## Generic input actions

Screen-click and keyboard actions store independent parameters for each preset/button/press. Tests cover v1/v2 preset loading, invalid input, routing isolation, portable desktop key mappings, release cleanup and pending-input invalidation on stop. `./verify.sh` and the rebuilt Linux packaged `--self-test` pass.

On an Android API 35 emulator with accessibility enabled, the editor’s delayed test clicked the configured screen rectangle in the system Settings app and opened Search. A separate configured key action sent A into that focused search field. These tests used the production input dispatcher and synthetic preset configuration, not a physical controller. Other target applications remain environment-dependent; native Ubuntu Wayland input was subsequently checked as described below. See [Input actions](docs/INPUT_ACTIONS.md).

## Ubuntu Wayland single-click verification

Native GNOME Wayland testing passed using the production input backend against a GTK4 window (`GdkWaylandDisplay`). Both an existing configured daemon and the app-started private daemon delivered keyboard input, fixed-area clicks, and a click at the current pointer. With a 1536×960 logical display, the app-started backend clicked at (307.102, 191.102) for target (307, 191), and (1227.668, 766.641) for target (1228, 767). The following pointer click stayed at the same position. See the [recorded result](docs/test-results/wayland-native.json).

The implementation accounts for GNOME's flat-profile mouse-speed gain and native logical screen geometry. The test used existing input-device permissions without changing mouse settings. Fixed-area testing covers this single-monitor GNOME configuration; other compositors and display layouts are not established as working. There is no repeating-click mode. Physical controller-to-desktop interoperability remains covered by the earlier BLE/unit-test limits.
