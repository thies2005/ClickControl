package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionRouterTest {

    private class FakeAnkiDispatcher : AnkiActionDispatcher {
        val dispatchedActions = mutableListOf<AnkiAction>()
        override fun dispatch(action: AnkiAction): Boolean {
            dispatchedActions.add(action)
            return true
        }
    }

    private class FakeMyWhooshDispatcher : MyWhooshActionDispatcher {
        val dispatchedActions = mutableListOf<MyWhooshAction>()
        override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration): Boolean {
            dispatchedActions.add(action)
            return true
        }
    }

    private lateinit var ankiDispatcher: FakeAnkiDispatcher
    private lateinit var myWhooshDispatcher: FakeMyWhooshDispatcher
    private val caps = PlatformCapabilities.android()

    @Before
    fun setUp() {
        ankiDispatcher = FakeAnkiDispatcher()
        myWhooshDispatcher = FakeMyWhooshDispatcher()
    }

    @Test
    fun testRideAndAnkiTemplateRouting() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.RIDE_AND_ANKI, caps)
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val router = ActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )

        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_UP_LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.RIGHT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.UP, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.DOWN, PressType.SINGLE_CLICK))

        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.Z, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.B, PressType.SINGLE_CLICK))

        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                MyWhooshAction.SHIFT_UP,
                MyWhooshAction.SHIFT_DOWN,
                MyWhooshAction.SHIFT_UP,
                MyWhooshAction.STEER_LEFT,
                MyWhooshAction.STEER_RIGHT,
                MyWhooshAction.TOGGLE_CAMERA,
                MyWhooshAction.EMOTE
            ),
            myWhooshDispatcher.dispatchedActions
        )
        assertEquals(
            listOf(
                AnkiAction.FLIP_OR_AGAIN,
                AnkiAction.UNDO,
                AnkiAction.FLIP_OR_HARD,
                AnkiAction.FLIP_OR_GOOD,
                AnkiAction.FLIP_OR_EASY,
                AnkiAction.FLIP_OR_GOOD
            ),
            ankiDispatcher.dispatchedActions
        )
    }

    @Test
    fun testAnkiLocalTemplateRoutesNoMyWhooshActions() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, caps)
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val router = ActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )

        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.Z, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.RIGHT, PressType.SINGLE_CLICK))

        testScheduler.advanceUntilIdle()

        assertTrue(myWhooshDispatcher.dispatchedActions.isEmpty())
        assertEquals(
            listOf(
                AnkiAction.FLIP_OR_GOOD,
                AnkiAction.FLIP_OR_EASY,
                AnkiAction.FLIP_OR_AGAIN,
                AnkiAction.UNDO,
                AnkiAction.FLIP_OR_HARD,
                AnkiAction.UNDO,
                AnkiAction.FLIP_CARD
            ),
            ankiDispatcher.dispatchedActions
        )
    }

    @Test
    fun testMyWhooshShiftingTemplateRoutesNoAnkiActions() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, caps)
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val router = ActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )

        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Z, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_UP_LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.RIGHT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.LONG_PRESS))

        testScheduler.advanceUntilIdle()

        assertTrue(ankiDispatcher.dispatchedActions.isEmpty())
        assertEquals(
            listOf(
                MyWhooshAction.SHIFT_UP,
                MyWhooshAction.SHIFT_DOWN,
                MyWhooshAction.SHIFT_UP,
                MyWhooshAction.SHIFT_DOWN,
                MyWhooshAction.STEER_LEFT,
                MyWhooshAction.STEER_RIGHT,
                MyWhooshAction.TOGGLE_CAMERA,
                MyWhooshAction.HUD_TOGGLE
            ),
            myWhooshDispatcher.dispatchedActions
        )
    }

    @Test
    fun testDynamicConfigSupplierSwitchesPresets() = runTest {
        val ankiPreset = PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, caps)
        val bikePreset = PresetStore.createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, caps)
        var currentConfig = AppConfiguration(presets = listOf(ankiPreset, bikePreset), activePresetId = ankiPreset.id)
        val router = ActionRouter(
            scope = this,
            configSupplier = { currentConfig },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )

        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.SINGLE_CLICK))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(AnkiAction.FLIP_OR_GOOD), ankiDispatcher.dispatchedActions)

        currentConfig = currentConfig.copy(activePresetId = bikePreset.id)
        router.routeGesture(ButtonGestureEvent(ClickButton.A, PressType.SINGLE_CLICK))
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(MyWhooshAction.SHIFT_UP), myWhooshDispatcher.dispatchedActions)
    }
}
