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
    private val capabilities = PlatformCapabilities.desktop()
    private val _lastRoutedActionLabel = MutableStateFlow("Ready")
    val lastRoutedActionLabel: StateFlow<String> = _lastRoutedActionLabel.asStateFlow()

    fun routeGesture(event: ButtonGestureEvent) {
        if (!routingEnabled()) return
        val config = configSupplier()
        val activePreset = PresetStore.active(config)
        val target = PresetStore.resolve(config, event.button, event.type) ?: MappedTargetAction.NONE

        val actionText = activePreset?.let {
            PresetStore.bindingLabel(it, PresetBindingKey(event.button.canonicalButton, event.type), PlatformKind.DESKTOP)
        } ?: target.label
        _lastRoutedActionLabel.value = actionText

        val scopeLabel = activePreset?.name ?: "Default"
        DiagnosticLogger.info(
            LogCategory.ROUTER,
            "[$scopeLabel] ${event.button.displayName} (${event.type.name}) -> ${target.label}"
        )

        DesktopFeedbackCueManager.triggerCue(target, config)

        if (target.isGenericInput) {
            val key = PresetBindingKey(event.button.canonicalButton, event.type)
            val payload = activePreset?.inputConfigs?.get(key)
            synchronized(DesktopGenericInputDispatcher.routingLock) {
                if (!routingEnabled() || payload == null || !capabilities.isValidInputConfig(target, payload) ||
                    !genericInputDispatcher.dispatch(target, payload, event.isRepeat, event.button.canonicalButton)
                ) {
                    val statusHint = DesktopGenericInputDispatcher.inputStatus
                    DiagnosticLogger.warn(LogCategory.ROUTER, "Desktop input is not configured or unavailable ($statusHint)")
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
