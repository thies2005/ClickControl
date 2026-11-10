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

    fun routeGesture(event: ButtonGestureEvent) {
        if (!routingEnabled()) return
        val config = configSupplier()
        val target = PresetStore.resolve(config, event.button, event.type) ?: ButtonMappingStore.resolveAction(
            mode = config.mode,
            button = event.button,
            pressType = event.type,
            customBindings = config.customBindings,
            rightPodOnlyNoZwift = config.rightPodOnlyNoZwift,
            useZForShiftDownInSinglePodMode = config.useZForShiftDownInSinglePodMode,
            podAssignment = config.podAssignment
        )

        DiagnosticLogger.info(
            LogCategory.ROUTER,
            "[${config.mode.shortTitle}] ${event.button.displayName} (${event.type.name}) -> ${target.label}"
        )

        if (context != null) {
            FeedbackCueManager.triggerCue(context, target, config)
        }

        FloatingHudPill.notifyActionDispatched(
            service = BridgeAccessibilityService.activeInstance,
            enabled = config.floatingHudPillEnabled,
            bleState = BridgeForegroundService.connectionStateFlow.value,
            mode = config.mode,
            actionText = PresetStore.active(config)?.let { PresetStore.bindingLabel(it, PresetBindingKey(event.button.canonicalButton, event.type)) } ?: target.label
        )

        if (target == MappedTargetAction.SCREEN_CLICK || target == MappedTargetAction.KEYBOARD_INPUT) {
            val key = PresetBindingKey(event.button.canonicalButton, event.type)
            val payload = PresetStore.active(config)?.inputConfigs?.get(key)
            synchronized(AndroidGenericInputDispatcher.routingLock) {
                if (!routingEnabled() || payload == null || !payload.validFor(target) || !genericInputDispatcher.dispatch(target, payload)) {
                    DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input is not configured or unavailable")
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
