package com.ankiride.bridge.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.util.prefs.Preferences

object ButtonMappingStore {
    internal var fallbackPreferences: () -> Preferences = { Preferences.userNodeForPackage(ButtonMappingStore::class.java) }
    private const val FALLBACK_PATH = "active_fallback_path"


    fun assignedAction(assignment: PodAssignment, button: ClickButton, press: PressType): MappedTargetAction {
        val b = button.canonicalButton
        if (b.isRightPod) return when (b) {
            ClickButton.Y -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_AGAIN
            ClickButton.Z -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_HARD
            ClickButton.A -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_FLIP_OR_EASY else MappedTargetAction.ANKI_FLIP_OR_GOOD
            ClickButton.B -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_GOOD
            ClickButton.SHIFT_UP_RIGHT -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_ONLY
            ClickButton.SHIFT_DOWN_RIGHT -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_AGAIN
            else -> MappedTargetAction.NONE
        }
        if (assignment != PodAssignment.RIGHT_ANKI_LEFT_BIKE) return MappedTargetAction.NONE
        return when (b) {
            ClickButton.SHIFT_UP_LEFT -> MappedTargetAction.MW_SHIFT_UP
            ClickButton.SHIFT_DOWN_LEFT -> if (press == PressType.LONG_PRESS) MappedTargetAction.MW_SHIFT_UP else MappedTargetAction.MW_SHIFT_DOWN
            ClickButton.LEFT -> MappedTargetAction.MW_STEER_LEFT
            ClickButton.RIGHT -> MappedTargetAction.MW_STEER_RIGHT
            ClickButton.UP -> MappedTargetAction.MW_CAMERA
            ClickButton.DOWN -> MappedTargetAction.MW_EMOTE
            else -> MappedTargetAction.NONE
        }
    }

    private const val KEY_MODE = "operating_mode"
    private const val KEY_ASSIGNMENT = "pod_assignment"
    private const val KEY_RIGHT_POD_ONLY = "right_pod_only"
    const val KEY_USE_Z_SHIFT_DOWN = "use_z_for_shift_down"
    private const val KEY_USE_Z_FOR_SHIFT_DOWN = KEY_USE_Z_SHIFT_DOWN
    private const val KEY_AUTO_START = "auto_start_on_wake"
    private const val KEY_FLOATING_HUD = "floating_hud_pill_enabled"
    private const val KEY_HAPTIC = "haptic_feedback_enabled"
    private const val KEY_AUDIO = "audio_feedback_enabled"
    private const val KEY_UBUNTU_HOST = "ubuntu_ankiconnect_host"
    private const val KEY_UBUNTU_PORT = "ubuntu_ankiconnect_port"
    private const val KEY_LEFT_POD_RECONNECT = "left_pod_auto_reconnect_sec"
    private const val KEY_OPENBIKE_PORT = "open_bike_server_port"
    private const val KEY_TOUCH_FALLBACK = "touch_fallback"
    private const val KEY_SHIFT_UP_X = "my_shift_up_norm_x"
    private const val KEY_SHIFT_UP_Y = "my_shift_up_norm_y"
    private const val KEY_SHIFT_DOWN_X = "my_shift_down_norm_x"
    private const val KEY_SHIFT_DOWN_Y = "my_shift_down_norm_y"
    private const val KEY_PINNED_RIGHT_MAC = "pinned_right_pod_mac"
    private const val KEY_PINNED_LEFT_MAC = "pinned_left_pod_mac"

    fun defaultSettingsFile(): File {
        val dir = File(System.getProperty("user.home"), ".ankiride")
        return File(dir, "settings.properties")
    }

    fun resolveAction(
        mode: OperatingMode,
        button: ClickButton,
        pressType: PressType,
        customBindings: Map<ModeButtonBindingKey, MappedTargetAction> = emptyMap(),
        rightPodOnlyNoZwift: Boolean = false,
        useZForShiftDownInSinglePodMode: Boolean = false
    ): MappedTargetAction {
        val canonical = when (button) { ClickButton.SHIFT_UP_LEFT -> ClickButton.SHIFT_UP_RIGHT; ClickButton.SHIFT_DOWN_RIGHT -> ClickButton.SHIFT_DOWN_LEFT; else -> button.canonicalButton }
        val key = ModeButtonBindingKey(mode, canonical, pressType)
        customBindings[key]?.let { return it }

        if (rightPodOnlyNoZwift && useZForShiftDownInSinglePodMode) {
            if (canonical == ClickButton.Z && pressType == PressType.SINGLE_CLICK) {
                when (mode) {
                    OperatingMode.MODE_1_TABLET_SPLIT_SCREEN,
                    OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH,
                    OperatingMode.MODE_4_MYWHOOSH_ONLY,
                    OperatingMode.MODE_5_UBUNTU_ANKICONNECT -> {
                        return MappedTargetAction.MW_SHIFT_DOWN
                    }
                    else -> {}
                }
            }
        }

        return defaultActionFor(mode, canonical, pressType, rightPodOnlyNoZwift, useZForShiftDownInSinglePodMode)
    }

