package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MyWhooshAction
import com.ankiride.bridge.core.MyWhooshActionDispatcher
import com.ankiride.bridge.core.MyWhooshLocation
import com.ankiride.bridge.service.BridgeAccessibilityService

class MyWhooshNetworkDispatcherImpl(
    private val client: OpenBikeControlClient,
    private val accessibilityServiceSupplier: () -> BridgeAccessibilityService?
) : MyWhooshActionDispatcher {

    override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration): Boolean {
        // 1. Official native OpenBikeControl TCP channel is primary (low latency, zero permissions needed).
        if (client.hasConnectedClients()) {
            val deliveredNetwork = client.sendAction("127.0.0.1", config.openBikeServerPort, action)
            if (deliveredNetwork) return true
        }

        // 2. Optional calibrated screen-tap fallback only when explicitly enabled for MyWhoosh on this device.
        val isLocalMyWhoosh = config.myWhooshLocation == MyWhooshLocation.ON_THIS_DEVICE
        if (isLocalMyWhoosh && config.useMyShiftTouchFallbackWhenNoClient &&
            (action == MyWhooshAction.SHIFT_UP || action == MyWhooshAction.SHIFT_DOWN)
        ) {
            val service = accessibilityServiceSupplier()
            if (service != null) {
                return MyShiftGestureFallback.performTouchAction(service, action, config)
            } else {
                DiagnosticLogger.warn(LogCategory.MYWHOOSH, "Touch shift fallback enabled, but Accessibility Service is off")
            }
        }

        val err = client.lastServerErrorFlow.value
        if (err != null) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "Shift blocked ($action): $err")
        } else {
            DiagnosticLogger.warn(
                LogCategory.MYWHOOSH,
                "Shift ignored ($action): MyWhoosh is not connected yet. Open MyWhoosh → Devices → Controllers and select '${OpenBikeProtocol.SERVICE_NAME_ANDROID}' (port ${config.openBikeServerPort})."
            )
        }
        return false
    }
}
