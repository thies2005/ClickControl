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

    @Test fun scrollRepeatAndExtendedKeysRoundTripCleanly() {
        val scrollKey = PresetBindingKey(ClickButton.Y, PressType.SINGLE_CLICK)
        val navKey = PresetBindingKey(ClickButton.B, PressType.SINGLE_CLICK)
        val scrollConfig = InputConfig(
            scrollDirection = ScrollDirection.LEFT,
            scrollAmount = 7,
            clickAtPointer = true,
            repeatWhileHeld = true
        )
        val navConfig = InputConfig(key = PortableKey.PAGE_DOWN, repeatWhileHeld = true)
        val preset = MappingPreset(
            "p1",
            "Presentation",
            AnkiTarget.ANKIDROID,
            mapOf(scrollKey to MappedTargetAction.SCREEN_SCROLL, navKey to MappedTargetAction.KEYBOARD_INPUT),
            mapOf(scrollKey to scrollConfig, navKey to navConfig)
        )
        val (restored, active) = PresetStore.deserialize(PresetStore.serialize(listOf(preset), "p1"))!!
        assertEquals("p1", active)
        assertEquals(preset, restored.single())
        assertTrue(PresetStore.isRepeatEnabled(AppConfiguration(presets = restored, activePresetId = "p1"), ClickButton.Y))
        assertTrue(PresetStore.isRepeatEnabled(AppConfiguration(presets = restored, activePresetId = "p1"), ClickButton.B))
        assertFalse(PresetStore.isRepeatEnabled(AppConfiguration(presets = restored, activePresetId = "p1"), ClickButton.Z))
        assertFalse(scrollConfig.copy(scrollAmount = 0).validFor(MappedTargetAction.SCREEN_SCROLL))
        assertFalse(scrollConfig.copy(scrollAmount = 21).validFor(MappedTargetAction.SCREEN_SCROLL))
        assertFalse(InputConfig(left = 0.25f, top = 0.25f, width = 2.0f, height = 0.5f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3).validFor(MappedTargetAction.SCREEN_SCROLL))
        assertTrue(InputConfig(left = 0.25f, top = 0.25f, width = 0.5f, height = 0.5f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3).validFor(MappedTargetAction.SCREEN_SCROLL))
        for (k in listOf(PortableKey.PAGE_UP, PortableKey.PAGE_DOWN, PortableKey.HOME, PortableKey.END)) {
            assertTrue(InputConfig(key = k).validFor(MappedTargetAction.KEYBOARD_INPUT))
        }
    }
}
