package com.ankiride.bridge.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Properties

class DesktopButtonMappingStoreTest {

    @Test
    fun testSaveAndLoadConfigurationRoundTrip() {
        val tempFile = File.createTempFile("test_settings", ".properties")
        try {
            val (presets, firstId) = PresetStore.defaultStarterPresets(PlatformCapabilities.desktop())
            val originalConfig = AppConfiguration(
                presets = presets,
                activePresetId = presets.last().id,
                defaultPresetId = firstId,
                podAssignment = PodAssignment.BOTH_CONTROLLERS,
                autoStartOnControllerWake = false,
                startMinimizedToTray = true,
                floatingHudPillEnabled = true,
                hapticFeedbackEnabled = false,
                audioFeedbackEnabled = true,
                ubuntuAnkiConnectHost = "192.168.1.150",
                ubuntuAnkiConnectPort = 9999,
                leftPodAutoReconnectSec = 30,
                openBikeServerPort = 25000,
                pinnedRightPodMac = "AA:BB:CC:11:22:33",
                pinnedLeftPodMac = "DD:EE:FF:44:55:66"
            )

            ButtonMappingStore.saveConfiguration(originalConfig, tempFile)
            assertTrue(tempFile.exists())

            val loadedConfig = ButtonMappingStore.loadConfiguration(tempFile)
            assertEquals(originalConfig.podAssignment, loadedConfig.podAssignment)
            assertEquals(originalConfig.activePresetId, loadedConfig.activePresetId)
            assertEquals(originalConfig.defaultPresetId, loadedConfig.defaultPresetId)
            assertEquals(originalConfig.autoStartOnControllerWake, loadedConfig.autoStartOnControllerWake)
            assertEquals(originalConfig.startMinimizedToTray, loadedConfig.startMinimizedToTray)
            assertEquals(originalConfig.floatingHudPillEnabled, loadedConfig.floatingHudPillEnabled)
            assertEquals(originalConfig.audioFeedbackEnabled, loadedConfig.audioFeedbackEnabled)
            assertEquals(originalConfig.ubuntuAnkiConnectHost, loadedConfig.ubuntuAnkiConnectHost)
            assertEquals(originalConfig.ubuntuAnkiConnectPort, loadedConfig.ubuntuAnkiConnectPort)
            assertEquals(originalConfig.leftPodAutoReconnectSec, loadedConfig.leftPodAutoReconnectSec)
            assertEquals(originalConfig.openBikeServerPort, loadedConfig.openBikeServerPort)
            assertEquals(originalConfig.pinnedRightPodMac, loadedConfig.pinnedRightPodMac)
            assertEquals(originalConfig.pinnedLeftPodMac, loadedConfig.pinnedLeftPodMac)
            assertEquals(originalConfig.presets, loadedConfig.presets)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testLoadConfigurationFromNonExistentFileReturnsDefaults() {
        val nonExistent = File(System.getProperty("java.io.tmpdir"), "non_existent_settings_${System.nanoTime()}.properties")
        val config = ButtonMappingStore.loadConfiguration(nonExistent)
        assertNotNull(config)
        assertEquals(PodAssignment.SINGLE_CONTROLLER, config.podAssignment)
        assertFalse(config.startMinimizedToTray)
        assertFalse(config.floatingHudPillEnabled)
        assertFalse(config.hapticFeedbackEnabled)
        assertFalse(config.audioFeedbackEnabled)
        assertTrue(config.autoStartOnControllerWake)
    }

    @Test
    fun legacyPodAssignmentNamesMigrateAndObsoleteKeysAreScrubbedOnSave() {
        val file = File.createTempFile("legacy-settings", ".properties")
        try {
            file.writeText(
                """
                operating_mode=MODE_5_UBUNTU_ANKICONNECT
                pod_assignment=RIGHT_ANKI_LEFT_BIKE
                right_pod_only=false
                use_z_for_shift_down=true
                touch_fallback=true
                MODE_1_TABLET_SPLIT_SCREEN|Y|SINGLE_CLICK=ANKI_UNDO
                custom_unknown_key=keep_me
                """.trimIndent()
            )
            val loaded = ButtonMappingStore.loadConfiguration(file)
            assertEquals(PodAssignment.BOTH_CONTROLLERS, loaded.podAssignment)

            ButtonMappingStore.saveConfiguration(loaded.copy(podAssignment = PodAssignment.SINGLE_CONTROLLER), file)
            val props = Properties().apply { file.reader().use { load(it) } }
            assertNull(props.getProperty("operating_mode"))
            assertNull(props.getProperty("right_pod_only"))
            assertNull(props.getProperty("touch_fallback"))
            assertNull(props.getProperty("MODE_1_TABLET_SPLIT_SCREEN|Y|SINGLE_CLICK"))
            assertEquals("keep_me", props.getProperty("custom_unknown_key"))
            assertEquals(PodAssignment.SINGLE_CONTROLLER.name, props.getProperty("pod_assignment"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun defaultUbuntuAnkiConnectHostIsLocalhost() {
        assertEquals("127.0.0.1", AppConfiguration().ubuntuAnkiConnectHost)
    }

    @Test
    fun freshDesktopLoadIncludesStarterTemplatesWithoutAnkiDroid() {
        val nonExistent = File(System.getProperty("java.io.tmpdir"), "fresh_desktop_${System.nanoTime()}.properties")
        val config = ButtonMappingStore.loadConfiguration(nonExistent)
        assertTrue(config.presets.size >= 6)
        assertTrue(config.presets.none { it.ankiTarget == AnkiTarget.ANKIDROID })
        assertTrue(config.presets.any { it.name == "Presentations" })
        assertTrue(config.presets.any { it.name == "Document Scrolling" })
        assertTrue(config.presets.any { it.name == "MyWhoosh Shifting" })
        assertTrue(config.presets.any { it.name == "Ride + Web, Docs & Slides" })
        assertTrue(config.presets.any { it.ankiTarget == AnkiTarget.ANKIWEB })
    }
}
