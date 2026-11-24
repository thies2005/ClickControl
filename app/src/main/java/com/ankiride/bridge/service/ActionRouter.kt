package com.ankiride.bridge.service

import android.content.Context
import com.ankiride.bridge.core.*
import com.ankiride.bridge.ui.FloatingHudPill
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ActionRouter(
    private val context: Context? = null,
    private val scope: CoroutineScope,
    private val configSupplier: () -> AppConfiguration,
    private val ankiDispatcher: AnkiActionDispatcher,
    private val myWhooshDispatcher: MyWhooshActionDispatcher,
    private val routingEnabled: () -> Boolean = { true },
    private val genericInputDispatcher: GenericInputDispatcher = AndroidGenericInputDispatcher
) {
    private val capabilities = PlatformCapabilities.android()

    fun routeGesture(event: ButtonGestureEvent) {
        if (!routingEnabled()) return
        val config = configSupplier()
        val activePreset = PresetStore.active(config)
        val target = PresetStore.resolve(config, event.button, event.type) ?: MappedTargetAction.NONE

        val scopeLabel = activePreset?.name ?: "Default"
        DiagnosticLogger.info(
            LogCategory.ROUTER,
            "[$scopeLabel] ${event.button.displayName} (${event.type.name}) -> ${target.displayLabel(PlatformKind.ANDROID)}"
        )

        if (context != null) {
            FeedbackCueManager.triggerCue(context, target, config)
        }

        val actionText = activePreset?.let {
            PresetStore.bindingLabel(it, PresetBindingKey(event.button.canonicalButton, event.type), PlatformKind.ANDROID)
        } ?: target.displayLabel(PlatformKind.ANDROID)

        FloatingHudPill.notifyActionDispatched(
            service = BridgeAccessibilityService.activeInstance,
            enabled = config.floatingHudPillEnabled,
            bleState = BridgeForegroundService.connectionStateFlow.value,
            presetName = activePreset?.name ?: "Default",
            ankiTarget = activePreset?.ankiTarget ?: AnkiTarget.ANKIDROID,
            actionText = actionText
        )

        if (target.isGenericInput) {
            val key = PresetBindingKey(event.button.canonicalButton, event.type)
            val payload = activePreset?.inputConfigs?.get(key)
            synchronized(AndroidGenericInputDispatcher.routingLock) {
                if (!routingEnabled() || payload == null || !capabilities.isValidInputConfig(target, payload) ||
                    !genericInputDispatcher.dispatch(target, payload, event.isRepeat, event.button.canonicalButton)
                ) {
                    DiagnosticLogger.warn(LogCategory.ROUTER, "Touch action is not configured or Accessibility Service is off")
                }
            }
        }
        target.ankiAction?.let { ankiAction ->
            ankiDispatcher.dispatch(ankiAction)
        }
        target.myWhooshAction?.let { mwAction ->
            scope.launch {
                myWhooshDispatcher.dispatch(mwAction, config)
            }
        }
    }
}
