package com.ankiride.bridge.ble

import com.ankiride.bridge.core.ButtonGestureEvent
import com.ankiride.bridge.core.ClickButton
import com.ankiride.bridge.core.PressType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZwiftGestureEngineCoreTest {

    @Test
    fun repeatReleaseCallbackFiresAndMarksRepeatTicks() {
        val events = mutableListOf<ButtonGestureEvent>()
        val releasedButtons = mutableListOf<ClickButton>()
        val engine = ZwiftGestureEngine(
            isRepeatEnabled = { it == ClickButton.B },
            onRepeatReleased = { releasedButtons.add(it) }
        ) { events.add(it) }

        // Initial press emits isRepeat = false
        engine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 1000L)
        assertEquals(1, events.size)
        assertFalse(events[0].isRepeat)

        // Past threshold emits repeat ticks with isRepeat = true
        engine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 1500L)
        engine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 1630L)
        assertEquals(3, events.size)
        assertTrue(events[1].isRepeat)
        assertTrue(events[2].isRepeat)

        // Release invokes onRepeatReleased immediately
        engine.processButtonStates(mapOf(ClickButton.B to false), nowMs = 1700L)
        assertEquals(listOf(ClickButton.B), releasedButtons)

        // Non-repeat button release does not trigger onRepeatReleased
        engine.processButtonStates(mapOf(ClickButton.A to true), nowMs = 2000L)
        engine.processButtonStates(mapOf(ClickButton.A to false), nowMs = 2100L)
        assertEquals(listOf(ClickButton.B), releasedButtons)
    }
}
