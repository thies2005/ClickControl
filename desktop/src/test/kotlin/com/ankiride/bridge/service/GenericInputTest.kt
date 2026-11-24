package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.Rectangle
import java.awt.event.KeyEvent

class GenericInputTest {
    class Driver(val fail: Boolean = false) : InputDriver {
        val calls = mutableListOf<String>()
        override fun move(x: Int, y: Int) { calls.add("move:$x,$y") }
        override fun mousePress() { calls.add("mouseDown") }
        override fun mouseRelease() { calls.add("mouseUp") }
        override fun mouseWheel(horizontal: Int, vertical: Int) { calls.add("wheel:$horizontal,$vertical") }
        override fun keyPress(code: Int) { calls.add("down:$code"); if (fail && code == KeyEvent.VK_A) error("failure") }
        override fun keyRelease(code: Int) { calls.add("up:$code") }
    }
    @Test fun stopDropsPendingButAllowsNewInput() = kotlinx.coroutines.test.runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = mutableListOf<PortableKey?>()
        val queue = OrderedInputQueue(backgroundScope) { _, config, _ ->
            calls.add(config.key)
            if (config.key == PortableKey.A) { entered.complete(Unit); release.await() }
        }
        queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.A))
        entered.await()
        queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.B))
        queue.cancelPending()
        queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.C))
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(PortableKey.A, PortableKey.C), calls)
    }
    @Test fun stopDuringSetupPreventsInputAfterSetup() = kotlinx.coroutines.test.runTest {
        for ((action, config) in listOf(
            MappedTargetAction.SCREEN_CLICK to InputConfig(.1f, .1f, .2f, .2f),
            MappedTargetAction.KEYBOARD_INPUT to InputConfig(key = PortableKey.A)
        )) {
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val completed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val driver = Driver()
            val queue = OrderedInputQueue(backgroundScope) { queuedAction, queuedConfig, valid ->
                entered.complete(Unit)
                release.await() // Simulate slow geometry or daemon setup.
                try { performInput(GuardedInputDriver(driver, valid), queuedAction, queuedConfig, Rectangle(100, 100)) }
                catch (_: java.util.concurrent.CancellationException) { }
                completed.complete(Unit)
            }
            queue.enqueue(action, config)
            entered.await()
            queue.cancelPending()
            release.complete(Unit)
            completed.await()
            assertTrue(driver.calls.none { it.startsWith("move:") || it.startsWith("down:") || it == "mouseDown" })
        }
    }
    @Test fun allPortableKeysHaveDesktopMapping() {
        for (key in PortableKey.entries) performInput(Driver(), MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = key), Rectangle(100, 100))
    }
    @Test fun releasesKeysAfterFailure() {
        val driver = Driver(true)
        try { performInput(driver, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.A, ctrl = true, shift = true), Rectangle(100, 100)); fail() } catch (_: IllegalStateException) {}
        assertEquals(listOf("down:${KeyEvent.VK_CONTROL}", "down:${KeyEvent.VK_SHIFT}", "down:${KeyEvent.VK_A}", "up:${KeyEvent.VK_A}", "up:${KeyEvent.VK_SHIFT}", "up:${KeyEvent.VK_CONTROL}"), driver.calls)
    }
    @Test fun currentPointerClickDoesNotMovePointer() {
        val driver = Driver()
        performInput(driver, MappedTargetAction.SCREEN_CLICK, InputConfig(clickAtPointer = true), Rectangle(100, 100))
        assertEquals(listOf("mouseDown", "mouseUp"), driver.calls)
    }
    @Test fun clickUsesAreaCenterOnPrimaryDisplay() {
        val driver = Driver()
        performInput(driver, MappedTargetAction.SCREEN_CLICK, InputConfig(.2f,.1f,.4f,.2f), Rectangle(10, 20, 101, 201))
        assertEquals(listOf("move:50,60", "mouseDown", "mouseUp"), driver.calls)
    }

    @Test fun scrollAtPointerAndFixedArea() {
        val pointerDriver = Driver()
        performInput(pointerDriver, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 4, clickAtPointer = true), Rectangle(0, 0, 100, 100))
        assertEquals(listOf("wheel:0,4"), pointerDriver.calls)

        val areaDriver = Driver()
        performInput(areaDriver, MappedTargetAction.SCREEN_SCROLL, InputConfig(left = .2f, top = .1f, width = .4f, height = .2f, scrollDirection = ScrollDirection.LEFT, scrollAmount = 2, clickAtPointer = false), Rectangle(10, 20, 101, 201))
        assertEquals(listOf("move:50,60", "wheel:-2,0"), areaDriver.calls)
    }

    @Test fun repeatTicksAreCoalescedWhileInFlight() = kotlinx.coroutines.test.runTest {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = mutableListOf<PortableKey?>()
        val queue = OrderedInputQueue(backgroundScope) { _, config, _ ->
            calls.add(config.key)
            if (config.key == PortableKey.A) { entered.complete(Unit); release.await() }
        }
        queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.A), isRepeat = false)
        entered.await()
        // While A is in flight, repeat ticks are dropped instead of building a runaway backlog
        assertTrue(queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.B), isRepeat = true))
        assertTrue(queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.C), isRepeat = true))
        // Non-repeat action still queues normally
        assertTrue(queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.D), isRepeat = false))
        release.complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf(PortableKey.A, PortableKey.D), calls)
    }

    @Test fun releaseCancelsOnlyRepeatsForReleasedButtonWithoutDroppingInitialOrOtherButtons() = kotlinx.coroutines.test.runTest {
        val initialStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val initialRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repeatStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val repeatRelease = kotlinx.coroutines.CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var repeatWasValidAfterCancel = true

        val queue = OrderedInputQueue(backgroundScope) { _, config, valid ->
            when {
                config.scrollDirection == ScrollDirection.DOWN && !config.repeatWhileHeld -> {
                    initialStarted.complete(Unit)
                    initialRelease.await()
                    if (valid()) events.add("DOWN_INITIAL")
                }
                config.scrollDirection == ScrollDirection.DOWN && config.repeatWhileHeld -> {
                    repeatStarted.complete(Unit)
                    repeatRelease.await()
                    repeatWasValidAfterCancel = valid()
                    if (valid()) events.add("DOWN_REPEAT")
                }
                config.scrollDirection == ScrollDirection.LEFT -> {
                    if (valid()) events.add("LEFT_INITIAL")
                }
                config.scrollDirection == ScrollDirection.UP -> {
                    if (valid()) events.add("UP_REPEAT")
                }
            }
        }

        // 1. User taps DOWN (initial isRepeat=false) and quickly releases DOWN while initial scroll is executing.
        queue.enqueue(MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, repeatWhileHeld = false, clickAtPointer = true), isRepeat = false, sourceButton = ClickButton.DOWN)
        initialStarted.await()
        queue.enqueue(MappedTargetAction.KEYBOARD_INPUT, InputConfig(scrollDirection = ScrollDirection.LEFT, key = PortableKey.LEFT), isRepeat = false, sourceButton = ClickButton.LEFT)
        queue.cancelPendingRepeats(ClickButton.DOWN)
        initialRelease.complete(Unit)
        testScheduler.runCurrent()

        assertEquals(listOf("DOWN_INITIAL", "LEFT_INITIAL"), events)

        // 2. User holds DOWN until repeat tick starts in flight, then releases DOWN -> invalidates DOWN repeat only.
        queue.enqueue(MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, repeatWhileHeld = true, clickAtPointer = true), isRepeat = true, sourceButton = ClickButton.DOWN)
        repeatStarted.await()
        queue.cancelPendingRepeats(ClickButton.DOWN)
        repeatRelease.complete(Unit)
        testScheduler.runCurrent()

        assertFalse(repeatWasValidAfterCancel)
        // Subsequent UP repeat runs unaffected.
        queue.enqueue(MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.UP, repeatWhileHeld = true, clickAtPointer = true), isRepeat = true, sourceButton = ClickButton.UP)
        testScheduler.runCurrent()
        assertEquals(listOf("DOWN_INITIAL", "LEFT_INITIAL", "UP_REPEAT"), events)
    }
}
