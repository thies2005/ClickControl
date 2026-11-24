# Ubuntu Wayland input

On Wayland sessions (`XDG_SESSION_TYPE=wayland`), Desktop ClickControl uses a `ydotool` / `ydotoold` backend instead of Java AWT Robot for keyboard shortcuts, screen clicks, and mouse wheel scrolling. Single-press actions can trigger once or use opt-in **Repeat while held** (coalesced one-in-flight queue, cancelled immediately on button release).

## Requirements

Ubuntu/GNOME needs `ydotool` and `ydotoold` on `PATH` plus write access to `/dev/uinput` (or an existing `YDOTOOL_SOCKET`):

```bash
command -v ydotool ydotoold
test -w /dev/uinput && echo 'Input access available'
```

ClickControl never runs `sudo`, changes device permissions, or alters mouse settings. If `YDOTOOL_SOCKET` is unset, ClickControl launches a private `ydotoold` instance inside a `0700` runtime directory with a `0600` socket and terminates it on exit. An existing configured `YDOTOOL_SOCKET` is reused and left running.

For **fixed screen-area clicks or fixed-area scrolls** on GNOME Wayland, Python GTK4 introspection is also used to query logical monitor geometry:

```bash
sudo apt install ydotool python3-gi gir1.2-gtk-4.0
```

## Scrolling, pointer clicks, and keyboard shortcuts

- **Keyboard shortcuts & AnkiWeb desktop keys**: Use `ydotool key` with explicit Linux evdev keycodes and do not require GNOME `flat` mouse acceleration or single-monitor geometry.
- **Mouse wheel scroll (`At current pointer`)**: Uses `ydotool mousemove -w -- <dx> <dy>` at your current pointer location and does not require a `flat` mouse acceleration profile. Because `ydotool mousemove -w` negates horizontal relative wheel axes (`+X` scrolls left, `-X` scrolls right) while `+Y` scrolls down and `-Y` scrolls up, `WaylandInputBackend` negates `horizontal` so **Right** scrolls right and **Left** scrolls left consistently across Robot and Wayland backends.
- **Fixed-area clicks / fixed-area scrolls**: Move the pointer using native GDK Wayland logical display bounds and GNOME's mouse speed setting. These require a single-monitor GNOME session with `org.gnome.desktop.peripherals.mouse accel-profile` set to `'flat'`. Unsupported layouts or non-flat profiles report a clear error instead of guessing coordinates.

Use **Test in 3 seconds** in the Preset editor to focus a target window and test a single input before saving.

## Integration tests

The packaged desktop binary provides `--live-input-test` (waits 3 seconds so you can focus a test window):

```bash
ClickControl --live-input-test key A
ClickControl --live-input-test click 0.15 0.15 0.1 0.1
ClickControl --live-input-test pointer
ClickControl --live-input-test scroll down 3
```

`--self-test` remains the headless smoke test for parser, gesture engine, routing, presets, and local server startup. See [AUDIT.md](../AUDIT.md) and [recorded native click result](test-results/wayland-native.json) for observed GNOME Wayland measurements.