    fun defaultActionFor(
        mode: OperatingMode,
        button: ClickButton,
        pressType: PressType,
        rightPodOnlyNoZwift: Boolean = false,
        useZForShiftDownInSinglePodMode: Boolean = false
    ): MappedTargetAction {
        val canonical = when (button) { ClickButton.SHIFT_UP_LEFT -> ClickButton.SHIFT_UP_RIGHT; ClickButton.SHIFT_DOWN_RIGHT -> ClickButton.SHIFT_DOWN_LEFT; else -> button.canonicalButton }
        if (rightPodOnlyNoZwift && useZForShiftDownInSinglePodMode) {
            if (canonical == ClickButton.Z && pressType == PressType.SINGLE_CLICK) {
                when (mode) {
                    OperatingMode.MODE_1_TABLET_SPLIT_SCREEN,
                    OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH,
                    OperatingMode.MODE_4_MYWHOOSH_ONLY,
                    OperatingMode.MODE_5_UBUNTU_ANKICONNECT -> {
                        return MappedTargetAction.MW_SHIFT_DOWN
                    }
                    else -> {}
                }
            }
        }
        return when (mode) {
            OperatingMode.MODE_1_TABLET_SPLIT_SCREEN,
            OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH,
            OperatingMode.MODE_5_UBUNTU_ANKICONNECT -> {
                // Default 4-Colorful-Button Split for all Combined Ride + Anki Modes (Modes 1, 2, and 5):
                // Z (Left Orange) = Shift Up (+), A (Right Green) = Shift Down (–)
                // Y (Top Cyan) = Anki Didn't Know (Again), B (Bottom Pink) = Anki Good
                // Bottom '+' Circle (Below B) = Shift Up (+), Bottom '–' Circle (Below v) = Shift Down (–)
                when (canonical) {
                    ClickButton.Z -> MappedTargetAction.MW_SHIFT_UP
                    ClickButton.A -> MappedTargetAction.MW_SHIFT_DOWN
                    ClickButton.Y -> if (pressType == PressType.LONG_PRESS) {
                        MappedTargetAction.ANKI_UNDO
                    } else {
                        MappedTargetAction.ANKI_FLIP_OR_AGAIN
                    }
                    ClickButton.B -> if (pressType == PressType.LONG_PRESS) {
                        MappedTargetAction.ANKI_UNDO
                    } else {
                        MappedTargetAction.ANKI_FLIP_OR_GOOD
                    }
                    ClickButton.SHIFT_UP_RIGHT -> if (pressType == PressType.LONG_PRESS) {
                        MappedTargetAction.MW_SHIFT_DOWN
                    } else {
                        MappedTargetAction.MW_SHIFT_UP
                    }
                    ClickButton.SHIFT_DOWN_LEFT -> MappedTargetAction.MW_SHIFT_DOWN
                    ClickButton.LEFT -> MappedTargetAction.MW_STEER_LEFT
                    ClickButton.RIGHT -> MappedTargetAction.MW_STEER_RIGHT
                    ClickButton.UP -> MappedTargetAction.MW_CAMERA
                    ClickButton.DOWN -> MappedTargetAction.MW_EMOTE
                    else -> MappedTargetAction.NONE
                }
            }

            OperatingMode.MODE_3_ANKI_ONLY -> {
                when (canonical) {
                    ClickButton.Y -> if (pressType == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_AGAIN
                    ClickButton.B -> if (pressType == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_GOOD
                    ClickButton.A -> if (pressType == PressType.LONG_PRESS) MappedTargetAction.ANKI_FLIP_OR_EASY else MappedTargetAction.ANKI_FLIP_OR_GOOD
                    ClickButton.Z -> if (pressType == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_HARD
                    ClickButton.SHIFT_UP_RIGHT -> MappedTargetAction.ANKI_FLIP_OR_GOOD
                    ClickButton.SHIFT_DOWN_LEFT, ClickButton.LEFT -> MappedTargetAction.ANKI_UNDO
                    ClickButton.RIGHT, ClickButton.UP, ClickButton.DOWN -> MappedTargetAction.ANKI_FLIP_ONLY
                    else -> MappedTargetAction.NONE
                }
            }

            OperatingMode.MODE_4_MYWHOOSH_ONLY -> {
                when (canonical) {
                    ClickButton.Z, ClickButton.SHIFT_UP_RIGHT -> MappedTargetAction.MW_SHIFT_UP
                    ClickButton.A, ClickButton.SHIFT_DOWN_LEFT -> MappedTargetAction.MW_SHIFT_DOWN
                    ClickButton.Y, ClickButton.LEFT -> MappedTargetAction.MW_STEER_LEFT
                    ClickButton.B, ClickButton.RIGHT -> MappedTargetAction.MW_STEER_RIGHT
                    ClickButton.UP -> MappedTargetAction.MW_CAMERA
                    ClickButton.DOWN -> MappedTargetAction.MW_EMOTE
                    else -> MappedTargetAction.NONE
                }
            }
        }
    }

    fun loadConfiguration(file: File = defaultSettingsFile()): AppConfiguration {
        val props = Properties()
        var loadedFromFile = false
        val fallbackSelected = try { fallbackPreferences().get(FALLBACK_PATH, null) == file.absoluteFile.normalize().path } catch (_: Exception) { false }
        if (fallbackSelected) loadFromPreferencesFallback()?.let { return it }

        if (file.exists() && file.isFile) {
            try {
                FileInputStream(file).use { fis ->
                    InputStreamReader(fis, StandardCharsets.UTF_8).use { reader ->
                        props.load(reader)
                    }
                }
                loadedFromFile = true
            } catch (e: Exception) {
                DiagnosticLogger.warn(LogCategory.SYSTEM, "Failed to read ${file.absolutePath}: ${e.message}")
            }
        }

        if (!loadedFromFile) {
            val fallback = loadFromPreferencesFallback()
            if (fallback != null) {
                if (fallback.presets.isNotEmpty()) return fallback
                val modes = OperatingMode.entries
                val presets = modes.map { mode -> PresetStore.snapshot("Imported · ${mode.shortTitle}", AnkiTarget.ANKICONNECT) { button, press ->
                    if (fallback.podAssignment != PodAssignment.LEGACY_SHARED) assignedAction(fallback.podAssignment, button, press)
                    else resolveAction(mode, button, press, fallback.customBindings, fallback.rightPodOnlyNoZwift, fallback.useZForShiftDownInSinglePodMode)
                } }
                return fallback.copy(presets = presets, activePresetId = presets[modes.indexOf(fallback.mode)].id)
            }
        }

        val modeName = props.getProperty(KEY_MODE, OperatingMode.MODE_1_TABLET_SPLIT_SCREEN.name)
        val mode = OperatingMode.entries.firstOrNull { it.name == modeName } ?: OperatingMode.MODE_1_TABLET_SPLIT_SCREEN

        val customBindings = mutableMapOf<ModeButtonBindingKey, MappedTargetAction>()
        for (name in props.stringPropertyNames()) {
            val parts = name.split("|")
            if (parts.size == 3) {
                val m = OperatingMode.entries.firstOrNull { it.name == parts[0] } ?: continue
                val b = ClickButton.entries.firstOrNull { it.name == parts[1] } ?: continue
                val p = PressType.entries.firstOrNull { it.name == parts[2] } ?: continue
                val actionName = props.getProperty(name)
                val action = MappedTargetAction.entries.firstOrNull { it.name == actionName } ?: continue
                customBindings[ModeButtonBindingKey(m, b, p)] = action
            }
        }

        val config = AppConfiguration(
            mode = mode,
            podAssignment = props.getProperty(KEY_ASSIGNMENT)?.let { name -> PodAssignment.entries.firstOrNull { it.name == name } } ?: if (loadedFromFile) PodAssignment.LEGACY_SHARED else PodAssignment.RIGHT_ANKI_ONLY,
            rightPodOnlyNoZwift = props.getProperty(KEY_RIGHT_POD_ONLY, "true").toBoolean(),
            useZForShiftDownInSinglePodMode = props.getProperty(KEY_USE_Z_FOR_SHIFT_DOWN, "true").toBoolean(),
            autoStartOnControllerWake = props.getProperty(KEY_AUTO_START, "true").toBoolean(),
            floatingHudPillEnabled = props.getProperty(KEY_FLOATING_HUD, "false").toBoolean(),
            hapticFeedbackEnabled = props.getProperty(KEY_HAPTIC, "false").toBoolean(),
            audioFeedbackEnabled = props.getProperty(KEY_AUDIO, "false").toBoolean(),
            ubuntuAnkiConnectHost = props.getProperty(KEY_UBUNTU_HOST, "127.0.0.1").trim(),
            ubuntuAnkiConnectPort = props.getProperty(KEY_UBUNTU_PORT, "8765").toIntOrNull() ?: 8765,
            leftPodAutoReconnectSec = props.getProperty(KEY_LEFT_POD_RECONNECT, "55").toIntOrNull() ?: 55,
            openBikeServerPort = props.getProperty(KEY_OPENBIKE_PORT, "21587").toIntOrNull() ?: 21587,
            useMyShiftTouchFallbackWhenNoClient = props.getProperty(KEY_TOUCH_FALLBACK, "true").toBoolean(),
            myShiftUpNormalizedX = props.getProperty(KEY_SHIFT_UP_X, "0.92").toFloatOrNull() ?: 0.92f,
            myShiftUpNormalizedY = props.getProperty(KEY_SHIFT_UP_Y, "0.85").toFloatOrNull() ?: 0.85f,
            myShiftDownNormalizedX = props.getProperty(KEY_SHIFT_DOWN_X, "0.84").toFloatOrNull() ?: 0.84f,
            myShiftDownNormalizedY = props.getProperty(KEY_SHIFT_DOWN_Y, "0.85").toFloatOrNull() ?: 0.85f,
            pinnedRightPodMac = props.getProperty(KEY_PINNED_RIGHT_MAC)?.takeIf { it.isNotBlank() },
            pinnedLeftPodMac = props.getProperty(KEY_PINNED_LEFT_MAC)?.takeIf { it.isNotBlank() },
            customBindings = customBindings
        )
        val saved = PresetStore.deserialize(props.getProperty("named_presets_v1"))
        if (saved != null) return config.copy(presets = saved.first, activePresetId = saved.second ?: saved.first.first().id)
        val modes = if (loadedFromFile) OperatingMode.entries else listOf(mode)
        val presets = modes.map { oldMode ->
            PresetStore.snapshot(if (loadedFromFile) "Imported · ${oldMode.shortTitle}" else "My controls", AnkiTarget.ANKICONNECT) { button, press ->
                if (config.podAssignment != PodAssignment.LEGACY_SHARED) assignedAction(config.podAssignment, button, press)
                else resolveAction(oldMode, button, press, config.customBindings, config.rightPodOnlyNoZwift, config.useZForShiftDownInSinglePodMode)
            }
        }
        return config.copy(presets = presets, activePresetId = presets[modes.indexOf(mode).coerceAtLeast(0)].id)
    }

    fun saveConfiguration(config: AppConfiguration, file: File = defaultSettingsFile()) {
        val props = Properties()
        if (file.exists()) {
            try { file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { props.load(it) } }
            catch (e: Exception) {
                DiagnosticLogger.error(LogCategory.SYSTEM, "Cannot safely update unreadable settings: ${e.message}")
                saveToPreferencesFallback(config, file)
                return
            }
        }
        if (config.presets.isNotEmpty()) props.setProperty("named_presets_v1", PresetStore.serialize(config.presets, config.activePresetId))
        props.setProperty(KEY_MODE, config.mode.name)
        props.setProperty(KEY_ASSIGNMENT, config.podAssignment.name)
        props.setProperty(KEY_RIGHT_POD_ONLY, config.rightPodOnlyNoZwift.toString())
        props.setProperty(KEY_USE_Z_FOR_SHIFT_DOWN, config.useZForShiftDownInSinglePodMode.toString())
        props.setProperty(KEY_AUTO_START, config.autoStartOnControllerWake.toString())
        props.setProperty(KEY_FLOATING_HUD, config.floatingHudPillEnabled.toString())
        props.setProperty(KEY_HAPTIC, config.hapticFeedbackEnabled.toString())
        props.setProperty(KEY_AUDIO, config.audioFeedbackEnabled.toString())
        props.setProperty(KEY_UBUNTU_HOST, config.ubuntuAnkiConnectHost.trim())
        props.setProperty(KEY_UBUNTU_PORT, config.ubuntuAnkiConnectPort.toString())
        props.setProperty(KEY_LEFT_POD_RECONNECT, config.leftPodAutoReconnectSec.toString())
        props.setProperty(KEY_OPENBIKE_PORT, config.openBikeServerPort.toString())
        props.setProperty(KEY_TOUCH_FALLBACK, config.useMyShiftTouchFallbackWhenNoClient.toString())
        props.setProperty(KEY_SHIFT_UP_X, config.myShiftUpNormalizedX.toString())
        props.setProperty(KEY_SHIFT_UP_Y, config.myShiftUpNormalizedY.toString())
        props.setProperty(KEY_SHIFT_DOWN_X, config.myShiftDownNormalizedX.toString())
        props.setProperty(KEY_SHIFT_DOWN_Y, config.myShiftDownNormalizedY.toString())
        config.pinnedRightPodMac?.let { props.setProperty(KEY_PINNED_RIGHT_MAC, it) }
        config.pinnedLeftPodMac?.let { props.setProperty(KEY_PINNED_LEFT_MAC, it) }

        for ((key, action) in config.customBindings) {
            val serializedKey = "${key.mode.name}|${key.button.name}|${key.pressType.name}"
            props.setProperty(serializedKey, action.name)
        }

        try {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("settings-", ".tmp", file.absoluteFile.parentFile)
            try {
                FileOutputStream(temp).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer -> props.store(writer, "AnkiRideBridge Desktop Settings") }
                }
                try { java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
            } finally { temp.delete() }
            try {
                val prefs = fallbackPreferences()
                if (prefs.get(FALLBACK_PATH, null) == file.absoluteFile.normalize().path) { prefs.remove(FALLBACK_PATH); prefs.flush() }
            } catch (e: Exception) { DiagnosticLogger.warn(LogCategory.SYSTEM, "Could not clear settings fallback marker: ${e.message}") }
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.SYSTEM, "Failed to save settings to ${file.absolutePath}: ${e.message}")
            saveToPreferencesFallback(config, file)
        }
    }

    fun loadFullConfiguration(): AppConfiguration = loadConfiguration()
    fun saveFullConfiguration(config: AppConfiguration) = saveConfiguration(config)

    private fun loadFromPreferencesFallback(): AppConfiguration? {
        return try {
            val prefs = fallbackPreferences()
            val modeName = prefs.get(KEY_MODE, null) ?: return null
            val mode = OperatingMode.entries.firstOrNull { it.name == modeName } ?: OperatingMode.MODE_1_TABLET_SPLIT_SCREEN
            val saved = readFallbackPresets(prefs)
            AppConfiguration(
                presets = saved?.first ?: emptyList(),
                activePresetId = saved?.second,
                mode = mode,
                podAssignment = prefs.get(KEY_ASSIGNMENT, null)?.let { name -> PodAssignment.entries.firstOrNull { it.name == name } } ?: PodAssignment.LEGACY_SHARED,
                rightPodOnlyNoZwift = prefs.getBoolean(KEY_RIGHT_POD_ONLY, true),
                useZForShiftDownInSinglePodMode = prefs.getBoolean(KEY_USE_Z_FOR_SHIFT_DOWN, true),
                autoStartOnControllerWake = prefs.getBoolean(KEY_AUTO_START, true),
                floatingHudPillEnabled = prefs.getBoolean(KEY_FLOATING_HUD, false),
                hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC, false),
                audioFeedbackEnabled = prefs.getBoolean(KEY_AUDIO, false),
                ubuntuAnkiConnectHost = prefs.get(KEY_UBUNTU_HOST, "192.168.1.100"),
                ubuntuAnkiConnectPort = prefs.getInt(KEY_UBUNTU_PORT, 8765),
                leftPodAutoReconnectSec = prefs.getInt(KEY_LEFT_POD_RECONNECT, 55),
                openBikeServerPort = prefs.getInt(KEY_OPENBIKE_PORT, 21587),
                useMyShiftTouchFallbackWhenNoClient = prefs.getBoolean(KEY_TOUCH_FALLBACK, true),
                myShiftUpNormalizedX = prefs.getFloat(KEY_SHIFT_UP_X, 0.92f),
                myShiftUpNormalizedY = prefs.getFloat(KEY_SHIFT_UP_Y, 0.85f),
                myShiftDownNormalizedX = prefs.getFloat(KEY_SHIFT_DOWN_X, 0.84f),
                myShiftDownNormalizedY = prefs.getFloat(KEY_SHIFT_DOWN_Y, 0.85f),
                pinnedRightPodMac = prefs.get(KEY_PINNED_RIGHT_MAC, null),
                pinnedLeftPodMac = prefs.get(KEY_PINNED_LEFT_MAC, null)
            )
        } catch (_: Exception) {
            null
        }
    }

