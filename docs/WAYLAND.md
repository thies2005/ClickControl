# Ubuntu Wayland input

ClickControl uses a Linux `ydotool` backend on Wayland instead of Java AWT Robot. Each controller action sends one key combination or one click. There is no repeating-click mode.

## Requirements

Ubuntu/GNOME needs `ydotool` and `ydotoold` available on PATH, and the user must already have write access to `/dev/uinput`, or access to an existing daemon socket. Check with:

```bash
command -v ydotool ydotoold
test -w /dev/uinput && echo 'Input access available'
```

The app never runs sudo, changes device permissions, or changes mouse settings. If access is missing, have the system administrator configure it. When no socket is explicitly configured, ClickControl can start a private daemon using a 0700 runtime directory and a 0600 socket, then stop that owned daemon on application exit. An existing daemon supplied with `YDOTOOL_SOCKET` is reused and is not terminated by ClickControl. An invalid explicitly configured socket produces an error instead of silently changing backends.

For fixed screen areas on GNOME, also install Python's GTK4 introspection support. On Ubuntu the relevant packages are:

```bash
sudo apt install ydotool python3-gi gir1.2-gtk-4.0
```

## Single clicks

In a preset, choose **Screen click** for a button's Press or Hold. Desktop supports clicking at the current pointer without moving it, or clicking the centre of a configured screen area. Put the target application in front before using the controller.

Fixed-area Wayland clicks currently support a single GNOME monitor with a flat mouse acceleration profile. ClickControl reads native Wayland logical geometry and compensates for GNOME's mouse-speed setting. It does not use XWayland's AWT dimensions, which can differ under fractional scaling. Unknown desktop gain, unsupported layouts, or unavailable prerequisites are reported instead of using an unverified coordinate transform.

Keyboard input and current-pointer clicks do not need a fixed-area coordinate transform. Delivery still depends on the focused application and system input permissions. Other compositors and display layouts need separate validation.

Use the editor's **Test in 3 seconds** control to switch to a test window before input is sent. It sends a single action and can be cancelled during the countdown. Stop bridge cancels pending controller input; an event already delivered to the OS cannot be recalled.

## Integration test

The packaged executable has an explicit live-input test hook for manual verification. These commands send real input after a three-second delay, so focus a disposable target window first:

```bash
ClickControl --live-input-test key A
ClickControl --live-input-test click 0.15 0.15 0.1 0.1
ClickControl --live-input-test pointer
```

The click rectangle uses fractions, so the example clicks at 20% across and 20% down. `--self-test` remains the non-interactive smoke test. Live input verification is separate from unit tests.

## Verified local result

Native GTK4 testing on Ubuntu GNOME Wayland passed for keyboard A, two fixed-area clicks and a current-pointer click. The app-started private daemon was tested, and fixed targets were within 0.4 logical pixels at the existing fractional display scale and mouse speed. See [the recorded result](test-results/wayland-native.json) and [verification status](../AUDIT.md). Each trigger produces one click; no repeating mode is present.
