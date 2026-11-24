#!/usr/bin/env bash
set -euo pipefail

echo "=== [RG-0] Checking Core Contracts (Defaults OFF for HUD/Haptic/Audio + PlatformCapabilities) ==="
grep -q "floatingHudPillEnabled: Boolean = false" core/src/main/kotlin/com/ankiride/bridge/core/Contracts.kt
grep -q "hapticFeedbackEnabled: Boolean = false" core/src/main/kotlin/com/ankiride/bridge/core/Contracts.kt
grep -q "audioFeedbackEnabled: Boolean = false" core/src/main/kotlin/com/ankiride/bridge/core/Contracts.kt
grep -q "data class PlatformCapabilities" core/src/main/kotlin/com/ankiride/bridge/core/Contracts.kt
grep -q "enum class PresetTemplate" core/src/main/kotlin/com/ankiride/bridge/core/PresetStore.kt

echo "=== [RG-1] Checking BLE Parser Bounds & Gesture Engine Long-Press Release Fix ==="
grep -q "MAX_VARINT_BYTES = 5" core/src/main/kotlin/com/ankiride/bridge/ble/ZwiftPacketParser.kt
grep -q "DEBOUNCE_WINDOW_MS = 120L" core/src/main/kotlin/com/ankiride/bridge/ble/ZwiftGestureEngine.kt

echo "=== [RG-2] Checking Anki Bridges (AnkiDroid, AnkiWeb, AnkiConnect) ==="
test -f core/src/main/kotlin/com/ankiride/bridge/anki/AnkiConnectClient.kt
test -f app/src/main/java/com/ankiride/bridge/anki/AnkiWebAccessibilityBridge.kt
test -f desktop/src/main/kotlin/com/ankiride/bridge/anki/AnkiWebKeyboardBridge.kt
grep -q "LanAddressValidator.isLocalOrLanAddress" core/src/main/kotlin/com/ankiride/bridge/anki/AnkiConnectClient.kt
grep -q "guiAnswerCard" core/src/main/kotlin/com/ankiride/bridge/anki/AnkiConnectClient.kt

echo "=== [RG-3] Checking Profile-Keyed MyShiftOverlayCalibrator & Network Security Config ==="
grep -q "profilePrefix" app/src/main/java/com/ankiride/bridge/mywhoosh/MyShiftOverlayCalibrator.kt
grep -q "TAP_DURATION_MS = 75L" app/src/main/java/com/ankiride/bridge/mywhoosh/MyShiftGestureFallback.kt
grep -q 'cleartextTrafficPermitted="false"' app/src/main/res/xml/network_security_config.xml

echo "=== [RG-4 & RG-5] Checking FloatingHudPill, FeedbackCueManager & Platform UI Separation ==="
test -f app/src/main/java/com/ankiride/bridge/ui/FloatingHudPill.kt
test -f app/src/main/java/com/ankiride/bridge/service/FeedbackCueManager.kt
grep -q "AndroidSystemSection" app/src/main/java/com/ankiride/bridge/ui/MainActivity.kt
grep -q "AdwPreferencesGroup" desktop/src/main/kotlin/com/ankiride/bridge/ui/DesktopAppUi.kt

echo "=== [RG-6] Running Full Unit Test Suite ==="
./gradlew :core:test :app:testDebugUnitTest :desktop:test --no-daemon

echo "ALL STATIC GUARDS AND CORE / ANDROID / DESKTOP UNIT TESTS PASSED."
