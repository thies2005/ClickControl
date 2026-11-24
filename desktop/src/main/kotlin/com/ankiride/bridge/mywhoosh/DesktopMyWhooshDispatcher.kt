package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MyWhooshAction
import com.ankiride.bridge.core.MyWhooshActionDispatcher

class DesktopMyWhooshDispatcher(
    private val openBikeClient: OpenBikeControlClient
) : MyWhooshActionDispatcher {

    override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration): Boolean {
        if (openBikeClient.hasConnectedClients()) {
            val delivered = openBikeClient.sendAction("127.0.0.1", config.openBikeServerPort, action)
            if (delivered) return true
        }

        val err = openBikeClient.lastServerErrorFlow.value
        if (err != null) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "Shift blocked ($action): $err")
        } else {
            DiagnosticLogger.warn(
                LogCategory.MYWHOOSH,
                "Shift ignored ($action): MyWhoosh is not connected yet. In MyWhoosh → Devices → Controllers, select '${openBikeClient.serviceName}' (${openBikeClient.lanEndpointSummary(config.openBikeServerPort)})."
            )
        }
        return false
    }
}
