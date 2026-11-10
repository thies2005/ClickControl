package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

interface InputDriver {
    fun move(x: Int, y: Int)
    fun mousePress()
    fun mouseRelease()
    fun keyPress(code: Int)
    fun keyRelease(code: Int)
}

class RobotInputDriver : InputDriver {
    private val robot = Robot()
    override fun move(x: Int, y: Int) = robot.mouseMove(x, y)
    override fun mousePress() = robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    override fun mouseRelease() = robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    override fun keyPress(code: Int) = robot.keyPress(code)
    override fun keyRelease(code: Int) = robot.keyRelease(code)
}

internal fun performInput(driver: InputDriver, action: MappedTargetAction, c: InputConfig, bounds: java.awt.Rectangle) {
    if (action == MappedTargetAction.SCREEN_CLICK) {
        if (!c.clickAtPointer) driver.move(bounds.x + ((c.left + c.width / 2) * (bounds.width - 1)).toInt(), bounds.y + ((c.top + c.height / 2) * (bounds.height - 1)).toInt())
        try { driver.mousePress() } finally { driver.mouseRelease() }
    } else {
        val name = if (c.key == PortableKey.BACKSPACE) "BACK_SPACE" else c.key!!.name.removePrefix("DIGIT_")
        val key = KeyEvent::class.java.getField("VK_$name").getInt(null)
        val modifiers = listOfNotNull(KeyEvent.VK_CONTROL.takeIf { c.ctrl }, KeyEvent.VK_ALT.takeIf { c.alt }, KeyEvent.VK_SHIFT.takeIf { c.shift }, KeyEvent.VK_META.takeIf { c.meta })
        val pressed = mutableListOf<Int>()
        try {
            for (code in modifiers + key) { pressed.add(code); driver.keyPress(code) }
        } finally { pressed.asReversed().forEach { try { driver.keyRelease(it) } catch (_: Exception) {} } }
    }
}

internal class GuardedInputDriver(private val delegate: InputDriver, private val valid: () -> Boolean) : InputDriver {
    private fun checkValid() { if (!valid()) throw CancellationException("Input cancelled") }
    override fun move(x: Int, y: Int) {
        if (delegate is WaylandInputBackend) delegate.moveChecked(x, y, valid) else { checkValid(); delegate.move(x, y) }
    }
    override fun mousePress() {
        if (delegate is WaylandInputBackend) delegate.mousePressChecked(valid) else { checkValid(); delegate.mousePress() }
    }
    override fun keyPress(code: Int) {
        if (delegate is WaylandInputBackend) delegate.keyPressChecked(code, valid) else { checkValid(); delegate.keyPress(code) }
    }
    override fun mouseRelease() = delegate.mouseRelease()
    override fun keyRelease(code: Int) = delegate.keyRelease(code)
}

internal class OrderedInputQueue(scope: CoroutineScope, private val execute: suspend (MappedTargetAction, InputConfig, () -> Boolean) -> Unit) {
    private val generation = AtomicLong()
    private val queue = Channel<Triple<Long, MappedTargetAction, InputConfig>>(64)
    init { scope.launch {
        for ((queuedGeneration, action, config) in queue) {
            if (queuedGeneration == generation.get()) execute(action, config) { queuedGeneration == generation.get() }
        }
    } }
    fun cancelPending() { generation.incrementAndGet() }
    fun enqueue(action: MappedTargetAction, config: InputConfig) = queue.trySend(Triple(generation.get(), action, config)).isSuccess
}

object DesktopGenericInputDispatcher : GenericInputDispatcher {
    val routingLock = Any()
    private val wayland = WaylandInputBackend()
    private val isWayland get() = System.getenv("XDG_SESSION_TYPE")?.equals("wayland", true) == true
    val inputStatus: String get() = if (isWayland) wayland.status else "Desktop input uses the graphical session"
    init { Runtime.getRuntime().addShutdownHook(Thread { wayland.close() }) }
    internal fun executeNow(action: MappedTargetAction, config: InputConfig, valid: () -> Boolean = { true }) {
        require(config.validFor(action))
        if (isWayland) {
            val bounds = if (action == MappedTargetAction.SCREEN_CLICK && !config.clickAtPointer) waylandBounds() else java.awt.Rectangle(1, 1)
            wayland.initialize()
            if (!valid()) return
            performInput(GuardedInputDriver(wayland, valid), action, config, bounds)
        } else {
            check(!GraphicsEnvironment.isHeadless()) { "No graphical display" }
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
            val driver = RobotInputDriver()
            if (!valid()) return
            performInput(GuardedInputDriver(driver, valid), action, config, bounds)
        }
    }
    private val queue = OrderedInputQueue(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { action, config, valid ->
        try {
            executeNow(action, config, valid)
            DiagnosticLogger.info(LogCategory.ROUTER, "Generic input sent to desktop; delivery depends on the focused application")
        } catch (_: CancellationException) {
            // Stop invalidated this action before it could emit more input.
        } catch (e: Exception) {
            if (isWayland) wayland.reportFailure(e)
            DiagnosticLogger.error(LogCategory.ROUTER, "Generic input failed: ${e.message}")
        }
    }
    fun cancelPending() = queue.cancelPending()
    override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean {
        if (!config.validFor(action)) return false
        if (!isWayland && GraphicsEnvironment.isHeadless()) {
            DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input unavailable: no display")
            return false
        }
        val accepted = queue.enqueue(action, config)
        if (!accepted) DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input queue is full")
        return accepted
    }
}
