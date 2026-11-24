package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

open class DesktopMdnsAdvertiser {
    private var jmdns: JmDNS? = null

    open fun registerOpenBikeService(port: Int = OpenBikeProtocol.DEFAULT_PORT) {
        try {
            val hostIp = OpenBikeProtocol.findPrimaryLanIpv4Address()
            val lanAddress = if (hostIp != null) InetAddress.getByName(hostIp) else InetAddress.getLocalHost()
            val serviceName = OpenBikeProtocol.desktopServiceName()
            val mdns = JmDNS.create(lanAddress, serviceName)
            val props = mapOf(
                "version" to "1",
                "id" to "clickcontroldesktop01",
                "name" to serviceName,
                "service-uuids" to OpenBikeProtocol.OPENBIKE_BLE_SERVICE_UUID,
                "manufacturer" to "ClickControl",
                "model" to "Zwift Click v2 Desktop Bridge"
            )
            val serviceInfo = ServiceInfo.create(
                "_openbikecontrol._tcp.local.",
                serviceName,
                port,
                0,
                0,
                props
            )
            mdns.registerService(serviceInfo)
            jmdns = mdns
            DiagnosticLogger.info(
                LogCategory.MYWHOOSH,
                "mDNS advertising '$serviceName' (_openbikecontrol._tcp.local.) at ${lanAddress.hostAddress}:$port"
            )
        } catch (e: Exception) {
            DiagnosticLogger.warn(LogCategory.MYWHOOSH, "mDNS registration failed (direct IP pairing still works): ${e.message}")
        }
    }

    open fun unregister() {
        try {
            jmdns?.unregisterAllServices()
            jmdns?.close()
        } catch (_: Exception) {
        }
        jmdns = null
    }

    open fun close() = unregister()
}
