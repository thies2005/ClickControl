package com.ankiride.bridge.ble

import com.ankiride.bridge.core.ButtonGestureEvent
import com.ankiride.bridge.core.ClickButton
import com.ankiride.bridge.core.PressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwiftGestureEngineTest {

    @Test
    fun testSingleClickDetection() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        // Button A pressed at t=1000
        engine.processButtonStates(mapOf(ClickButton.A to true), nowMs = 1000L)
        assertEquals(0, events.size)

        // Button A released at t=1100 (100ms hold < 450ms)
        engine.processButtonStates(mapOf(ClickButton.A to false), nowMs = 1100L)

        assertEquals(1, events.size)
        assertEquals(ClickButton.A, events[0].button)
        assertEquals(PressType.SINGLE_CLICK, events[0].type)
        assertEquals(1100L, events[0].timestampMs)
    }

    @Test
    fun testSingleClickBoundary449ms() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        engine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 1000L)
        // 449ms hold: still < 450ms
        engine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 1449L)
        assertEquals(0, events.size)

        // Releasing at 449ms triggers SINGLE_CLICK
        engine.processButtonStates(mapOf(ClickButton.B to false), nowMs = 1449L)
        assertEquals(1, events.size)
        assertEquals(ClickButton.B, events[0].button)
        assertEquals(PressType.SINGLE_CLICK, events[0].type)
    }

    @Test
    fun testLongPressExactThreshold450ms() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        engine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1000L)
        assertEquals(0, events.size)

        // Exactly 450ms hold -> triggers LONG_PRESS while held
        engine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1450L)
        assertEquals(1, events.size)
        assertEquals(ClickButton.Z, events[0].button)
        assertEquals(PressType.LONG_PRESS, events[0].type)
        assertEquals(1450L, events[0].timestampMs)
    }

    @Test
    fun testReleaseSuppressionAfterLongPress() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        // Button Z pressed at t=1000
        engine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1000L)

        // At t=1500 (500ms >= 450ms), emits LONG_PRESS
        engine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1500L)
        assertEquals(1, events.size)
        assertEquals(PressType.LONG_PRESS, events[0].type)

        // Still holding at t=1600: no duplicate LONG_PRESS
        engine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1600L)
        assertEquals(1, events.size)

        // Released at t=1700: should NOT emit SINGLE_CLICK
        engine.processButtonStates(mapOf(ClickButton.Z to false), nowMs = 1700L)
        assertEquals(1, events.size)
    }

    @Test
    fun testDebounceWindowSuppressesRapidClicks() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        // First click: press at t=1000, release at t=1050 (emitted at 1050)
        engine.processButtonStates(mapOf(ClickButton.A to true), nowMs = 1000L)
        engine.processButtonStates(mapOf(ClickButton.A to false), nowMs = 1050L)
        assertEquals(1, events.size)

        // Second click within debounce window (<120ms): press at t=1070, release at t=1120
        // (1120 - 1050 = 70ms < 120ms) -> should be suppressed
        engine.processButtonStates(mapOf(ClickButton.A to true), nowMs = 1070L)
        engine.processButtonStates(mapOf(ClickButton.A to false), nowMs = 1120L)
        assertEquals(1, events.size)

        // Third click outside debounce window: press at t=1200, release at t=1250
        // (1250 - 1050 = 200ms >= 120ms) -> should be emitted
        engine.processButtonStates(mapOf(ClickButton.A to true), nowMs = 1200L)
        engine.processButtonStates(mapOf(ClickButton.A to false), nowMs = 1250L)
        assertEquals(2, events.size)
        assertEquals(1250L, events[1].timestampMs)
    }

    @Test
    fun testMultipleButtonsConcurrentTracking() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        // Button A and B pressed at t=1000
        engine.processButtonStates(
            mapOf(
                ClickButton.A to true,
                ClickButton.B to true
            ),
            nowMs = 1000L
        )

        // A released at t=1150 (hold 150ms -> single click)
        engine.processButtonStates(
            mapOf(
                ClickButton.A to false,
                ClickButton.B to true
            ),
            nowMs = 1150L
        )
        assertEquals(1, events.size)
        assertEquals(ClickButton.A, events[0].button)
        assertEquals(PressType.SINGLE_CLICK, events[0].type)

        // B held to t=1500 (hold 500ms >= 450ms -> long press)
        engine.processButtonStates(
            mapOf(
                ClickButton.A to false,
                ClickButton.B to true
            ),
            nowMs = 1500L
        )
        assertEquals(2, events.size)
        assertEquals(ClickButton.B, events[1].button)
        assertEquals(PressType.LONG_PRESS, events[1].type)

        // Now button Z is pressed at t=1500
        engine.processButtonStates(
            mapOf(
                ClickButton.A to false,
                ClickButton.B to true,
                ClickButton.Z to true
            ),
            nowMs = 1500L
        )
        assertEquals(2, events.size)

        // Z held to t=2000 (hold 500ms >= 450ms -> long press)
        engine.processButtonStates(
            mapOf(
                ClickButton.A to false,
                ClickButton.B to true,
                ClickButton.Z to true
            ),
            nowMs = 2000L
        )
        assertEquals(3, events.size)
        assertEquals(ClickButton.Z, events[2].button)
        assertEquals(PressType.LONG_PRESS, events[2].type)

        // B and Z both released at t=2100 -> suppressed (no single clicks)
        engine.processButtonStates(
            mapOf(
                ClickButton.A to false,
                ClickButton.B to false,
                ClickButton.Z to false
            ),
            nowMs = 2100L
        )
        assertEquals(3, events.size)
    }

    @Test
    fun testReleaseWithoutPriorPressDoesNothing() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine { events.add(it) }

        engine.processButtonStates(mapOf(ClickButton.Y to false), nowMs = 1000L)
        assertTrue(events.isEmpty())
    }

    @Test
    fun holdToRepeatEmitsImmediateClickAndPeriodicRepeatsWithoutLongPressOrReleaseDuplicate() {
        val events = mutableListOf<ButtonGestureEvent>()
        val engine = ZwiftGestureEngine(
            isRepeatEnabled = { it == ClickButton.Y },
            onGestureDetected = { events.add(it) }
        )

        engine.processButtonStates(mapOf(ClickButton.Y to true), nowMs = 1000L)
        assertEquals(1, events.size)
        assertEquals(PressType.SINGLE_CLICK, events[0].type)

        // Held before 450ms threshold: no repeat yet
        engine.processButtonStates(mapOf(ClickButton.Y to true), nowMs = 1400L)
        assertEquals(1, events.size)

        // Past threshold at 1500ms: emits repeat SINGLE_CLICK
        engine.processButtonStates(mapOf(ClickButton.Y to true), nowMs = 1500L)
        assertEquals(2, events.size)
        assertEquals(PressType.SINGLE_CLICK, events[1].type)

        // Next repeat interval (+120ms = 1620ms)
        engine.processButtonStates(mapOf(ClickButton.Y to true), nowMs = 1625L)
        assertEquals(3, events.size)

        // Release at 1700ms: no duplicate click and no LONG_PRESS
        engine.processButtonStates(mapOf(ClickButton.Y to false), nowMs = 1700L)
        assertEquals(3, events.size)
        assertTrue(events.all { it.type == PressType.SINGLE_CLICK })
    }
}
