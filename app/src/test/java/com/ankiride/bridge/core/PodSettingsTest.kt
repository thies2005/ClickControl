package com.ankiride.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PodSettingsTest {
    @Test fun newAndLegacySettingsMigrateSafely() {
        assertEquals(PodAssignment.SINGLE_CONTROLLER, ButtonMappingStore.podSettingsFrom(emptyMap<String, Any>()).first)
        assertEquals(PodAssignment.SINGLE_CONTROLLER, ButtonMappingStore.podSettingsFrom(mapOf("pod_assignment" to "LEGACY_SHARED")).first)
        assertEquals(PodAssignment.RIGHT_CONTROLLER_ONLY, ButtonMappingStore.podSettingsFrom(mapOf("pod_assignment" to "RIGHT_ANKI_ONLY")).first)
        assertEquals(PodAssignment.BOTH_CONTROLLERS, ButtonMappingStore.podSettingsFrom(mapOf("pod_assignment" to "RIGHT_ANKI_LEFT_BIKE")).first)
    }

    @Test fun assignmentAndPinsRoundTrip() {
        val config = AppConfiguration(podAssignment = PodAssignment.BOTH_CONTROLLERS, pinnedRightPodMac = " aa ", pinnedLeftPodMac = "bb")
        assertEquals(Triple(PodAssignment.BOTH_CONTROLLERS, "aa", "bb"), ButtonMappingStore.podSettingsFrom(ButtonMappingStore.podSettingsFor(config)))
    }
}
