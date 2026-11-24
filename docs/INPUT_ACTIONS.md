# Presentations, scrolling, taps, and keyboard shortcuts

Presets map each physical button's **Single press** and **Hold** to Anki actions, MyWhoosh actions, or generic input actions supported on that platform. Generic input always runs locally on the device hosting ClickControl.

## Platform capability differences

| Action | Desktop | Android |
|---|---|---|
| **Scroll / Swipe (`SCREEN_SCROLL`)** | **Mouse wheel scroll** (Up, Down, Left, Right; `At current pointer` or fixed screen area; amount 1–20; optional **Repeat while held**) | **Touch swipe / scroll** (Next →, Prev ←, Scroll ↓, Scroll ↑ inside a screen area %; swipe distance 1–20; optional **Repeat while held**) |
| **Click / Tap (`SCREEN_CLICK`)** | **Screen click** (`At current pointer` or center of a percentage rectangle) | **Screen tap** (center of a percentage rectangle; pointer mode is hidden) |
| **Keyboard shortcut (`KEYBOARD_INPUT`)** | **Keyboard shortcut** (A–Z, 0–9, Space, Enter, Tab, Escape, Arrows, Page Up/Down, Home/End, Backspace, Delete, F1–F12 + Ctrl/Alt/Shift/Meta) | **Hidden / unsupported** (Android cannot dispatch arbitrary global keyboard shortcuts outside text editors; imported presets warn the user) |

## Configure an action

1. Open **Presets** and select an existing preset, start from a template (`Presentations`, `Document Scrolling`, etc.), or create a **Blank** preset.
2. Select a controller button and pick an action for **Single press** or **Hold**.
3. Configure the action parameters and save the preset:
   - **Repeat while held (Single press only)**: Available on **Scroll / Swipe** and **Click / Tap** (and Desktop scroll/click actions). When enabled on Single press, holding the button repeats smoothly at one in-flight action at a time (without queuing a backlog after you let go), cancelling immediately on release. Long press on that same button pauses automatically whenever Single press has `Repeat while held` enabled.
4. Start the bridge on **Control** and bring your presentation, PDF, or browser app to the front. In the preset editor you can use **Test in 3 seconds** to switch windows and verify a single action before saving.

## Screen click / Screen tap & Touch swipe areas

- Percentages (`Left`, `Top`, `Width`, `Height`) define a valid rectangle within 0–100% of the primary display (Desktop) or active full display (Android) with positive width and height.
- **Screen click / Screen tap** targets the center of that rectangle.
- **Touch swipe / scroll** (Android) swipes across the configured rectangle:
  - **Next →** swipes right-to-left (advancing slides/pages); **Prev ←** swipes left-to-right.
  - **Scroll ↓** swipes upward to scroll down through a document; **Scroll ↑** swipes downward to scroll up.
- On **Desktop**, leaving **At current pointer** enabled for **Mouse wheel scroll** or **Screen click** acts wherever the mouse cursor currently rests — useful for scrolling documents or clicking without moving your cursor to fixed coordinates.

## Keyboard shortcuts (Desktop only)

Desktop dispatches keyboard shortcuts to the currently focused window via AWT Robot (X11/macOS/Windows) or `ydotool` (Ubuntu Wayland). Supported keys include letters, digits, navigation keys (`Page Up`, `Page Down`, `Home`, `End`, arrows), editing keys (`Space`, `Enter`, `Tab`, `Escape`, `Backspace`, `Delete`), and function keys (`F1`–`F12`), plus `Ctrl`, `Alt`, `Shift`, and `Meta`.

On Android, `KEYBOARD_INPUT` is omitted from action pickers and rejected at runtime by `PlatformCapabilities`. Any preset imported from desktop containing `KEYBOARD_INPUT` shows a clear warning banner in Android **Presets** so you can replace those slots with **Touch swipe / scroll** or **Screen tap**.

## Saved presets and diagnostics

Presets store generic `InputConfig` payloads (`scrollDirection`, `scrollAmount`, `repeatWhileHeld`, `clickAtPointer`, screen area, and portable key/modifiers) per `(button, pressType)` using v3 serialization (while still reading v1 and v2 presets cleanly). Check **Settings -> Diagnostics** if a target app ignores injected events.
