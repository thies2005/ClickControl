package com.ankiride.bridge.core

import org.junit.Assert.*
import org.junit.Test
import java.util.Properties
import java.util.prefs.Preferences

class PresetMigrationTest {
    @Test
    fun legacyPodAssignmentAndPresetsPreserveUnknownSettings() {
        val file = kotlin.io.path.createTempFile("legacy-settings", ".properties").toFile()
        val preset = PresetStore.createFromTemplate(PresetTemplate.RIDE_WEB_SLIDES, PlatformCapabilities.desktop())
        val old = Properties().apply {
            setProperty("pod_assignment", "LEGACY_SHARED")
            setProperty("named_presets_v1", PresetStore.serialize(listOf(preset), preset.id))
            setProperty("unknown_setting", "keep")
        }
        file.writer().use { old.store(it, null) }
        try {
            val loaded = ButtonMappingStore.loadConfiguration(file)
            assertEquals(PodAssignment.SINGLE_CONTROLLER, loaded.podAssignment)
            assertEquals(listOf(preset), loaded.presets)
            assertEquals(preset.id, loaded.activePresetId)

            ButtonMappingStore.saveConfiguration(loaded.copy(startMinimizedToTray = true), file)
            val reloaded = ButtonMappingStore.loadConfiguration(file)
            assertEquals(loaded.presets, reloaded.presets)
            assertTrue(reloaded.startMinimizedToTray)
            val saved = Properties().apply { file.reader().use { load(it) } }
            assertEquals("keep", saved.getProperty("unknown_setting"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun preferencesFallbackRoundTrip() {
        val prefs = Preferences.userRoot().node("/com/ankiride/test-${System.nanoTime()}")
        try {
            val presets = (1..8).map { MappingPreset("id$it", "Preset $it", AnkiTarget.ANKICONNECT, PresetStore.standardBindings()) }
            val config = AppConfiguration(presets = presets, activePresetId = presets.last().id)
            ButtonMappingStore.writeFallbackPresets(prefs, config)
            assertEquals(presets to presets.last().id, ButtonMappingStore.readFallbackPresets(prefs))
        } finally {
            prefs.removeNode()
        }
    }

    @Test
    fun failedFileSaveUsesNewerFallbackUntilFileSaveSucceeds() {
        val prefs = Preferences.userRoot().node("/com/ankiride/test-${System.nanoTime()}")
        val settings = kotlin.io.path.createTempDirectory("settings-failure").toFile()
        val originalProvider = ButtonMappingStore.fallbackPreferences
        try {
            ButtonMappingStore.fallbackPreferences = { prefs }
            val preset = MappingPreset("saved", "Unsaved file", AnkiTarget.ANKICONNECT, PresetStore.standardBindings())
            val config = AppConfiguration(
                presets = listOf(preset),
                activePresetId = preset.id,
                defaultPresetId = preset.id,
                startMinimizedToTray = true,
                ubuntuAnkiConnectHost = "10.1.2.3"
            )
            ButtonMappingStore.saveConfiguration(config, settings) // Directory cannot be written as properties file.
            val fallback = ButtonMappingStore.loadConfiguration(settings)
            assertEquals(config.presets, fallback.presets)
            assertEquals(config.defaultPresetId, fallback.defaultPresetId)
            assertTrue(fallback.startMinimizedToTray)
            assertEquals(config.ubuntuAnkiConnectHost, fallback.ubuntuAnkiConnectHost)
            assertTrue(settings.delete())
            val recovered = config.copy(ubuntuAnkiConnectHost = "10.2.3.4")
            ButtonMappingStore.saveConfiguration(recovered, settings)
            assertEquals("10.2.3.4", ButtonMappingStore.loadConfiguration(settings).ubuntuAnkiConnectHost)
        } finally {
            ButtonMappingStore.fallbackPreferences = originalProvider
            settings.delete()
            prefs.removeNode()
        }
    }
}
