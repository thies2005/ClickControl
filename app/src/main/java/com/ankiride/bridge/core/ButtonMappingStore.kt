package com.ankiride.bridge.core

import android.content.Context

object ButtonMappingStore {
    private const val PREFS_SETTINGS = "ankiride_app_settings_v4"

    private const val KEY_ASSIGNMENT = "pod_assignment"
    private const val KEY_RIGHT_PIN = "pinned_right_pod_mac"
    private const val KEY_LEFT_PIN = "pinned_left_pod_mac"
    private const val KEY_DEFAULT_PRESET_ID = "default_preset_id"
    private const val KEY_MYWHOOSH_LOCATION = "mywhoosh_location"
    private const val KEY_AUTO_START = "auto_start_on_wake"
    private const val KEY_FLOATING_HUD = "floating_hud_pill_enabled"
    private const val KEY_HAPTIC = "haptic_feedback_enabled"
    private const val KEY_AUDIO = "audio_feedback_enabled"
    private const val KEY_UBUNTU_HOST = "ubuntu_ankiconnect_host"
    private const val KEY_UBUNTU_PORT = "ubuntu_ankiconnect_port"
    private const val KEY_OPENBIKE_PORT = "open_bike_server_port"
    private const val KEY_TOUCH_FALLBACK = "touch_fallback"

    internal fun podSettingsFrom(settings: Map<String, *>): Triple<PodAssignment, String?, String?> {
        val assignment = PodAssignment.fromStoredName(settings[KEY_ASSIGNMENT] as? String)
            ?: PodAssignment.SINGLE_CONTROLLER
        val rightPin = (settings[KEY_RIGHT_PIN] as? String)?.trim()?.ifBlank { null }
        val leftPin = (settings[KEY_LEFT_PIN] as? String)?.trim()?.ifBlank { null }
        return Triple(assignment, rightPin, leftPin)
    }

    internal fun podSettingsFor(config: AppConfiguration): Map<String, String?> = mapOf(
        KEY_ASSIGNMENT to config.podAssignment.name,
        KEY_RIGHT_PIN to config.pinnedRightPodMac?.trim()?.ifBlank { null },
        KEY_LEFT_PIN to config.pinnedLeftPodMac?.trim()?.ifBlank { null }
    )

    fun loadFullConfiguration(context: Context): AppConfiguration {
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val myWhooshLocation = prefs.getString(KEY_MYWHOOSH_LOCATION, null)
            ?.let { raw -> MyWhooshLocation.entries.firstOrNull { it.name == raw } }
            ?: MyWhooshLocation.ON_THIS_DEVICE
        val podTriple = podSettingsFrom(prefs.all)
        val config = AppConfiguration(
            defaultPresetId = prefs.getString(KEY_DEFAULT_PRESET_ID, null)?.takeIf { it.isNotBlank() },
            myWhooshLocation = myWhooshLocation,
            podAssignment = podTriple.first,
            pinnedRightPodMac = podTriple.second,
            pinnedLeftPodMac = podTriple.third,
            autoStartOnControllerWake = prefs.getBoolean(KEY_AUTO_START, true),
            floatingHudPillEnabled = prefs.getBoolean(KEY_FLOATING_HUD, false),
            hapticFeedbackEnabled = prefs.getBoolean(KEY_HAPTIC, false),
            audioFeedbackEnabled = prefs.getBoolean(KEY_AUDIO, false),
            ubuntuAnkiConnectHost = prefs.getString(KEY_UBUNTU_HOST, "192.168.1.100") ?: "192.168.1.100",
            ubuntuAnkiConnectPort = prefs.getInt(KEY_UBUNTU_PORT, 8765),
            openBikeServerPort = prefs.getInt(KEY_OPENBIKE_PORT, 21587),
            useMyShiftTouchFallbackWhenNoClient = prefs.getBoolean(KEY_TOUCH_FALLBACK, false)
        )
        val saved = PresetStore.deserialize(prefs.getString("named_presets_v1", null))
        if (saved != null) {
            return config.copy(presets = saved.first, activePresetId = saved.second ?: saved.first.first().id)
        }

        val (starterPresets, activeId) = PresetStore.defaultStarterPresets(PlatformCapabilities.android())
        return config.copy(presets = starterPresets, activePresetId = activeId)
    }

    fun saveFullConfiguration(context: Context, config: AppConfiguration) {
        val editor = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE).edit()
        if (config.presets.isNotEmpty()) {
            editor.putString("named_presets_v1", PresetStore.serialize(config.presets, config.activePresetId))
        }
        podSettingsFor(config).forEach { (key, value) -> editor.putString(key, value) }
        if (!config.defaultPresetId.isNullOrBlank()) {
            editor.putString(KEY_DEFAULT_PRESET_ID, config.defaultPresetId)
        } else {
            editor.remove(KEY_DEFAULT_PRESET_ID)
        }
        editor.putString(KEY_MYWHOOSH_LOCATION, config.myWhooshLocation.name)
            .putBoolean(KEY_AUTO_START, config.autoStartOnControllerWake)
            .putBoolean(KEY_FLOATING_HUD, config.floatingHudPillEnabled)
            .putBoolean(KEY_HAPTIC, config.hapticFeedbackEnabled)
            .putBoolean(KEY_AUDIO, config.audioFeedbackEnabled)
            .putString(KEY_UBUNTU_HOST, config.ubuntuAnkiConnectHost.trim())
            .putInt(KEY_UBUNTU_PORT, config.ubuntuAnkiConnectPort)
            .putInt(KEY_OPENBIKE_PORT, config.openBikeServerPort)
            .putBoolean(KEY_TOUCH_FALLBACK, config.useMyShiftTouchFallbackWhenNoClient)
            .remove("operating_mode")
            .remove("right_pod_only")
            .remove("use_z_for_shift_down")
            .apply()
    }
}
