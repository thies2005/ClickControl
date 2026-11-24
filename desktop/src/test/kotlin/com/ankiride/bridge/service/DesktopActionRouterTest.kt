package com.ankiride.bridge.service

import com.ankiride.bridge.ble.DesktopZwiftBleController
import com.ankiride.bridge.core.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopActionRouterTest {

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
    private val caps = PlatformCapabilities.desktop()

    @Before
    fun setUp() {
        ankiDispatcher = FakeAnkiDispatcher()
        myWhooshDispatcher = FakeMyWhooshDispatcher()
        DiagnosticLogger.clear()
    }

    @Test
    fun testRideAndAnkiPresetRouting() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.RIDE_AND_ANKI, caps)
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val router = DesktopActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )

        assertEquals("Ready", router.lastRoutedActionLabel.value)

        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_UP_LEFT, PressType.SINGLE_CLICK))
        assertEquals(MappedTargetAction.MW_SHIFT_UP.label, router.lastRoutedActionLabel.value)

        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK))
        assertEquals(MappedTargetAction.MW_SHIFT_DOWN.label, router.lastRoutedActionLabel.value)

        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Y, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.B, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.B, PressType.LONG_PRESS))

        router.routeGesture(ButtonGestureEvent(ClickButton.LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.RIGHT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.UP, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.DOWN, PressType.SINGLE_CLICK))

        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                MyWhooshAction.SHIFT_UP,
                MyWhooshAction.SHIFT_DOWN,
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
                AnkiAction.FLIP_OR_GOOD,
                AnkiAction.UNDO
            ),
            ankiDispatcher.dispatchedActions
        )

        val logs = DiagnosticLogger.logs.value.filter { it.category == LogCategory.ROUTER }
        assertTrue(logs.any { it.message.contains("[Ride + Anki]") })
    }

    @Test
    fun testAnkiPresetRoutingDispatchesOnlyAnki() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, caps)
        val config = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val router = DesktopActionRouter(
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

        assertTrue("Anki-only preset must not dispatch to MyWhoosh", myWhooshDispatcher.dispatchedActions.isEmpty())
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
    fun testOptionBRideWebSlidesPresetRoutesScrollKeysAndMyWhooshShiftsCleanly() = runTest {
        val preset = PresetStore.createFromTemplate(PresetTemplate.RIDE_WEB_SLIDES, caps)
        val config = AppConfiguration(
            presets = listOf(preset),
            activePresetId = preset.id,
            podAssignment = PodAssignment.BOTH_CONTROLLERS
        )
        val genericDispatched = mutableListOf<Pair<MappedTargetAction, InputConfig>>()
        val router = DesktopActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher,
            genericInputDispatcher = object : GenericInputDispatcher {
                override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean {
                    genericDispatched.add(action to config)
                    return true
                }
            }
        )

        router.routeGesture(ButtonGestureEvent(ClickButton.DOWN, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.LEFT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.RIGHT, PressType.LONG_PRESS))
        router.routeGesture(ButtonGestureEvent(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK))
        router.routeGesture(ButtonGestureEvent(ClickButton.Z, PressType.SINGLE_CLICK))

        testScheduler.advanceUntilIdle()

        assertTrue(ankiDispatcher.dispatchedActions.isEmpty())
        assertEquals(listOf(MyWhooshAction.SHIFT_UP, MyWhooshAction.SHIFT_DOWN), myWhooshDispatcher.dispatchedActions)
        assertEquals(3, genericDispatched.size)
        assertEquals(ScrollDirection.DOWN, genericDispatched[0].second.scrollDirection)
        assertEquals(PortableKey.LEFT, genericDispatched[1].second.key)
        assertEquals(PortableKey.PAGE_DOWN, genericDispatched[2].second.key)
    }

    @Test
    fun testFeedbackCueManagerAudioEnabledAndDisabled() {
        val configAudioDisabled = AppConfiguration(audioFeedbackEnabled = false)
        val configAudioEnabled = AppConfiguration(audioFeedbackEnabled = true)

        DesktopFeedbackCueManager.triggerCue(MappedTargetAction.NONE, configAudioEnabled)
        DesktopFeedbackCueManager.triggerCue(MappedTargetAction.MW_SHIFT_UP, configAudioDisabled)

        listOf(
            MappedTargetAction.MW_SHIFT_UP,
            MappedTargetAction.MW_SHIFT_DOWN,
            MappedTargetAction.ANKI_UNDO,
            MappedTargetAction.ANKI_FLIP_OR_AGAIN,
            MappedTargetAction.ANKI_FLIP_OR_GOOD,
            MappedTargetAction.ANKI_FLIP_OR_HARD,
            MappedTargetAction.MW_CAMERA
        ).forEach { target ->
            DesktopFeedbackCueManager.triggerCue(target, configAudioEnabled)
        }
    }

    @Test
    fun testEndToEndControllerToRouterDiagnosticPipeline() = runTest {
        val ankiPreset = PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, caps)
        val config = AppConfiguration(
            presets = listOf(ankiPreset),
            activePresetId = ankiPreset.id,
            podAssignment = PodAssignment.SINGLE_CONTROLLER
        )
        val controller = DesktopZwiftBleController(scope = this)
        val router = DesktopActionRouter(
            scope = this,
            configSupplier = { config },
            ankiDispatcher = ankiDispatcher,
            myWhooshDispatcher = myWhooshDispatcher
        )
        val collectJob = launch {
            controller.gestureEvents.collect { event ->
                router.routeGesture(event)
            }
        }

        val mac = "02:00:00:00:00:01"
        val parseWorkerLineMethod = DesktopZwiftBleController::class.java.getDeclaredMethod("parseWorkerLine", String::class.java)
        parseWorkerLineMethod.isAccessible = true
        parseWorkerLineMethod.invoke(controller, "STATE CONNECTED_RIGHT_ONLY $mac")

        val packetZPress = byteArrayOf(0x23, 0x08, 0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)
        controller.handleRawNotification(mac, packetZPress, nowMs = 1000L)

        val packetAllReleased = byteArrayOf(0x23, 0x08, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)
        controller.handleRawNotification(mac, packetAllReleased, nowMs = 1100L)

        testScheduler.advanceUntilIdle()

        val logs = DiagnosticLogger.logs.value
        assertTrue(logs.any { it.category == LogCategory.BLE && it.message.contains("Connected to Right Pod") })
        assertTrue(logs.any { it.category == LogCategory.PACKET && it.message.contains("0x23 packet from $mac") })
        assertTrue(logs.any { it.category == LogCategory.ROUTER && it.message.contains("->") })

        collectJob.cancel()
        controller.disconnect()
    }
}
