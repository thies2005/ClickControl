package com.ankiride.bridge.anki

import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.PortableKey
import org.junit.Assert.*
import org.junit.Test

class AnkiWebKeyboardBridgeTest {

    @Test
    fun flipsWithSpaceRespectsGuardAndGradesWithDigits() {
        val sent = mutableListOf<PortableKey>()
        val bridge = AnkiWebKeyboardBridge(sendKey = { sent.add(it); true })

        // First grading press sends Space to show answer
        assertTrue(bridge.dispatch(AnkiAction.FLIP_OR_GOOD, nowMs = 1000L))
        assertTrue(bridge.isAnswerVisible)
        assertEquals(listOf(PortableKey.SPACE), sent)

        // Quick second press inside 350ms guard is ignored
        assertFalse(bridge.dispatch(AnkiAction.FLIP_OR_GOOD, nowMs = 1200L))
        assertTrue(bridge.isAnswerVisible)
        assertEquals(listOf(PortableKey.SPACE), sent)

        // Second press after 350ms sends '3' (Good) and resets visibility
        assertTrue(bridge.dispatch(AnkiAction.FLIP_OR_GOOD, nowMs = 1400L))
        assertFalse(bridge.isAnswerVisible)
        assertEquals(listOf(PortableKey.SPACE, PortableKey.DIGIT_3), sent)
    }

    @Test
    fun supportsAllEaseButtonsAndRejectsUndo() {
        val sent = mutableListOf<PortableKey>()
        val bridge = AnkiWebKeyboardBridge(sendKey = { sent.add(it); true })

        val pairs = listOf(
            AnkiAction.FLIP_OR_AGAIN to PortableKey.DIGIT_1,
            AnkiAction.FLIP_OR_HARD to PortableKey.DIGIT_2,
            AnkiAction.FLIP_OR_GOOD to PortableKey.DIGIT_3,
            AnkiAction.FLIP_OR_EASY to PortableKey.DIGIT_4
        )
        var t = 1000L
        for ((action, expectedKey) in pairs) {
            assertTrue(bridge.dispatch(AnkiAction.FLIP_CARD, nowMs = t))
            t += 400L
            assertTrue(bridge.dispatch(action, nowMs = t))
            t += 400L
            assertEquals(expectedKey, sent.last())
        }

        assertFalse(bridge.dispatch(AnkiAction.UNDO, nowMs = t))
    }
}
