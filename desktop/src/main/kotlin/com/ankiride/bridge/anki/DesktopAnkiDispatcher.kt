package com.ankiride.bridge.anki

import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.AnkiActionDispatcher
import com.ankiride.bridge.core.AnkiTarget
import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.InputConfig
import com.ankiride.bridge.core.MappedTargetAction
import com.ankiride.bridge.core.PlatformCapabilities
import com.ankiride.bridge.core.PresetStore
import com.ankiride.bridge.service.DesktopGenericInputDispatcher
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DesktopAnkiDispatcher(
    private val configProvider: () -> AppConfiguration,
    private val ankiConnectClient: AnkiConnectClient = AnkiConnectClient(),
    private val ankiWebBridge: AnkiWebKeyboardBridge = AnkiWebKeyboardBridge(
        sendKey = { key ->
            DesktopGenericInputDispatcher.dispatch(
                MappedTargetAction.KEYBOARD_INPUT,
                InputConfig(key = key)
            )
        }
    ),
    val executor: ThreadPoolExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )
) : AnkiActionDispatcher {

    private val capabilities = PlatformCapabilities.desktop()
    @Volatile private var lastPresetKey: Pair<String?, AnkiTarget>? = null

    fun dispatchAsync(action: AnkiAction) {
        executor.execute {
            dispatch(action)
        }
    }

    override fun dispatch(action: AnkiAction): Boolean {
        val config = configProvider()
        val activePreset = PresetStore.active(config)
        val rawTarget = activePreset?.ankiTarget ?: AnkiTarget.ANKICONNECT
        val target = capabilities.normalizeAnkiTarget(rawTarget)
        val presetKey = activePreset?.id to target
        if (lastPresetKey != presetKey) {
            lastPresetKey = presetKey
            ankiWebBridge.isAnswerVisible = false
            ankiWebBridge.lastFlipTimestampMs = 0L
        }
        if (target == AnkiTarget.ANKIWEB) {
            return ankiWebBridge.dispatch(action)
        }
        val rawHost = config.ubuntuAnkiConnectHost.trim()
        val host = if (rawHost.isBlank()) "127.0.0.1" else rawHost
        val port = config.ubuntuAnkiConnectPort
        return ankiConnectClient.dispatchAnkiConnectAction(host, port, action)
    }
}
