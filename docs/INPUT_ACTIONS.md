# Screen clicks and keyboard input

A named preset can map each controller button's **Press** and **Hold** to an Anki action, a bike action, a **Screen click**, a **Keyboard input**, or no action. Generic input works on the device running ClickControl; it is not forwarded to the configured AnkiConnect host.

## Configure an action

1. Open **Presets** and select a preset or create a blank one.
2. Select the right or left controller and the physical button.
3. Open its Press or Hold action and choose Screen click or Keyboard input.
4. Configure the area or key combination, then save the preset. Each binding has its own configuration. Every trigger sends a single click or key combination; there is no repeat mode.
5. Start the bridge for controller input and put the target application in the foreground. The editor's delayed test lets you switch to the target before input is sent; cancel the countdown to stop the test.

## Screen click

On desktop you can choose a single click at the current pointer, without moving it. For a fixed target, define a rectangle using **Left**, **Top**, **Width**, and **Height**, as percentages of the screen. ClickControl clicks its centre. For example, Left 80%, Top 75%, Width 10%, Height 10% produces a click at 85% across and 80% down the screen.

The area must fit entirely on the screen and have positive width and height. Invalid or missing settings do not produce input. Use the current display orientation when measuring the area; rotation, window placement, keyboard visibility, scaling and application layout can move the target.

Android uses its current full display and requires the ClickControl accessibility service. Desktop uses the primary display, including its configured origin. The click goes to whichever visible application occupies that location. It does not locate a control by label or follow a background window.

## Keyboard input

Choose a key, optionally with Ctrl, Alt, Shift or Meta. Supported keys include letters, digits, Space, Enter, Tab, Escape, arrows, Backspace, Delete and F1–F12. This sends a key combination, not a saved text string. Meta corresponds to the platform's Meta/Command/Super key where supported. Keyboard layout and the receiving application determine the result.

Desktop sends input to the focused application through Java AWT Robot on X11/macOS/Windows, or through `ydotool` on Ubuntu Wayland. A graphical session and appropriate OS permissions are required. See [Wayland setup](WAYLAND.md) for prerequisites and fixed-area limitations. The app does not silently substitute bike controls for an unsupported input action.

Android keyboard input requires **Android 13 or newer**, the accessibility service, and a focused text editor with an active input connection. It cannot promise system-wide shortcuts or control arbitrary game windows. Older Android versions still support accessibility screen clicks. These constraints follow Android's [accessibility input-method API](https://developer.android.com/reference/android/accessibilityservice/InputMethod).

## Saved presets and feedback

Existing presets remain readable. Generic parameters are stored with the exact physical button and press type; duplicating a preset retains them. Switching a binding back to a standard action does not turn it into a screen click or keystroke. Check the diagnostic log when the target app does not respond; input submission is not proof that an application handled the event.
