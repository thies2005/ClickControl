package com.ankiride.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.WindowManager
import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

internal data class SwipeSegment(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

internal fun computeSwipeSegment(config: InputConfig, widthPixels: Int, heightPixels: Int): SwipeSegment {
    val maxX = (widthPixels - 1).coerceAtLeast(1).toFloat()
    val maxY = (heightPixels - 1).coerceAtLeast(1).toFloat()
    val hasArea = config.width > 0f && config.height > 0f
    val minXNorm = if (hasArea) (config.left + config.width * 0.05f).coerceIn(0.01f, 0.99f) else 0.05f
    val maxXNorm = if (hasArea) (config.left + config.width * 0.95f).coerceIn(minXNorm, 0.99f) else 0.95f
    val minYNorm = if (hasArea) (config.top + config.height * 0.05f).coerceIn(0.01f, 0.99f) else 0.05f
    val maxYNorm = if (hasArea) (config.top + config.height * 0.95f).coerceIn(minYNorm, 0.99f) else 0.95f
    val centerXNorm = (minXNorm + maxXNorm) / 2f
    val centerYNorm = (minYNorm + maxYNorm) / 2f
    val requestedHalfSpan = (config.scrollAmount * 0.08f).coerceIn(0.15f, 0.70f) / 2f
    val halfX = minOf(requestedHalfSpan, (maxXNorm - minXNorm) / 2f)
    val halfY = minOf(requestedHalfSpan, (maxYNorm - minYNorm) / 2f)
    // Scrolling down or advancing right means swiping upward or leftward on a touchscreen.
    return when (config.scrollDirection) {
        ScrollDirection.DOWN -> SwipeSegment(centerXNorm * maxX, (centerYNorm + halfY) * maxY, centerXNorm * maxX, (centerYNorm - halfY) * maxY)
        ScrollDirection.UP -> SwipeSegment(centerXNorm * maxX, (centerYNorm - halfY) * maxY, centerXNorm * maxX, (centerYNorm + halfY) * maxY)
        ScrollDirection.RIGHT -> SwipeSegment((centerXNorm + halfX) * maxX, centerYNorm * maxY, (centerXNorm - halfX) * maxX, centerYNorm * maxY)
        ScrollDirection.LEFT -> SwipeSegment((centerXNorm - halfX) * maxX, centerYNorm * maxY, (centerXNorm + halfX) * maxX, centerYNorm * maxY)
    }
}

private data class QueuedTouchAction(
    val generation: Long,
    val allRepeatsGeneration: Long,
    val buttonRepeatGeneration: Long,
    val sourceButton: ClickButton?,
    val action: MappedTargetAction,
    val config: InputConfig,
    val isRepeat: Boolean
)

object AndroidGenericInputDispatcher : GenericInputDispatcher {
    val routingLock = Any()
    private val capabilities = PlatformCapabilities.android()
    private val generation = AtomicLong()
    private val allRepeatsGeneration = AtomicLong()
    private val buttonRepeatGenerations = ConcurrentHashMap<ClickButton, AtomicLong>()
    private val inFlight = AtomicBoolean(false)

    private fun counterFor(button: ClickButton?): AtomicLong? =
        button?.canonicalButton?.let { buttonRepeatGenerations.computeIfAbsent(it) { AtomicLong() } }

    private fun isItemValid(item: QueuedTouchAction): Boolean {
        if (item.generation != generation.get()) return false
        if (!item.isRepeat) return true
        if (item.allRepeatsGeneration != allRepeatsGeneration.get()) return false
        val btnCounter = item.sourceButton?.let { buttonRepeatGenerations[it] } ?: return true
        return item.buttonRepeatGeneration == btnCounter.get()
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

    private suspend fun dispatchGestureStroke(service: AccessibilityService, path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                    override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                }, null)
                if (!accepted && cont.isActive) cont.resume(false)
            }
        } ?: false
    }

    private val queue by lazy {
        val queue = Channel<QueuedTouchAction>(64)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            for (item in queue) {
                if (!isItemValid(item)) continue
                if (item.isRepeat && inFlight.get()) continue
                val service = BridgeAccessibilityService.activeInstance
                if (service == null) {
                    DiagnosticLogger.warn(LogCategory.ROUTER, "Touch action requires the Android accessibility service")
                    continue
                }
                inFlight.set(true)
                try {
                    val metrics = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    (service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
                    if (!isItemValid(item)) continue
                    val completed = if (item.action == MappedTargetAction.SCREEN_CLICK) {
                        val x = (item.config.left + item.config.width / 2) * (metrics.widthPixels - 1)
                        val y = (item.config.top + item.config.height / 2) * (metrics.heightPixels - 1)
                        val path = Path().apply { moveTo(x, y) }
                        dispatchGestureStroke(service, path, 75)
                    } else {
                        val seg = computeSwipeSegment(item.config, metrics.widthPixels, metrics.heightPixels)
                        val path = Path().apply { moveTo(seg.startX, seg.startY); lineTo(seg.endX, seg.endY) }
                        dispatchGestureStroke(service, path, 220)
                    }
                    if (!completed) DiagnosticLogger.warn(LogCategory.ROUTER, "${item.action.displayLabel(PlatformKind.ANDROID)} failed or was cancelled")
                } catch (e: Exception) {
                    DiagnosticLogger.error(LogCategory.ROUTER, "Touch action failed: ${e.message}")
                } finally {
                    inFlight.set(false)
                }
            }
        }
        queue
    }

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
        if (!capabilities.isValidInputConfig(action, config)) {
            if (action == MappedTargetAction.KEYBOARD_INPUT) {
                DiagnosticLogger.warn(LogCategory.ROUTER, "Keyboard shortcuts are not supported on Android")
            }
            return false
        }
        val service = BridgeAccessibilityService.activeInstance
        if (service == null) {
            DiagnosticLogger.warn(LogCategory.ROUTER, "Touch action requires the Android accessibility service")
            return false
        }
        if (isRepeat && inFlight.get()) return true
        val canonical = sourceButton?.canonicalButton
        val btnGen = counterFor(canonical)?.get() ?: 0L
        val accepted = queue.trySend(
            QueuedTouchAction(
                generation = generation.get(),
                allRepeatsGeneration = allRepeatsGeneration.get(),
                buttonRepeatGeneration = btnGen,
                sourceButton = canonical,
                action = action,
                config = config,
                isRepeat = isRepeat
            )
        ).isSuccess
        if (!accepted) DiagnosticLogger.warn(LogCategory.ROUTER, "Touch input queue is full")
        return accepted
    }
}
