package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

interface InputDriver {
    fun move(x: Int, y: Int)
    fun mousePress()
    fun mouseRelease()
    fun mouseWheel(horizontal: Int, vertical: Int)
    fun keyPress(code: Int)
    fun keyRelease(code: Int)
}

class RobotInputDriver : InputDriver {
    private val robot = Robot()
    override fun move(x: Int, y: Int) = robot.mouseMove(x, y)
    override fun mousePress() = robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
    override fun mouseRelease() = robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
    override fun mouseWheel(horizontal: Int, vertical: Int) {
        if (vertical != 0) robot.mouseWheel(vertical)
        if (horizontal != 0) {
            robot.keyPress(KeyEvent.VK_SHIFT)
            try { robot.mouseWheel(horizontal) } finally { robot.keyRelease(KeyEvent.VK_SHIFT) }
        }
    }
    override fun keyPress(code: Int) = robot.keyPress(code)
    override fun keyRelease(code: Int) = robot.keyRelease(code)
}

internal fun performInput(driver: InputDriver, action: MappedTargetAction, c: InputConfig, bounds: java.awt.Rectangle) {
    when (action) {
        MappedTargetAction.SCREEN_CLICK -> {
            if (!c.clickAtPointer) driver.move(bounds.x + ((c.left + c.width / 2) * (bounds.width - 1)).toInt(), bounds.y + ((c.top + c.height / 2) * (bounds.height - 1)).toInt())
            try { driver.mousePress() } finally { driver.mouseRelease() }
        }
        MappedTargetAction.SCREEN_SCROLL -> {
            if (!c.clickAtPointer && c.width > 0f && c.height > 0f) {
                driver.move(bounds.x + ((c.left + c.width / 2) * (bounds.width - 1)).toInt(), bounds.y + ((c.top + c.height / 2) * (bounds.height - 1)).toInt())
            }
            val (dx, dy) = when (c.scrollDirection) {
                ScrollDirection.UP -> 0 to -c.scrollAmount
                ScrollDirection.DOWN -> 0 to c.scrollAmount
                ScrollDirection.LEFT -> -c.scrollAmount to 0
                ScrollDirection.RIGHT -> c.scrollAmount to 0
            }
            driver.mouseWheel(dx, dy)
        }
        else -> {
            val name = if (c.key == PortableKey.BACKSPACE) "BACK_SPACE" else c.key!!.name.removePrefix("DIGIT_")
            val key = KeyEvent::class.java.getField("VK_$name").getInt(null)
            val modifiers = listOfNotNull(KeyEvent.VK_CONTROL.takeIf { c.ctrl }, KeyEvent.VK_ALT.takeIf { c.alt }, KeyEvent.VK_SHIFT.takeIf { c.shift }, KeyEvent.VK_META.takeIf { c.meta })
            val pressed = mutableListOf<Int>()
            try {
                for (code in modifiers + key) { pressed.add(code); driver.keyPress(code) }
            } finally { pressed.asReversed().forEach { try { driver.keyRelease(it) } catch (_: Exception) {} } }
        }
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
    override fun mouseWheel(horizontal: Int, vertical: Int) {
        if (delegate is WaylandInputBackend) delegate.mouseWheelChecked(horizontal, vertical, valid) else { checkValid(); delegate.mouseWheel(horizontal, vertical) }
    }
    override fun keyPress(code: Int) {
        if (delegate is WaylandInputBackend) delegate.keyPressChecked(code, valid) else { checkValid(); delegate.keyPress(code) }
    }
    override fun mouseRelease() = delegate.mouseRelease()
    override fun keyRelease(code: Int) = delegate.keyRelease(code)
}

internal data class QueuedInput(
    val generation: Long,
    val allRepeatsGeneration: Long,
    val buttonRepeatGeneration: Long,
    val sourceButton: ClickButton?,
    val action: MappedTargetAction,
    val config: InputConfig,
    val isRepeat: Boolean = false
)

internal class OrderedInputQueue(
    scope: CoroutineScope,
    private val execute: suspend (MappedTargetAction, InputConfig, () -> Boolean) -> Unit
) {
    private val generation = AtomicLong()
    private val allRepeatsGeneration = AtomicLong()
    private val buttonRepeatGenerations = ConcurrentHashMap<ClickButton, AtomicLong>()
    private val inFlight = AtomicBoolean(false)
    private val queue = Channel<QueuedInput>(64)

    private fun counterFor(button: ClickButton?): AtomicLong? =
        button?.canonicalButton?.let { buttonRepeatGenerations.computeIfAbsent(it) { AtomicLong() } }

    private fun isItemValid(item: QueuedInput): Boolean {
        if (item.generation != generation.get()) return false
        if (!item.isRepeat) return true
        if (item.allRepeatsGeneration != allRepeatsGeneration.get()) return false
        val btnCounter = item.sourceButton?.let { buttonRepeatGenerations[it] } ?: return true
        return item.buttonRepeatGeneration == btnCounter.get()
    }

    init {
        scope.launch {
            for (item in queue) {
                if (!isItemValid(item)) continue
                if (item.isRepeat && inFlight.get()) continue
                inFlight.set(true)
                try {
                    execute(item.action, item.config) { isItemValid(item) }
                } finally {
                    inFlight.set(false)
                }
            }
        }
    }

    fun cancelPendingRepeats(button: ClickButton? = null) {
        if (button == null) {
            allRepeatsGeneration.incrementAndGet()
        } else {
            counterFor(button)?.incrementAndGet()
        }
    }

    fun cancelAllPending() {
        generation.incrementAndGet()
        allRepeatsGeneration.incrementAndGet()
    }

    fun cancelPending() = cancelAllPending()

    fun enqueue(
        action: MappedTargetAction,
        config: InputConfig,
        isRepeat: Boolean = false,
        sourceButton: ClickButton? = null
    ): Boolean {
        if (isRepeat && inFlight.get()) return true
        val canonical = sourceButton?.canonicalButton
        val btnGen = counterFor(canonical)?.get() ?: 0L
        return queue.trySend(
            QueuedInput(
                generation = generation.get(),
                allRepeatsGeneration = allRepeatsGeneration.get(),
                buttonRepeatGeneration = btnGen,
                sourceButton = canonical,
                action = action,
                config = config,
                isRepeat = isRepeat
            )
        ).isSuccess
    }
}

object DesktopGenericInputDispatcher : GenericInputDispatcher {
    val routingLock = Any()
    private val capabilities = PlatformCapabilities.desktop()
    private val wayland = WaylandInputBackend()
    private val isWayland get() = System.getenv("XDG_SESSION_TYPE")?.equals("wayland", true) == true
    val inputStatus: String
        get() = if (isWayland) wayland.status else "Desktop input ready (graphical session)"

    init {
        Runtime.getRuntime().addShutdownHook(Thread { wayland.close() })
    }

    internal fun executeNow(action: MappedTargetAction, config: InputConfig, valid: () -> Boolean = { true }) {
        require(capabilities.isValidInputConfig(action, config))
        if (isWayland) {
            val needsBounds = !config.clickAtPointer && (action == MappedTargetAction.SCREEN_CLICK || (action == MappedTargetAction.SCREEN_SCROLL && config.width > 0f && config.height > 0f))
            val bounds = if (needsBounds) waylandBounds() else java.awt.Rectangle(1, 1)
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
            DiagnosticLogger.info(LogCategory.ROUTER, "Desktop input sent; delivery depends on the focused window")
        } catch (_: CancellationException) {
            // Stop or release invalidated this action before it could emit more input.
        } catch (e: Exception) {
            if (isWayland) wayland.reportFailure(e)
            DiagnosticLogger.error(LogCategory.ROUTER, "Desktop input failed: ${e.message}")
        }
    }

    fun cancelPendingRepeats(button: ClickButton? = null) = queue.cancelPendingRepeats(button)
    fun cancelAllPending() = queue.cancelAllPending()
    fun cancelPending() = cancelAllPending()

    override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean =
        dispatch(action, config, isRepeat = false, sourceButton = null)

    override fun dispatch(action: MappedTargetAction, config: InputConfig, isRepeat: Boolean): Boolean =
        dispatch(action, config, isRepeat = isRepeat, sourceButton = null)

    override fun dispatch(
        action: MappedTargetAction,
        config: InputConfig,
        isRepeat: Boolean,
        sourceButton: ClickButton?
    ): Boolean {
        if (!capabilities.isValidInputConfig(action, config)) return false
        if (!isWayland && GraphicsEnvironment.isHeadless()) {
            DiagnosticLogger.warn(LogCategory.ROUTER, "Desktop input unavailable: no graphical display")
            return false
        }
        val accepted = queue.enqueue(action, config, isRepeat, sourceButton)
        if (!accepted) DiagnosticLogger.warn(LogCategory.ROUTER, "Desktop input queue is full")
        return accepted
    }
}
