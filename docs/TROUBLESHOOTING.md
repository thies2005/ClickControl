# Troubleshooting

- **No controller or wrong side:** wake the pod, enable Bluetooth and check permissions. Ensure another app or device is not connected to the same pod. If the advertisement does not identify its side, pin a verified address in Settings; ClickControl does not guess that an unknown pod is right. Firmware-specific advertisements and sleep behavior remain to be tested on hardware.
- **AnkiConnect cannot be reached:** check that Anki and AnkiConnect are running, and use `127.0.0.1:8765` on the same laptop. From Android use the laptop's LAN address, allow connections in AnkiConnect, and check network and firewall settings. A loopback-only service cannot be reached from another device.
- **Unexpected card state:** AnkiConnect keeps local answer-visibility state. Mixing mouse or keyboard review with controller review can desynchronize it; a recovery flip may be needed.
- **MyWhoosh does not respond:** check that the app supports and connects to OpenBikeControl on the same network. The desktop input fallback needs the appropriate focused window and may depend on the desktop session, especially on Wayland. Android touch fallback requires its accessibility service and calibration. The TCP app-info parser assumes a complete message per read; fragmented real traffic and protocol negotiation need integration testing.
- **Desktop Bluetooth worker fails:** check Bluetooth/BlueZ access on Linux and the diagnostic log. If no compatible bundled worker exists, the app needs Python 3, `venv`, network access and the dependencies described in [Development](DEVELOPMENT.md).
- **Android project missing from Gradle:** configure a valid Android SDK path with a `platforms` directory and install SDK 35; `-PskipAndroid=true` intentionally excludes Android.

See [AUDIT.md](../AUDIT.md) for what was and was not validated. Unit tests and packaged self-tests do not establish live controller, Anki or MyWhoosh interoperability.

For generic screen-click or key actions, verify accessibility on Android, the focused target/editor, and the configured full-screen area. Android keyboard input requires API 33+; Wayland desktop input requires its configured backend and permissions; see [Wayland setup](WAYLAND.md). See [Input actions](INPUT_ACTIONS.md).
