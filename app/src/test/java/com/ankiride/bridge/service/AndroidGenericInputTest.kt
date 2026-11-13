package com.ankiride.bridge.service

import com.ankiride.bridge.core.InputConfig
import com.ankiride.bridge.core.ScrollDirection
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
}
