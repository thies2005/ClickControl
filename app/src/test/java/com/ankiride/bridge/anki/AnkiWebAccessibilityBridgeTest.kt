package com.ankiride.bridge.anki

import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.AnkiTarget
import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.MappingPreset
import org.junit.Assert.*
import org.junit.Test

class AnkiWebAccessibilityBridgeTest {

    @Test
    fun matchesExpectedAnkiWebButtonLabelsAndRejectsLongCardText() {
        assertTrue(AnkiWebAccessibilityBridge.matchesButtonLabel("Show Answer", listOf("Show Answer")))
        assertTrue(AnkiWebAccessibilityBridge.matchesButtonLabel("Good", listOf("Good")))
        assertTrue(AnkiWebAccessibilityBridge.matchesButtonLabel("Good 10m", listOf("Good")))
        assertTrue(AnkiWebAccessibilityBridge.matchesButtonLabel("10m\nGood", listOf("Good")))

        assertFalse(AnkiWebAccessibilityBridge.matchesButtonLabel("", listOf("Good")))
        assertFalse(AnkiWebAccessibilityBridge.matchesButtonLabel(null, listOf("Good")))
        assertFalse(
            AnkiWebAccessibilityBridge.matchesButtonLabel(
                "This is a Good long sentence from a flashcard front",
                listOf("Good")
            )
        )
    }

    @Test
    fun supportsCommonAndroidBrowsersAndRejectsNullRoot() {
        assertTrue(AnkiWebAccessibilityBridge.BROWSER_PACKAGES.contains("com.android.chrome"))
        assertTrue(AnkiWebAccessibilityBridge.BROWSER_PACKAGES.contains("org.mozilla.firefox"))
        assertTrue(AnkiWebAccessibilityBridge.BROWSER_PACKAGES.contains("com.sec.android.app.sbrowser"))
        val bridge = AnkiWebAccessibilityBridge()
        assertFalse(bridge.findAndClickWebButton(null, AnkiAction.FLIP_CARD))
        assertFalse(bridge.containsAnkiWebMarker(null))
        assertTrue(AnkiWebAccessibilityBridge.isAnkiWebMarkerText("https://ankiweb.net/study/"))
        assertTrue(AnkiWebAccessibilityBridge.isAnkiWebMarkerText("ankiuser.net"))
        assertTrue(AnkiWebAccessibilityBridge.isAnkiWebMarkerText("AnkiWeb"))
        assertFalse(AnkiWebAccessibilityBridge.isAnkiWebMarkerText("docs.google.com/presentation"))
        assertFalse(AnkiWebAccessibilityBridge.isAnkiWebMarkerText(null))
    }

    @Test
    fun ankiWebTargetReturnsFalseGracefullyWithoutAccessibilityService() {
        val preset = MappingPreset("web", "AnkiWeb", AnkiTarget.ANKIWEB, emptyMap())
        val dispatcher = AnkiActionDispatcherImpl(
            configSupplier = { AppConfiguration(presets = listOf(preset), activePresetId = preset.id) },
            serviceProvider = { null }
        )
        assertFalse(dispatcher.dispatch(AnkiAction.FLIP_OR_GOOD))
    }
}
