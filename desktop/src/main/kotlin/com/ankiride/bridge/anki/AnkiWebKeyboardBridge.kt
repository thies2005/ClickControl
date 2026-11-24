package com.ankiride.bridge.anki

import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.PortableKey

/**
 * Controls AnkiWeb (or keyboard-focused Anki) by sending Space to reveal the answer
 * and 1–4 to rate the card after a 350ms bounce guard.
 */
class AnkiWebKeyboardBridge(
    private val sendKey: (PortableKey) -> Boolean,
    private val nowMsProvider: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val FLIP_TO_GRADE_GUARD_MS = 350L
    }

    @Volatile
    var isAnswerVisible: Boolean = false

    @Volatile
    var lastFlipTimestampMs: Long = 0L

    fun dispatch(action: AnkiAction, nowMs: Long = nowMsProvider()): Boolean {
        return when (action) {
            AnkiAction.FLIP_CARD -> {
                val ok = sendKey(PortableKey.SPACE)
                if (ok) {
                    isAnswerVisible = true
                    lastFlipTimestampMs = nowMs
                    DiagnosticLogger.info(LogCategory.ANKI, "AnkiWeb (Browser): Flipped card (Space)")
                }
                ok
            }
            AnkiAction.UNDO -> {
                DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb does not provide a keyboard Undo shortcut")
                false
            }
            AnkiAction.FLIP_OR_AGAIN -> flipOrGrade(PortableKey.DIGIT_1, "Again (1)", nowMs)
            AnkiAction.FLIP_OR_HARD -> flipOrGrade(PortableKey.DIGIT_2, "Hard (2)", nowMs)
            AnkiAction.FLIP_OR_GOOD -> flipOrGrade(PortableKey.DIGIT_3, "Good (3)", nowMs)
            AnkiAction.FLIP_OR_EASY -> flipOrGrade(PortableKey.DIGIT_4, "Easy (4)", nowMs)
        }
    }

    private fun flipOrGrade(key: PortableKey, label: String, nowMs: Long): Boolean {
        if (!isAnswerVisible) {
            val ok = sendKey(PortableKey.SPACE)
            if (ok) {
                isAnswerVisible = true
                lastFlipTimestampMs = nowMs
                DiagnosticLogger.info(LogCategory.ANKI, "AnkiWeb (Browser): 1st press -> Show Answer (Space, armed 350ms guard)")
            }
            return ok
        }
        if ((nowMs - lastFlipTimestampMs) < FLIP_TO_GRADE_GUARD_MS) {
            DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb: Bounce Guard (350ms) blocked quick double-press for $label")
            return false
        }
        val ok = sendKey(key)
        if (ok) {
            isAnswerVisible = false
            DiagnosticLogger.info(LogCategory.ANKI, "AnkiWeb (Browser): Graded card -> $label")
        }
        return ok
    }
}
