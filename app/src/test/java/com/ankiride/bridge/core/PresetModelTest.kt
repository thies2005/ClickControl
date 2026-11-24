package com.ankiride.bridge.core

import org.junit.Assert.*
import org.junit.Test

class PresetModelTest {
    @Test fun roundTripAndOwnershipAreIndependent() {
        val key = PresetBindingKey(ClickButton.SHIFT_DOWN_RIGHT, PressType.LONG_PRESS)
        val one = MappingPreset("one", "Ride | 中文", AnkiTarget.ANKIDROID, mapOf(key to MappedTargetAction.MW_CAMERA))
        val two = one.copy(id = "two", name = "Copy", ankiTarget = AnkiTarget.ANKICONNECT)
        val encoded = PresetStore.serialize(listOf(one, two), two.id)
        assertEquals(listOf(one, two) to "two", PresetStore.deserialize(encoded))
        val config = AppConfiguration(presets = listOf(one, two), activePresetId = one.id)
        assertEquals(MappedTargetAction.MW_CAMERA, PresetStore.resolve(config, key.button, key.pressType))
        assertEquals(MappedTargetAction.MW_CAMERA, PresetStore.resolve(config.copy(podAssignment = PodAssignment.RIGHT_CONTROLLER_ONLY), key.button, key.pressType))
        assertEquals(MappedTargetAction.MW_CAMERA, PresetStore.resolve(config.copy(podAssignment = PodAssignment.BOTH_CONTROLLERS), key.button, key.pressType))
        assertEquals(AnkiTarget.ANKICONNECT, PresetStore.active(config.copy(activePresetId = two.id))?.ankiTarget)
        assertEquals(MappedTargetAction.NONE, PresetStore.resolve(config, ClickButton.Y, PressType.SINGLE_CLICK))
    }

    @Test fun malformedDataIsRejected() {
        assertNull(PresetStore.deserialize("1|bad\ninvalid"))
        assertNull(PresetStore.deserialize("2|unsupported"))
    }

    @Test fun canonicalAliasesSharePhysicalBinding() {
        val preset = MappingPreset("one", "test", AnkiTarget.ANKICONNECT, mapOf(PresetBindingKey(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK) to MappedTargetAction.ANKI_UNDO))
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        assertEquals(MappedTargetAction.ANKI_UNDO, PresetStore.resolve(config, ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
    }
}
