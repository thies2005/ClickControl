package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenericRouterTest {
    @Test fun canonicalBindingRoutesOnlyValidMatchingPayload() = runTest {
        val key = PresetBindingKey(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK)
        val payload = InputConfig(key = PortableKey.A, ctrl = true)
        var preset = MappingPreset("p", "Preset", AnkiTarget.ANKICONNECT, mapOf(key to MappedTargetAction.KEYBOARD_INPUT), mapOf(key to payload))
        val calls = mutableListOf<InputConfig>()
        var running = true
        val router = ActionRouter(scope = this, configSupplier = { AppConfiguration(presets = listOf(preset), activePresetId = "p") },
            ankiDispatcher = object : AnkiActionDispatcher { override fun dispatch(action: AnkiAction) = true },
            myWhooshDispatcher = object : MyWhooshActionDispatcher { override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration) = true },
            routingEnabled = { running },
            genericInputDispatcher = object : GenericInputDispatcher { override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean { calls.add(config); return true } })
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.LONG_PRESS))
        preset = preset.copy(inputConfigs = emptyMap())
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        running = false
        preset = preset.copy(inputConfigs = mapOf(key to payload))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        assertEquals(listOf(payload), calls)
    }
}
