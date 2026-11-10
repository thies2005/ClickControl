# Development

## Requirements and commands

Use JDK 17 or newer (CI uses 21) and the Gradle wrapper. Android builds require SDK platform 35 and build tools, configured with `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`. Without an SDK, Gradle omits the Android project; `-PskipAndroid=true` explicitly selects desktop-only configuration. The full verification script requires the Android SDK.

```bash
./gradlew :desktop:test -PskipAndroid=true
./verify.sh
./gradlew :app:assembleDebug
./gradlew :desktop:run -PskipAndroid=true
./gradlew createDistributable packageDistributionForCurrentOS -PskipAndroid=true
python3 desktop/src/test/python/test_worker_side.py
```

The debug APK is under `app/build/outputs/apk/debug/`. Compose native app bundles and installers are under `desktop/build/compose/binaries/main/` (`app/`, `deb/`, `dmg/`, `msi/`, or `exe/` as applicable). Packaging targets the host OS. A packaged executable accepts `--self-test`, a headless smoke test of parser, gestures, routing, settings and local server startup; it does not exercise physical BLE or target apps.

The desktop worker uses Python and Bleak. Release CI builds a standalone PyInstaller worker and places it in `desktop/build/packaged-resources/common/` before packaging. Without a compatible bundled worker, development can bootstrap `~/.ankiride/venv`, requiring Python 3, its `venv` module, network access and Bleak (`dbus-fast` also on Linux). To build a standalone worker locally, install `bleak`, `pyinstaller` and, on Linux, `dbus-fast` in a build environment, then:

```bash
python -m PyInstaller --clean --noconfirm --onefile --name zwift_ble_worker desktop/src/main/resources/ble/zwift_ble_worker.py
mkdir -p desktop/build/packaged-resources/common
install -m 755 dist/zwift_ble_worker desktop/build/packaged-resources/common/zwift_ble_worker
./gradlew createDistributable packageDistributionForCurrentOS -PskipAndroid=true
```

## Structure and persistence

`app/` is the Android Compose app and foreground service; `desktop/` is the Compose Desktop app. Each has BLE parsing and gesture handling, a router, Anki and MyWhoosh dispatchers, and preset/configuration stores. Desktop Bluetooth is delegated to the Python worker. Android also has accessibility integration and touch fallback. Shared concepts are implemented in both modules; keep behavior and tests aligned when changing either.

The Android application ID remains `com.ankiride.bridge`; settings and older per-mode bindings use private preferences `ankiride_app_settings_v4` and `ankiride_per_mode_bindings_v4`. Desktop uses `~/.ankiride/settings.properties` with Java Preferences fallback. Presets use a versioned serialization (v3 writes, v1/v2/v3 reads, retaining the `named_presets_v1` storage key), stable UUID IDs, an active ID, destination and button/press action entries. The migration derives editable Imported presets from existing mode settings; old settings remain for recovery. Ownership and pinned addresses are separate from preset bindings.

## Worker protocol and CI

The desktop controller launches the worker with ownership and pin arguments. The worker emits line-oriented `STATE`, `PACKET`, and `LOG` records on stdout. `--protocol-version` returns `CLICKCONTROL_BLE_PROTOCOL_3`; the desktop controller checks compatibility before using a standalone worker. See the worker and controller source for exact records and options.

The release workflow runs desktop tests and packages on Ubuntu, macOS and Windows, builds and checks a bundled worker and runs the packaged self-test. Android CI runs debug unit tests and assembles a release APK. Workflow artifacts are named `ClickControl-<runner OS>` and `ClickControl-android`; staged installers and archives are collected from `release-assets/`. Non-pull-request runs publish a prerelease with checksums after both jobs succeed. Android release signing falls back to a debug key when release keystore environment variables are absent; inspect the build configuration before distributing an APK.

Generic input is dispatched separately from Anki and MyWhoosh. Per-binding payloads accompany Screen click and Keyboard input actions. Desktop uses a serialized Robot backend outside Wayland and a `ydotool` backend on Wayland; Android uses accessibility gestures and, on API 33+, an accessibility input connection for keyboard events. See [Input actions](INPUT_ACTIONS.md).
