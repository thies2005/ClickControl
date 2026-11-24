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

    private const val KEY_ASSIGNMENT = "pod_assignment"
    private const val KEY_DEFAULT_PRESET_ID = "default_preset_id"
    private const val KEY_START_MINIMIZED = "start_minimized_to_tray"
    private const val KEY_AUTO_START = "auto_start_on_wake"
    private const val KEY_FLOATING_HUD = "floating_hud_pill_enabled"
    private const val KEY_HAPTIC = "haptic_feedback_enabled"
    private const val KEY_AUDIO = "audio_feedback_enabled"
    private const val KEY_UBUNTU_HOST = "ubuntu_ankiconnect_host"
    private const val KEY_UBUNTU_PORT = "ubuntu_ankiconnect_port"
    private const val KEY_LEFT_POD_RECONNECT = "left_pod_auto_reconnect_sec"
    private const val KEY_OPENBIKE_PORT = "open_bike_server_port"
    private const val KEY_PINNED_RIGHT_MAC = "pinned_right_pod_mac"
    private const val KEY_PINNED_LEFT_MAC = "pinned_left_pod_mac"

    private val LEGACY_KEYS = listOf(
        "operating_mode",
        "right_pod_only",
        "use_z_for_shift_down",
        "touch_fallback",
        "my_shift_up_norm_x",
        "my_shift_up_norm_y",
        "my_shift_down_norm_x",
        "my_shift_down_norm_y"
    )

    fun defaultSettingsFile(): File {
        val dir = File(System.getProperty("user.home"), ".ankiride")
        return File(dir, "settings.properties")
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
                val (starterPresets, activeId) = PresetStore.defaultStarterPresets(PlatformCapabilities.desktop())
                return fallback.copy(presets = starterPresets, activePresetId = activeId)
            }
        }

        val config = AppConfiguration(
            defaultPresetId = props.getProperty(KEY_DEFAULT_PRESET_ID)?.takeIf { it.isNotBlank() },
            podAssignment = PodAssignment.fromStoredName(props.getProperty(KEY_ASSIGNMENT))
                ?: PodAssignment.SINGLE_CONTROLLER,
            autoStartOnControllerWake = props.getProperty(KEY_AUTO_START, "true").toBoolean(),
            startMinimizedToTray = props.getProperty(KEY_START_MINIMIZED, "false").toBoolean(),
            floatingHudPillEnabled = props.getProperty(KEY_FLOATING_HUD, "false").toBoolean(),
            hapticFeedbackEnabled = props.getProperty(KEY_HAPTIC, "false").toBoolean(),
            audioFeedbackEnabled = props.getProperty(KEY_AUDIO, "false").toBoolean(),
            ubuntuAnkiConnectHost = props.getProperty(KEY_UBUNTU_HOST, "127.0.0.1").trim(),
            ubuntuAnkiConnectPort = props.getProperty(KEY_UBUNTU_PORT, "8765").toIntOrNull()?.takeIf { it in 1..65535 } ?: 8765,
            leftPodAutoReconnectSec = props.getProperty(KEY_LEFT_POD_RECONNECT, "55").toIntOrNull()?.coerceAtLeast(0) ?: 55,
            openBikeServerPort = props.getProperty(KEY_OPENBIKE_PORT, "21587").toIntOrNull()?.takeIf { it in 1..65535 } ?: 21587,
            pinnedRightPodMac = props.getProperty(KEY_PINNED_RIGHT_MAC)?.takeIf { it.isNotBlank() },
            pinnedLeftPodMac = props.getProperty(KEY_PINNED_LEFT_MAC)?.takeIf { it.isNotBlank() }
        )

        val saved = PresetStore.deserialize(props.getProperty("named_presets_v1"))
        if (saved != null) return config.copy(presets = saved.first, activePresetId = saved.second ?: saved.first.first().id)

        val (starterPresets, activeId) = PresetStore.defaultStarterPresets(PlatformCapabilities.desktop())
        return config.copy(presets = starterPresets, activePresetId = activeId)
    }

    fun saveConfiguration(config: AppConfiguration, file: File = defaultSettingsFile()) {
        val props = Properties()
        if (file.exists()) {
            try {
                file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { props.load(it) }
            } catch (e: Exception) {
                DiagnosticLogger.error(LogCategory.SYSTEM, "Cannot safely update unreadable settings: ${e.message}")
                saveToPreferencesFallback(config, file)
                return
            }
        }

        // Scrub legacy mode/robot properties while preserving unrelated keys.
        LEGACY_KEYS.forEach { props.remove(it) }
        props.stringPropertyNames()
            .filter { key ->
                val parts = key.split("|")
                parts.size == 3 && parts[0].startsWith("MODE_")
            }
            .forEach { props.remove(it) }

        if (config.presets.isNotEmpty()) {
            props.setProperty("named_presets_v1", PresetStore.serialize(config.presets, config.activePresetId))
        }
        if (!config.defaultPresetId.isNullOrBlank()) {
            props.setProperty(KEY_DEFAULT_PRESET_ID, config.defaultPresetId)
        } else {
            props.remove(KEY_DEFAULT_PRESET_ID)
        }
        props.setProperty(KEY_ASSIGNMENT, config.podAssignment.name)
        props.setProperty(KEY_AUTO_START, config.autoStartOnControllerWake.toString())
        props.setProperty(KEY_START_MINIMIZED, config.startMinimizedToTray.toString())
        props.setProperty(KEY_FLOATING_HUD, config.floatingHudPillEnabled.toString())
        props.setProperty(KEY_HAPTIC, config.hapticFeedbackEnabled.toString())
        props.setProperty(KEY_AUDIO, config.audioFeedbackEnabled.toString())
        props.setProperty(KEY_UBUNTU_HOST, config.ubuntuAnkiConnectHost.trim())
        props.setProperty(KEY_UBUNTU_PORT, config.ubuntuAnkiConnectPort.toString())
        props.setProperty(KEY_LEFT_POD_RECONNECT, config.leftPodAutoReconnectSec.toString())
        props.setProperty(KEY_OPENBIKE_PORT, config.openBikeServerPort.toString())
        if (!config.pinnedRightPodMac.isNullOrBlank()) props.setProperty(KEY_PINNED_RIGHT_MAC, config.pinnedRightPodMac) else props.remove(KEY_PINNED_RIGHT_MAC)
        if (!config.pinnedLeftPodMac.isNullOrBlank()) props.setProperty(KEY_PINNED_LEFT_MAC, config.pinnedLeftPodMac) else props.remove(KEY_PINNED_LEFT_MAC)

        try {
            file.parentFile?.mkdirs()
            val temp = File.createTempFile("settings-", ".tmp", file.absoluteFile.parentFile)
            try {
                FileOutputStream(temp).use { fos ->
                    OutputStreamWriter(fos, StandardCharsets.UTF_8).use { writer -> props.store(writer, "ClickControl Desktop Settings") }
                }
                try {
                    java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temp.delete()
            }
            try {
                val prefs = fallbackPreferences()
                if (prefs.get(FALLBACK_PATH, null) == file.absoluteFile.normalize().path) {
                    prefs.remove(FALLBACK_PATH)
                    prefs.flush()
                }
            } catch (e: Exception) {
                DiagnosticLogger.warn(LogCategory.SYSTEM, "Could not clear settings fallback marker: ${e.message}")
            }
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
            val assignmentRaw = prefs.get(KEY_ASSIGNMENT, null)
            val hasMarker = prefs.get(FALLBACK_PATH, null) != null
            if (assignmentRaw == null && !hasMarker) return null
            val saved = readFallbackPresets(prefs)
            AppConfiguration(
                presets = saved?.first ?: emptyList(),
                activePresetId = saved?.second,
                defaultPresetId = prefs.get(KEY_DEFAULT_PRESET_ID, null)?.takeIf { it.isNotBlank() },
                podAssignment = PodAssignment.fromStoredName(assignmentRaw) ?: PodAssignment.SINGLE_CONTROLLER,
                autoStartOnControllerWake = prefs.getBoolean(KEY_AUTO_START, true),
                startMinimizedToTray = prefs.getBoolean(KEY_START_MINIMIZED, false),
                floatingHudPillEnabled = prefs.getBoolean(KEY_FLOATING_HUD, false),
                hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC, false),
                audioFeedbackEnabled = prefs.getBoolean(KEY_AUDIO, false),
                ubuntuAnkiConnectHost = prefs.get(KEY_UBUNTU_HOST, "127.0.0.1"),
                ubuntuAnkiConnectPort = prefs.getInt(KEY_UBUNTU_PORT, 8765),
                leftPodAutoReconnectSec = prefs.getInt(KEY_LEFT_POD_RECONNECT, 55),
                openBikeServerPort = prefs.getInt(KEY_OPENBIKE_PORT, 21587),
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
            prefs.put(KEY_ASSIGNMENT, config.podAssignment.name)
            if (!config.defaultPresetId.isNullOrBlank()) prefs.put(KEY_DEFAULT_PRESET_ID, config.defaultPresetId) else prefs.remove(KEY_DEFAULT_PRESET_ID)
            prefs.putBoolean(KEY_AUTO_START, config.autoStartOnControllerWake)
            prefs.putBoolean(KEY_START_MINIMIZED, config.startMinimizedToTray)
            prefs.putBoolean(KEY_FLOATING_HUD, config.floatingHudPillEnabled)
            prefs.putBoolean(KEY_HAPTIC, config.hapticFeedbackEnabled)
            prefs.putBoolean(KEY_AUDIO, config.audioFeedbackEnabled)
            prefs.put(KEY_UBUNTU_HOST, config.ubuntuAnkiConnectHost.trim())
            prefs.putInt(KEY_UBUNTU_PORT, config.ubuntuAnkiConnectPort)
            prefs.putInt(KEY_LEFT_POD_RECONNECT, config.leftPodAutoReconnectSec)
            prefs.putInt(KEY_OPENBIKE_PORT, config.openBikeServerPort)
            if (!config.pinnedRightPodMac.isNullOrBlank()) prefs.put(KEY_PINNED_RIGHT_MAC, config.pinnedRightPodMac) else prefs.remove(KEY_PINNED_RIGHT_MAC)
            if (!config.pinnedLeftPodMac.isNullOrBlank()) prefs.put(KEY_PINNED_LEFT_MAC, config.pinnedLeftPodMac) else prefs.remove(KEY_PINNED_LEFT_MAC)
            prefs.put(FALLBACK_PATH, file.absoluteFile.normalize().path)
            prefs.flush()
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.SYSTEM, "Failed to save settings fallback: ${e.message}")
        }
    }
}
