package com.ankiride.bridge.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

object AndroidGenericInputDispatcher : GenericInputDispatcher {
    val routingLock = Any()
    private val generation = AtomicLong()
    fun cancelPending() { generation.incrementAndGet() }
    private val queue by lazy {
        val queue = Channel<Triple<Long, MappedTargetAction, InputConfig>>(64)
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            for ((queuedGeneration, action, config) in queue) {
                if (queuedGeneration != generation.get()) continue
                val service = BridgeAccessibilityService.activeInstance
                if (service == null) { DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input requires the accessibility service"); continue }
                try {
                    if (action == MappedTargetAction.SCREEN_CLICK) {
                        val metrics = android.util.DisplayMetrics()
                        @Suppress("DEPRECATION")
                        (service.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
                        val x = (config.left + config.width / 2) * (metrics.widthPixels - 1)
                        val y = (config.top + config.height / 2) * (metrics.heightPixels - 1)
                        val path = Path().apply { moveTo(x, y) }
                        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 75)).build()
                        val completed = withTimeoutOrNull(3000) { suspendCancellableCoroutine<Boolean> { cont ->
                            val accepted = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                            }, null)
                            if (!accepted && cont.isActive) cont.resume(false)
                        } } ?: false
                        if (!completed) DiagnosticLogger.warn(LogCategory.ROUTER, "Screen click failed or was cancelled")
                    } else if (Build.VERSION.SDK_INT >= 33) {
                        val input = service.inputMethod
                        val connection = input?.currentInputConnection
                        if (input?.currentInputStarted != true || connection == null) { DiagnosticLogger.warn(LogCategory.ROUTER, "Keyboard input requires a focused text editor"); continue }
                        val name = when (config.key!!) { PortableKey.BACKSPACE -> "DEL"; PortableKey.DELETE -> "FORWARD_DEL"; PortableKey.UP, PortableKey.DOWN, PortableKey.LEFT, PortableKey.RIGHT -> "DPAD_${config.key.name}"; else -> config.key.name.removePrefix("DIGIT_") }
                        val code = KeyEvent.keyCodeFromString("KEYCODE_$name")
                        if (code == KeyEvent.KEYCODE_UNKNOWN) { DiagnosticLogger.warn(LogCategory.ROUTER, "Unsupported key"); continue }
                        val meta = (if (config.ctrl) KeyEvent.META_CTRL_ON else 0) or (if (config.alt) KeyEvent.META_ALT_ON else 0) or (if (config.shift) KeyEvent.META_SHIFT_ON else 0) or (if (config.meta) KeyEvent.META_META_ON else 0)
                        val now = SystemClock.uptimeMillis()
                        try { connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD)) } finally {
                            connection.sendKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                        }
                    } else DiagnosticLogger.warn(LogCategory.ROUTER, "Keyboard input requires Android 13 or later and a focused text editor")
                } catch (e: Exception) { DiagnosticLogger.error(LogCategory.ROUTER, "Generic input failed: ${e.message}") }
            }
        }
        queue
    }
    override fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean {
        if (!config.validFor(action) || (action == MappedTargetAction.SCREEN_CLICK && config.clickAtPointer)) return false
        val service = BridgeAccessibilityService.activeInstance
        if (service == null) { DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input requires the accessibility service"); return false }
        if (action == MappedTargetAction.KEYBOARD_INPUT && (Build.VERSION.SDK_INT < 33 || service.inputMethod?.currentInputStarted != true || service.inputMethod?.currentInputConnection == null)) {
            DiagnosticLogger.warn(LogCategory.ROUTER, "Keyboard input requires Android 13 or later and a focused text editor"); return false
        }
        val accepted = queue.trySend(Triple(generation.get(), action, config)).isSuccess
        if (!accepted) DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input queue is full")
        return accepted
    }
}
