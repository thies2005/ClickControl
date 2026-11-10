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
}
