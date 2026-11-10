package com.ankiride.bridge.service

import com.ankiride.bridge.anki.DesktopAnkiDispatcher
import com.ankiride.bridge.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DesktopActionRouter(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
    private val configSupplier: () -> AppConfiguration,
    private val ankiDispatcher: AnkiActionDispatcher,
    private val myWhooshDispatcher: MyWhooshActionDispatcher,
    private val routingEnabled: () -> Boolean = { true },
    private val genericInputDispatcher: GenericInputDispatcher = DesktopGenericInputDispatcher
) {
    private val _lastRoutedActionLabel = MutableStateFlow("Ready")
    val lastRoutedActionLabel: StateFlow<String> = _lastRoutedActionLabel.asStateFlow()

    fun routeGesture(event: ButtonGestureEvent) {
        if (!routingEnabled()) return
        val config = configSupplier()
        val target = PresetStore.resolve(config, event.button, event.type) ?: if (config.podAssignment != PodAssignment.LEGACY_SHARED) ButtonMappingStore.assignedAction(config.podAssignment, event.button, event.type) else ButtonMappingStore.resolveAction(
            mode = config.mode,
            button = event.button,
            pressType = event.type,
            customBindings = config.customBindings,
            rightPodOnlyNoZwift = config.rightPodOnlyNoZwift,
            useZForShiftDownInSinglePodMode = config.useZForShiftDownInSinglePodMode
        )

        _lastRoutedActionLabel.value = PresetStore.active(config)?.let { PresetStore.bindingLabel(it, PresetBindingKey(event.button.canonicalButton, event.type)) } ?: target.label

        DiagnosticLogger.info(
            LogCategory.ROUTER,
            "[${config.mode.shortTitle}] ${event.button.displayName} (${event.type.name}) -> ${target.label}"
        )

        DesktopFeedbackCueManager.triggerCue(target, config)

        if (target == MappedTargetAction.SCREEN_CLICK || target == MappedTargetAction.KEYBOARD_INPUT) {
            val key = PresetBindingKey(event.button.canonicalButton, event.type)
            val payload = PresetStore.active(config)?.inputConfigs?.get(key)
            synchronized(DesktopGenericInputDispatcher.routingLock) {
                if (!routingEnabled() || payload == null || !payload.validFor(target) || !genericInputDispatcher.dispatch(target, payload)) {
                    DiagnosticLogger.warn(LogCategory.ROUTER, "Generic input is not configured or unavailable")
                }
            }
        }
        target.ankiAction?.let { ankiAction ->
            if (ankiDispatcher is DesktopAnkiDispatcher) {
                ankiDispatcher.dispatchAsync(ankiAction)
            } else {
                ankiDispatcher.dispatch(ankiAction)
            }
        }

        target.myWhooshAction?.let { mwAction ->
            scope.launch {
                myWhooshDispatcher.dispatch(mwAction, config)
            }
        }
    }
}
