package com.ankiride.bridge.ble

import com.ankiride.bridge.core.ButtonGestureEvent
import com.ankiride.bridge.core.ClickButton
import com.ankiride.bridge.core.PressType
import java.util.EnumMap

class ZwiftGestureEngine(
    private val isRepeatEnabled: (ClickButton) -> Boolean = { false },
    private val onGestureDetected: (ButtonGestureEvent) -> Unit
) {
    companion object {
        const val LONG_PRESS_THRESHOLD_MS = 450L
        const val DEBOUNCE_WINDOW_MS = 120L
        const val REPEAT_INTERVAL_MS = 120L
    }

    private class ButtonStateTracker {
        var isPressed: Boolean = false
        var pressStartTime: Long = 0L
        var longPressTriggered: Boolean = false
        var repeatModeActive: Boolean = false
        var lastEmittedTime: Long = -1000L
    }

    private val trackers = EnumMap<ClickButton, ButtonStateTracker>(ClickButton::class.java).apply {
        ClickButton.entries.forEach { put(it, ButtonStateTracker()) }
    }

    @Synchronized
    fun reset() {
        trackers.values.forEach { tracker ->
            tracker.isPressed = false
            tracker.longPressTriggered = false
            tracker.repeatModeActive = false
            tracker.lastEmittedTime = -1000L
        }
    }

    @Synchronized
    fun processButtonStates(states: Map<ClickButton, Boolean>, nowMs: Long = System.currentTimeMillis()) {
        states.forEach { (button, isPressed) ->
            val tracker = trackers[button] ?: return@forEach

            if (isPressed && !tracker.isPressed) {
                tracker.isPressed = true
                tracker.pressStartTime = nowMs
                tracker.longPressTriggered = false
                tracker.repeatModeActive = isRepeatEnabled(button)
                if (tracker.repeatModeActive && (nowMs - tracker.lastEmittedTime) >= DEBOUNCE_WINDOW_MS) {
                    tracker.lastEmittedTime = nowMs
                    onGestureDetected(ButtonGestureEvent(button, PressType.SINGLE_CLICK, nowMs))
                }
            } else if (isPressed && tracker.isPressed) {
                if (tracker.repeatModeActive) {
                    if ((nowMs - tracker.pressStartTime) >= LONG_PRESS_THRESHOLD_MS &&
                        (nowMs - tracker.lastEmittedTime) >= REPEAT_INTERVAL_MS
                    ) {
                        tracker.longPressTriggered = true
                        tracker.lastEmittedTime = nowMs
                        onGestureDetected(ButtonGestureEvent(button, PressType.SINGLE_CLICK, nowMs))
                    }
                } else if (!tracker.longPressTriggered && (nowMs - tracker.pressStartTime) >= LONG_PRESS_THRESHOLD_MS) {
                    tracker.longPressTriggered = true
                    if ((nowMs - tracker.lastEmittedTime) >= DEBOUNCE_WINDOW_MS) {
                        tracker.lastEmittedTime = nowMs
                        onGestureDetected(ButtonGestureEvent(button, PressType.LONG_PRESS, nowMs))
                    }
                }
            } else if (!isPressed && tracker.isPressed) {
                val wasRepeat = tracker.repeatModeActive
                tracker.isPressed = false
                tracker.repeatModeActive = false
                if (!wasRepeat) {
                    val holdDuration = nowMs - tracker.pressStartTime
                    if (!tracker.longPressTriggered && holdDuration >= LONG_PRESS_THRESHOLD_MS &&
                        (nowMs - tracker.lastEmittedTime) >= DEBOUNCE_WINDOW_MS) {
                        tracker.lastEmittedTime = nowMs
                        onGestureDetected(ButtonGestureEvent(button, PressType.LONG_PRESS, nowMs))
                    } else if (!tracker.longPressTriggered && holdDuration < LONG_PRESS_THRESHOLD_MS) {
                        if ((nowMs - tracker.lastEmittedTime) >= DEBOUNCE_WINDOW_MS) {
                            tracker.lastEmittedTime = nowMs
                            onGestureDetected(ButtonGestureEvent(button, PressType.SINGLE_CLICK, nowMs))
                        }
                    }
                }
            }
        }
    }
}
