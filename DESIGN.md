# ClickControl design language

ClickControl adapts to the user's device instead of forcing one UI onto two fundamentally different operating systems. Desktop and Android share the `:core` domain model, preset engine, starter templates, and 3 controller connection modes, while presenting native platform chrome and isolating platform-only features via `PlatformCapabilities`.

## Platform separation rules

- **Never expose unsupported capabilities**:
  - Desktop never displays **AnkiDroid**, Android accessibility prompts, touch shift calibration, or "Android app / phone screen" copy. Linux autostart settings only appear on Linux.
  - Android never displays **Keyboard shortcut**, current-pointer targeting, Wayland/ydotool settings, or desktop presentation key combos.
  - Legacy 5-mode (`Anki + MyWhoosh` / `Split-Screen` / etc.) selectors are removed from the UI and HUD; every workflow is a named **Preset**. Old setups migrate into editable presets on upgrade.

## Visual systems

### Desktop (Ubuntu / GNOME Adwaita Dark + Yaru accent)

Desktop (`desktop/`) uses GNOME/libadwaita-inspired layout primitives and Ubuntu Yaru accenting:

| Role | Color | Use |
| --- | --- | --- |
| Window canvas | `#1E1E1E` | Main Adwaita dark content background |
| Sidebar surface | `#2B2B2B` | Persistent navigation rail and compact header |
| Preference card (`AdwPreferencesGroup`) | `#303030` | Grouped settings, connection cards, and preset groups |
| Elevated control | `#383838` | Inputs, interactive rows, and floating HUD surface |
| Primary accent | `#E95420` | Ubuntu Yaru orange for primary actions and active states |

- Structure: split sidebar + scrollable `AdwPreferencesGroup` / `AdwActionRow` cards on wide windows, adapting smoothly to compact windows.
- Floating HUD window: compact pill showing BLE connection status and active preset name.

### Android (Material 3 System UI)

Android (`app/`) follows Material 3 system guidelines with a clean preference-group hierarchy:

- Components: `AndroidSystemSection` cards, bottom navigation (**Control**, **Presets**, **Settings**), 48 dp minimum touch targets, system bar insets, and an optional compact floating HUD pill (`FloatingHudPill`, off by default).
- Preset controls: swipe directions are arranged in a balanced 2×2 grid (`Next →` / `Prev ←`, `Scroll ↓` / `Scroll ↑`) with single-line labels so nothing wraps awkwardly at phone widths, and screen percentages format cleanly as rounded integers/tenths.

## Information architecture

- **Control**: BLE connection state, active preset name, platform-valid Anki destination, Connect/Disconnect primary action, and a clean list of physical controller bindings (`Single press` + `Hold`). Connectivity is shown as a calm status badge rather than an alarm unless user action is needed.
- **Presets**: named library with **New from template** (`Presentations` / `Presentations (Swipe)`, `Document Scrolling`, `Anki Desktop` or `AnkiDroid`, `AnkiWeb (Browser)`, `Ride + Anki`, `Blank`), Rename, Duplicate, Delete (always retaining at least one preset), explicit Save/Reset, and unsaved-change protection.
  - Press and Hold are edited independently per button. When Single Press enables **Repeat while held**, Hold displays a clear note that Long Press pauses while Single Press repeats on hold.
- **Settings**: 3 controller connection modes (**Right controller only**, **Both controllers**, **Any single controller (auto-connect)**), optional hardware address pinning, Anki connection parameters, Android MyWhoosh location (**On this Android device** vs. **On another device**), feedback toggles, and diagnostics.

## Design goals and verification status

Screenshots on Android emulator and live Wayland desktop behavior establish layout clarity and input delivery for covered scenarios; see [AUDIT.md](AUDIT.md) for exact observed coverage, unit-test counts, and hardware scenarios that still require physical validation.
