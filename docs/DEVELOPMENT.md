# Development

## Requirements and commands

Use JDK 17 (Android) or JDK 21 (Desktop CI) and the Gradle wrapper. Android builds require SDK platform 35 and build tools configured via `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`. Without an SDK (or when `-PskipAndroid=true` is passed), `settings.gradle.kts` excludes `:app` while keeping `:core` and `:desktop` active. Do not pass `-PskipAndroid=true` when running `:app:*` tasks.

```bash
./verify.sh
./gradlew :app:lintDebug :app:assembleDebug
./gradlew :desktop:test createDistributable packageDistributionForCurrentOS -PskipAndroid=true
python3 -m unittest discover -s desktop/src/test/python -v
python3 -m unittest discover -s scripts/tests -v
```

The debug APK is written to `app/build/outputs/apk/debug/`. Compose native app bundles and installers are under `desktop/build/compose/binaries/main/`. A packaged executable supports `--self-test` for headless validation of BLE packet parsing, gestures, routing, persistence, and local server startup.

### Standalone Python BLE worker packaging

Desktop BLE is delegated to `desktop/src/main/resources/ble/zwift_ble_worker.py` (Bleak). Release CI builds a standalone PyInstaller worker with Python 3.11 and places it in `desktop/build/packaged-resources/common/` before packaging. To build it locally (`bleak`, `pyinstaller`, and `dbus-fast` on Linux):

```bash
python -m PyInstaller --clean --noconfirm --onefile --name zwift_ble_worker desktop/src/main/resources/ble/zwift_ble_worker.py
mkdir -p desktop/build/packaged-resources/common
install -m 755 dist/zwift_ble_worker desktop/build/packaged-resources/common/zwift_ble_worker
./gradlew createDistributable packageDistributionForCurrentOS -PskipAndroid=true
```

Without a bundled worker in development, the desktop app can bootstrap `~/.ankiride/venv` when Python 3 `venv` and network access are available.

## Module architecture and persistence

ClickControl is structured into three Gradle modules:

- **`core/` (pure Kotlin JVM 17)**: Shared domain contracts (`Contracts.kt`, including `PlatformKind`, `PlatformCapabilities`, `MyWhooshLocation`, `AnkiTarget.ANKIWEB`, unified `BleConnectionState`, `ButtonGestureEvent`), BLE protobuf/button parsing and policy (`ZwiftPacketParser`, `PodSelectionPolicy`, `ZwiftGestureEngine` with coalesced hold-to-repeat + `onRepeatReleased`), `AnkiConnectClient`, `LanAddressValidator`, `DiagnosticLogger`, and `PresetStore` (v3 serialization reading v1/v2/v3 presets, `PresetTemplate` starter builder, platform-aware labels).
- **`desktop/` (Compose Desktop — Ubuntu Adwaita Dark + Yaru accent)**: Desktop windowing/tray (`Main.kt`), GNOME/Adwaita/Yaru UI (`DesktopAppUi.kt`, `ClickControlTheme.kt`, `FloatingHudWindow.kt`), `ButtonMappingStore` (`~/.ankiride/settings.properties` + Preferences fallback), `DesktopActionRouter`, `DesktopAnkiDispatcher` + `AnkiWebKeyboardBridge`, `DesktopMyWhooshDispatcher` + `OpenBikeControlClient` + `DesktopMdnsAdvertiser`, `DesktopZwiftBleController`, and `GenericInput` (`RobotInputDriver` + `WaylandInputBackend` with coalesced repeat queue).
- **`app/` (Android — Material 3 System UI)**: Compose UI (`MainActivity.kt`, `ClickControlTheme.kt`, `FloatingHudPill.kt`), foreground BLE service and `ZwiftBleController`, `ButtonMappingStore` (`ankiride_app_settings_v4`), `ActionRouter`, `AnkiActionDispatcherImpl` + `AnkiWebAccessibilityBridge` + `AnkiContentProviderHelper`, `MyWhooshNetworkDispatcherImpl` + touch shift fallback/calibrator, and accessibility-backed `GenericInput` (`computeSwipeSegment` + coalesced touch queue).

Note on AnkiWeb bridges:
- **Desktop**: `AnkiWebKeyboardBridge` sends `Space` (with 350 ms bounce guard on Flip) and `1`–`4` through `DesktopGenericInputDispatcher` when `AnkiTarget.ANKIWEB` is selected. `UNDO` returns a clear unsupported warning.
- **Android**: `accessibility_service_config.xml` intentionally omits a fixed `packageNames` restriction so `AnkiWebAccessibilityBridge` can inspect the currently active accessibility window on-demand (`ankiweb.net` / `ankiuser.net` web views in mobile browsers) only when a controller button fires an `ANKIWEB` action. Password fields (`isPassword`) are strictly skipped and `onAccessibilityEvent` stays empty.

## Worker protocol & CI

The desktop controller spawns the BLE worker with ownership (`RIGHT_ONLY`, `BOTH`, `ANY_SINGLE`) and pinned-address arguments and reads `STATE`, `PACKET`, and `LOG` records on stdout (`--protocol-version` -> `CLICKCONTROL_BLE_PROTOCOL_3`).

Release workflows build and verify Desktop (Linux, macOS, Windows with bundled PyInstaller worker and `--self-test`) and Android (`:app:testDebugUnitTest`, `:app:lintDebug`, signed release APK). Release APKs require explicit release signing credentials without debug fallback. See [AUDIT.md](../AUDIT.md) for current verification coverage.
