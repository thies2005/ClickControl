package com.ankiride.bridge.core

import com.ankiride.bridge.mywhoosh.OpenBikeProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlatformCapabilitiesAndTemplatesTest {

    @Test
    fun desktopCapabilitiesExcludeAndroidOnlyFeaturesAndRespectLinuxFlag() {
        val linux = PlatformCapabilities.desktop(isLinux = true)
        val macOrWin = PlatformCapabilities.desktop(isLinux = false)

        assertEquals(PlatformKind.DESKTOP, linux.platform)
        assertTrue(linux.supportsAnkiTarget(AnkiTarget.ANKICONNECT))
        assertTrue(linux.supportsAnkiTarget(AnkiTarget.ANKIWEB))
        assertFalse(linux.supportsAnkiTarget(AnkiTarget.ANKIDROID))
        assertEquals(AnkiTarget.ANKICONNECT, linux.normalizeAnkiTarget(AnkiTarget.ANKIDROID))

        assertTrue(linux.supportsAction(MappedTargetAction.KEYBOARD_INPUT))
        assertTrue(linux.supportsAction(MappedTargetAction.SCREEN_SCROLL))
        assertTrue(linux.supportsAction(MappedTargetAction.SCREEN_CLICK))
        assertTrue(linux.supportsPointerTargeting)
        assertFalse(linux.supportsHaptics)
        assertFalse(linux.supportsTouchCalibration)
        assertTrue(linux.supportsLinuxAutostart)
        assertFalse(macOrWin.supportsLinuxAutostart)
    }

    @Test
    fun androidCapabilitiesExcludeKeyboardInputAndPointerTargeting() {
        val android = PlatformCapabilities.android()

        assertEquals(PlatformKind.ANDROID, android.platform)
        assertTrue(android.supportsAnkiTarget(AnkiTarget.ANKIDROID))
        assertTrue(android.supportsAnkiTarget(AnkiTarget.ANKIWEB))
        assertTrue(android.supportsAnkiTarget(AnkiTarget.ANKICONNECT))

        assertFalse(android.supportsAction(MappedTargetAction.KEYBOARD_INPUT))
        assertTrue(android.supportsAction(MappedTargetAction.SCREEN_SCROLL))
        assertTrue(android.supportsAction(MappedTargetAction.SCREEN_CLICK))
        assertFalse(android.availableActions().contains(MappedTargetAction.KEYBOARD_INPUT))

        assertFalse(android.supportsPointerTargeting)
        assertTrue(android.supportsHaptics)
        assertTrue(android.supportsTouchCalibration)
        assertFalse(android.supportsLinuxAutostart)

        assertFalse(android.isValidInputConfig(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT)))
        assertFalse(android.isValidInputConfig(MappedTargetAction.SCREEN_CLICK, InputConfig(clickAtPointer = true)))
        assertFalse(android.isValidInputConfig(MappedTargetAction.SCREEN_SCROLL, InputConfig(clickAtPointer = true, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3)))
        assertTrue(android.isValidInputConfig(MappedTargetAction.SCREEN_SCROLL, InputConfig(left = 0.2f, top = 0.2f, width = 0.6f, height = 0.6f, scrollDirection = ScrollDirection.RIGHT, scrollAmount = 5)))
    }

    @Test
    fun starterTemplatesMatchEachPlatformStrictlyIncludingOptionBAndOptionC() {
        val desktop = PlatformCapabilities.desktop()
        val android = PlatformCapabilities.android()

        for (template in PresetTemplate.entries) {
            val dPreset = PresetStore.createFromTemplate(template, desktop)
            assertTrue(desktop.supportsAnkiTarget(dPreset.ankiTarget))
            dPreset.bindings.forEach { (key, action) ->
                assertTrue("Desktop template $template produced unsupported $action", desktop.supportsAction(action))
                if (action.isGenericInput) {
                    val cfg = dPreset.inputConfigs[key]
                    assertNotNull("Desktop template $template missing config for $key", cfg)
                    assertTrue(desktop.isValidInputConfig(action, cfg!!))
                }
            }

            val aPreset = PresetStore.createFromTemplate(template, android)
            assertTrue(android.supportsAnkiTarget(aPreset.ankiTarget))
            aPreset.bindings.forEach { (key, action) ->
                assertTrue("Android template $template produced unsupported $action", android.supportsAction(action))
                if (action.isGenericInput) {
                    val cfg = aPreset.inputConfigs[key]
                    assertNotNull("Android template $template missing config for $key", cfg)
                    assertTrue("Android template $template invalid config for $key", android.isValidInputConfig(action, cfg!!))
                }
            }
        }

        val bikeOnly = PresetStore.createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, android)
        assertEquals(MappedTargetAction.MW_SHIFT_UP, bikeOnly.bindings[PresetBindingKey(ClickButton.A, PressType.SINGLE_CLICK)])
        assertEquals(MappedTargetAction.MW_SHIFT_DOWN, bikeOnly.bindings[PresetBindingKey(ClickButton.Z, PressType.SINGLE_CLICK)])
        assertTrue(bikeOnly.bindings.values.none { it.ankiAction != null || it.isGenericInput })

        val rideWebDesktop = PresetStore.createFromTemplate(PresetTemplate.RIDE_WEB_SLIDES, desktop)
        assertTrue(PresetStore.activePresetUsesMyWhoosh(AppConfiguration(presets = listOf(rideWebDesktop), activePresetId = rideWebDesktop.id)))
        assertFalse(PresetStore.activePresetUsesAnki(AppConfiguration(presets = listOf(rideWebDesktop), activePresetId = rideWebDesktop.id)))
        assertEquals(MappedTargetAction.SCREEN_SCROLL, rideWebDesktop.bindings[PresetBindingKey(ClickButton.DOWN, PressType.SINGLE_CLICK)])
        assertEquals(MappedTargetAction.KEYBOARD_INPUT, rideWebDesktop.bindings[PresetBindingKey(ClickButton.RIGHT, PressType.SINGLE_CLICK)])

        val desktopAnkiWeb = PresetStore.createFromTemplate(PresetTemplate.ANKI_WEB, desktop)
        assertEquals(AnkiTarget.ANKIWEB, desktopAnkiWeb.ankiTarget)
        assertEquals(MappedTargetAction.NONE, desktopAnkiWeb.bindings[PresetBindingKey(ClickButton.Y, PressType.LONG_PRESS)])
    }

    @Test
    fun podAssignmentLegacyMigrationAndStartupDefaultPresetWork() {
        assertEquals(PodAssignment.SINGLE_CONTROLLER, PodAssignment.fromStoredName("LEGACY_SHARED"))
        assertEquals(PodAssignment.RIGHT_CONTROLLER_ONLY, PodAssignment.fromStoredName("RIGHT_ANKI_ONLY"))
        assertEquals(PodAssignment.BOTH_CONTROLLERS, PodAssignment.fromStoredName("RIGHT_ANKI_LEFT_BIKE"))
        assertEquals(PodAssignment.SINGLE_CONTROLLER, PodAssignment.fromStoredName("SINGLE_CONTROLLER"))
        assertNull(PodAssignment.fromStoredName("UNKNOWN"))

        val (starters, firstId) = PresetStore.defaultStarterPresets(PlatformCapabilities.desktop())
        val lastId = starters.last().id
        val appliedDefault = PresetStore.applyStartupDefaultPreset(
            AppConfiguration(presets = starters, activePresetId = firstId, defaultPresetId = lastId)
        )
        assertEquals(lastId, appliedDefault.activePresetId)

        val keepLastUsed = PresetStore.applyStartupDefaultPreset(
            AppConfiguration(presets = starters, activePresetId = lastId, defaultPresetId = "missing")
        )
        assertEquals(lastId, keepLastUsed.activePresetId)
    }

    @Test
    fun openBikeProtocolAndDiagnosticLoggerFileRotationWork() {
        assertArrayEquals(byteArrayOf(0x02, 0x64, 0x01), OpenBikeProtocol.deviceStatusPacket())
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x01), OpenBikeProtocol.buttonPacket(MyWhooshAction.SHIFT_UP, pressed = true))
        val appId = "MyWhoosh".toByteArray(Charsets.UTF_8)
        val appVer = "5.7.0".toByteArray(Charsets.UTF_8)
        val packet = byteArrayOf(0x04, 0x01, appId.size.toByte(), *appId, appVer.size.toByte(), *appVer, 0x00)
        assertEquals("MyWhoosh v5.7.0", OpenBikeProtocol.parseAppInfo(packet))

        val tmpDir = kotlin.io.path.createTempDirectory("cc-log-test").toFile()
        val logFile = File(tmpDir, "clickcontrol.log")
        try {
            DiagnosticLogger.clear()
            DiagnosticLogger.configureLogFile(logFile, maxBytes = 4096L)
            repeat(80) { i ->
                DiagnosticLogger.warn(LogCategory.SYSTEM, "Rotating diagnostic line #$i payload padding 0123456789abcdef")
            }
            assertTrue(logFile.exists())
            assertTrue(File(tmpDir, "clickcontrol.log.1").exists())
            assertNotNull(DiagnosticLogger.lastIssue.value)
            assertTrue(DiagnosticLogger.readRecentFileTail(5).contains("Rotating diagnostic line"))
            DiagnosticLogger.dismissLastIssue()
            assertNull(DiagnosticLogger.lastIssue.value)
        } finally {
            DiagnosticLogger.configureLogFile(null)
            DiagnosticLogger.clear()
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun ankiWebTargetExcludesUndoFromAvailableActionsAndRoundTrips() {
        val desktop = PlatformCapabilities.desktop()
        val android = PlatformCapabilities.android()
        assertFalse(desktop.availableActions(AnkiTarget.ANKIWEB).contains(MappedTargetAction.ANKI_UNDO))
        assertFalse(android.availableActions(AnkiTarget.ANKIWEB).contains(MappedTargetAction.ANKI_UNDO))
        assertTrue(desktop.availableActions(AnkiTarget.ANKICONNECT).contains(MappedTargetAction.ANKI_UNDO))
        assertTrue(android.availableActions(AnkiTarget.ANKIDROID).contains(MappedTargetAction.ANKI_UNDO))

        val preset = PresetStore.createFromTemplate(PresetTemplate.ANKI_WEB, android)
        val encoded = PresetStore.serialize(listOf(preset), preset.id)
        val restored = PresetStore.deserialize(encoded)
        assertNotNull(restored)
        assertEquals(AnkiTarget.ANKIWEB, restored!!.first.single().ankiTarget)
        assertEquals(preset, restored.first.single())
    }
}
