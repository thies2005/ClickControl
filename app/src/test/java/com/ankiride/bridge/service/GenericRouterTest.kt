package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenericRouterTest {
    @Test fun canonicalBindingRoutesOnlyValidMatchingPayload() = runTest {
        val key = PresetBindingKey(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK)
        val payload = InputConfig(left = 0.25f, top = 0.25f, width = 0.5f, height = 0.5f)
        var preset = MappingPreset("p", "Preset", AnkiTarget.ANKIDROID, mapOf(key to MappedTargetAction.SCREEN_CLICK), mapOf(key to payload))
        val calls = mutableListOf<InputConfig>()
        var running = true
        val router = ActionRouter(
            scope = this,
            configSupplier = { AppConfiguration(presets = listOf(preset), activePresetId = "p") },
            ankiDispatcher = object : AnkiActionDispatcher { override fun dispatch(action: AnkiAction) = true },
            myWhooshDispatcher = object : MyWhooshActionDispatcher { override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration) = true },
            routingEnabled = { running },
            genericInputDispatcher = object : GenericInputDispatcher {
                override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean {
                    calls.add(config)
                    return true
                }
            }
        )
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.LONG_PRESS))
        preset = preset.copy(inputConfigs = emptyMap())
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        running = false
        preset = preset.copy(inputConfigs = mapOf(key to payload))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT_ALT, PressType.SINGLE_CLICK))
        assertEquals(listOf(payload), calls)
    }

    @Test fun screenScrollRoutesWithConfigAndBlocksUnsupportedAndroidInputs() = runTest {
        val key = PresetBindingKey(ClickButton.Y, PressType.SINGLE_CLICK)
        val scrollPayload = InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 4, left = .25f, top = .25f, width = .5f, height = .5f)
        val pointerPayload = InputConfig(clickAtPointer = true, scrollDirection = ScrollDirection.DOWN, scrollAmount = 4)
        val keyPayload = InputConfig(key = PortableKey.RIGHT)

        var preset = MappingPreset("p", "ScrollPreset", AnkiTarget.ANKIDROID, mapOf(key to MappedTargetAction.SCREEN_SCROLL), mapOf(key to scrollPayload))
        val dispatched = mutableListOf<Triple<MappedTargetAction, InputConfig, Boolean>>()
        val router = ActionRouter(
            scope = this,
            configSupplier = { AppConfiguration(presets = listOf(preset), activePresetId = "p") },
            ankiDispatcher = object : AnkiActionDispatcher { override fun dispatch(action: AnkiAction) = true },
            myWhooshDispatcher = object : MyWhooshActionDispatcher { override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration) = true },
            routingEnabled = { true },
            genericInputDispatcher = object : GenericInputDispatcher {
                override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean =
                    dispatch(action, config, false)

                override fun dispatch(action: MappedTargetAction, config: InputConfig, isRepeat: Boolean): Boolean {
                    dispatched.add(Triple(action, config, isRepeat))
                    return true
                }
            }
        )
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK, isRepeat = true))

        // Android blocks clickAtPointer and KEYBOARD_INPUT at the router boundary
        preset = preset.copy(inputConfigs = mapOf(key to pointerPayload))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))

        preset = preset.copy(bindings = mapOf(key to MappedTargetAction.KEYBOARD_INPUT), inputConfigs = mapOf(key to keyPayload))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))

        assertEquals(listOf(Triple(MappedTargetAction.SCREEN_SCROLL, scrollPayload, true)), dispatched)
    }
}
