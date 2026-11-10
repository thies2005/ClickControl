package com.ankiride.bridge.core

import org.junit.Assert.*
import org.junit.Test

class GenericPresetTest {
    @Test fun oldAndNewFormatsPreserveAllPresetsAndBindings() {
        val old = "1|one\none|T25l|ANKICONNECT|Y,SINGLE_CLICK,ANKI_UNDO\ntwo|VHdv|ANKIDROID|A,LONG_PRESS,MW_CAMERA\n"
        val (presets, active) = PresetStore.deserialize(old)!!
        assertEquals("one", active)
        val key = PresetBindingKey(ClickButton.Y, PressType.SINGLE_CLICK)
        val other = PresetBindingKey(ClickButton.Y, PressType.LONG_PRESS)
        val changed = presets[0].copy(bindings = presets[0].bindings + (key to MappedTargetAction.SCREEN_CLICK) + (other to MappedTargetAction.KEYBOARD_INPUT), inputConfigs = mapOf(key to InputConfig(.1f,.2f,.3f,.4f), other to InputConfig(key = PortableKey.A, ctrl = true)))
        val all = listOf(changed, presets[1])
        assertEquals(all to active, PresetStore.deserialize(PresetStore.serialize(all, active)))
        assertEquals(MappedTargetAction.MW_CAMERA, presets[1].bindings[PresetBindingKey(ClickButton.A, PressType.LONG_PRESS)])
    }
    @Test fun versionTwoDefaultsToFixedAreaAndPointerRoundTrips() {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("0.1;0.2;0.3;0.4;;false;false;false;false".toByteArray())
        val old = "2|one\none|T25l|ANKICONNECT|Y,SINGLE_CLICK,SCREEN_CLICK,$payload\n"
        val preset = PresetStore.deserialize(old)!!.first.single()
        val key = PresetBindingKey(ClickButton.Y, PressType.SINGLE_CLICK)
        assertFalse(preset.inputConfigs[key]!!.clickAtPointer)
        val changed = preset.copy(inputConfigs = mapOf(key to InputConfig(clickAtPointer = true)))
        assertEquals(changed, PresetStore.deserialize(PresetStore.serialize(listOf(changed), "one"))!!.first.single())
    }
    @Test fun unfinishedDraftPayloadSurvivesSerializationButCannotRun() {
        val key = PresetBindingKey(ClickButton.Y, PressType.SINGLE_CLICK)
        val preset = MappingPreset("one", "Draft", AnkiTarget.ANKICONNECT, mapOf(key to MappedTargetAction.SCREEN_CLICK), mapOf(key to InputConfig(Float.NaN, .1f, .2f, .2f)))
        val restored = PresetStore.deserialize(PresetStore.serialize(listOf(preset), preset.id))!!.first.single()
        assertTrue(restored.inputConfigs[key]!!.left.isNaN())
        assertFalse(restored.inputConfigs[key]!!.validFor(MappedTargetAction.SCREEN_CLICK))
    }
    @Test fun invalidPayloadOnlyDisablesItsOwnBinding() {
        val raw = "2|one\none|T25l|ANKICONNECT|Y,SINGLE_CLICK,SCREEN_CLICK,%%%|Y,LONG_PRESS,ANKI_UNDO\ntwo|VHdv|ANKIDROID|A,SINGLE_CLICK,MW_CAMERA\n"
        val presets = PresetStore.deserialize(raw)!!.first
        assertEquals(2, presets.size)
        assertTrue(presets[0].inputConfigs.isEmpty())
        assertEquals(MappedTargetAction.ANKI_UNDO, presets[0].bindings[PresetBindingKey(ClickButton.Y, PressType.LONG_PRESS)])
        assertFalse(InputConfig(Float.NaN, 0f, 1f, 1f).validFor(MappedTargetAction.SCREEN_CLICK))
        assertFalse(InputConfig(.9f, 0f, .2f, 1f).validFor(MappedTargetAction.SCREEN_CLICK))
        assertFalse(InputConfig().validFor(MappedTargetAction.KEYBOARD_INPUT))
    }
}