    internal fun readFallbackPresets(prefs: Preferences): Pair<List<MappingPreset>, String?>? {
        val count = prefs.getInt("named_presets_parts", 0)
        if (count !in 1..1000) return null
        val raw = (0 until count).map { prefs.get("named_presets_part_$it", null) ?: return null }.joinToString("")
        return PresetStore.deserialize(raw)
    }

    internal fun writeFallbackPresets(prefs: Preferences, config: AppConfiguration) {
        if (config.presets.isEmpty()) return
        val chunks = PresetStore.serialize(config.presets, config.activePresetId).chunked(6000)
        val oldCount = prefs.getInt("named_presets_parts", 0).coerceIn(0, 1000)
        chunks.forEachIndexed { i, chunk -> prefs.put("named_presets_part_$i", chunk) }
        for (i in chunks.size until oldCount) prefs.remove("named_presets_part_$i")
        prefs.putInt("named_presets_parts", chunks.size)
    }

    private fun saveToPreferencesFallback(config: AppConfiguration, file: File) {
        try {
            val prefs = fallbackPreferences()
            writeFallbackPresets(prefs, config)
            prefs.put(KEY_MODE, config.mode.name)
            prefs.put(KEY_ASSIGNMENT, config.podAssignment.name)
            prefs.putBoolean(KEY_RIGHT_POD_ONLY, config.rightPodOnlyNoZwift)
            prefs.putBoolean(KEY_USE_Z_FOR_SHIFT_DOWN, config.useZForShiftDownInSinglePodMode)
            prefs.putBoolean(KEY_AUTO_START, config.autoStartOnControllerWake)
            prefs.putBoolean(KEY_FLOATING_HUD, config.floatingHudPillEnabled)
            prefs.putBoolean(KEY_HAPTIC, config.hapticFeedbackEnabled)
            prefs.putBoolean(KEY_AUDIO, config.audioFeedbackEnabled)
            prefs.put(KEY_UBUNTU_HOST, config.ubuntuAnkiConnectHost.trim())
            prefs.putInt(KEY_UBUNTU_PORT, config.ubuntuAnkiConnectPort)
            prefs.putInt(KEY_LEFT_POD_RECONNECT, config.leftPodAutoReconnectSec)
            prefs.putInt(KEY_OPENBIKE_PORT, config.openBikeServerPort)
            prefs.putBoolean(KEY_TOUCH_FALLBACK, config.useMyShiftTouchFallbackWhenNoClient)
            prefs.putFloat(KEY_SHIFT_UP_X, config.myShiftUpNormalizedX)
            prefs.putFloat(KEY_SHIFT_UP_Y, config.myShiftUpNormalizedY)
            prefs.putFloat(KEY_SHIFT_DOWN_X, config.myShiftDownNormalizedX)
            prefs.putFloat(KEY_SHIFT_DOWN_Y, config.myShiftDownNormalizedY)
            if (config.pinnedRightPodMac != null) prefs.put(KEY_PINNED_RIGHT_MAC, config.pinnedRightPodMac) else prefs.remove(KEY_PINNED_RIGHT_MAC)
            if (config.pinnedLeftPodMac != null) prefs.put(KEY_PINNED_LEFT_MAC, config.pinnedLeftPodMac) else prefs.remove(KEY_PINNED_LEFT_MAC)
            prefs.put(FALLBACK_PATH, file.absoluteFile.normalize().path)
            prefs.flush()
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.SYSTEM, "Failed to save settings fallback: ${e.message}")
        }
    }
}
