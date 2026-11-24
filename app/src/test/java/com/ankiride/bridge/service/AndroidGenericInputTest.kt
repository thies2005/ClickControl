package com.ankiride.bridge.service

import com.ankiride.bridge.core.InputConfig
import com.ankiride.bridge.core.MappedTargetAction
import com.ankiride.bridge.core.PlatformCapabilities
import com.ankiride.bridge.core.PortableKey
import com.ankiride.bridge.core.ScrollDirection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGenericInputTest {
    @Test
    fun computeSwipeSegmentMatchesScrollDirections() {
        val down = computeSwipeSegment(
            InputConfig(left = 0.2f, top = 0.2f, width = 0.6f, height = 0.6f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3),
            widthPixels = 1080,
            heightPixels = 2400
        )
        // Scrolling content DOWN swipes finger upward (startY > endY)
        assertTrue(down.startY > down.endY)

        val up = computeSwipeSegment(
            InputConfig(left = 0.2f, top = 0.2f, width = 0.6f, height = 0.6f, scrollDirection = ScrollDirection.UP, scrollAmount = 3),
            widthPixels = 1080,
            heightPixels = 2400
        )
        assertTrue(up.startY < up.endY)

        val right = computeSwipeSegment(
            InputConfig(left = 0.2f, top = 0.2f, width = 0.6f, height = 0.6f, scrollDirection = ScrollDirection.RIGHT, scrollAmount = 3),
            widthPixels = 1080,
            heightPixels = 2400
        )
        assertTrue(right.startX > right.endX)

        val left = computeSwipeSegment(
            InputConfig(left = 0.2f, top = 0.2f, width = 0.6f, height = 0.6f, scrollDirection = ScrollDirection.LEFT, scrollAmount = 3),
            widthPixels = 1080,
            heightPixels = 2400
        )
        assertTrue(left.startX < left.endX)
    }

    @Test
    fun computeSwipeSegmentStaysStrictlyInsideNarrowArea() {
        for (dir in ScrollDirection.entries) {
            val seg = computeSwipeSegment(
                InputConfig(left = 0.45f, top = 0.45f, width = 0.10f, height = 0.10f, scrollDirection = dir, scrollAmount = 10),
                widthPixels = 1001,
                heightPixels = 1001
            )
            assertTrue("$dir startX=${seg.startX} out of [450,550]", seg.startX in 450f..550f)
            assertTrue("$dir endX=${seg.endX} out of [450,550]", seg.endX in 450f..550f)
            assertTrue("$dir startY=${seg.startY} out of [450,550]", seg.startY in 450f..550f)
            assertTrue("$dir endY=${seg.endY} out of [450,550]", seg.endY in 450f..550f)
        }
    }

    @Test
    fun androidDispatcherRejectsKeyboardAndPointerInputs() {
        val caps = PlatformCapabilities.android()
        assertFalse(caps.isValidInputConfig(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT)))
        assertFalse(caps.isValidInputConfig(MappedTargetAction.SCREEN_CLICK, InputConfig(clickAtPointer = true)))
        assertFalse(caps.isValidInputConfig(MappedTargetAction.SCREEN_SCROLL, InputConfig(clickAtPointer = true, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3)))

        assertFalse(AndroidGenericInputDispatcher.dispatch(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT)))
        assertFalse(AndroidGenericInputDispatcher.dispatch(MappedTargetAction.SCREEN_CLICK, InputConfig(clickAtPointer = true)))
        assertFalse(AndroidGenericInputDispatcher.dispatch(MappedTargetAction.SCREEN_SCROLL, InputConfig(clickAtPointer = true, scrollDirection = ScrollDirection.DOWN, scrollAmount = 3)))
    }
}
