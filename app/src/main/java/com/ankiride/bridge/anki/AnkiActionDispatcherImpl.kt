package com.ankiride.bridge.anki

import android.accessibilityservice.AccessibilityService
import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.AnkiActionDispatcher
import com.ankiride.bridge.core.AnkiTarget
import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.PlatformCapabilities
import com.ankiride.bridge.core.PresetStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class AnkiActionDispatcherImpl(
    private val bridge: AnkiAccessibilityBridge = AnkiAccessibilityBridge(),
    private val webBridge: AnkiWebAccessibilityBridge = AnkiWebAccessibilityBridge(),
    private val ankiConnectClient: AnkiConnectClient = AnkiConnectClient(),
    private val configSupplier: () -> AppConfiguration = { AppConfiguration() },
    private val serviceProvider: () -> AccessibilityService?
) : AnkiActionDispatcher {

    private val capabilities = PlatformCapabilities.android()

    val ioExecutor: ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )

    override fun dispatch(action: AnkiAction): Boolean {
        val config = configSupplier()
        val rawTarget = PresetStore.active(config)?.ankiTarget ?: AnkiTarget.ANKIDROID
        val target = capabilities.normalizeAnkiTarget(rawTarget)

        return when (target) {
            AnkiTarget.ANKICONNECT -> {
                ioExecutor.execute {
                    ankiConnectClient.dispatchAnkiConnectAction(
                        host = config.ubuntuAnkiConnectHost,
                        port = config.ubuntuAnkiConnectPort,
                        action = action
                    )
                }
                true
            }
            AnkiTarget.ANKIWEB -> {
                val service = serviceProvider() ?: run {
                    DiagnosticLogger.warn(LogCategory.ANKI, "AnkiWeb requires ClickControl Accessibility Service enabled in Android Settings")
                    return false
                }
                webBridge.performAnkiWebAction(service, action)
            }
            AnkiTarget.ANKIDROID -> {
                val service = serviceProvider() ?: run {
                    DiagnosticLogger.warn(LogCategory.ANKI, "AnkiDroid control requires ClickControl Accessibility Service enabled in Android Settings")
                    return false
                }
                bridge.performAnkiAction(service, action)
            }
        }
    }
}
