package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.MyWhooshAction
import java.net.Inet4Address
import java.net.NetworkInterface

/** Shared OpenBikeControl binary protocol definitions and LAN discovery helpers. */
object OpenBikeProtocol {
    const val DEFAULT_PORT = 21587
    const val MAX_CONNECTED_CLIENTS = 4
    const val CLIENT_SO_TIMEOUT_MS = 60_000
    const val SERVICE_TYPE = "_openbikecontrol._tcp."
    const val SERVICE_NAME_DESKTOP = "ClickControl-Ubuntu"
    const val SERVICE_NAME_DESKTOP_OTHER = "ClickControl-Desktop"
    const val SERVICE_NAME_ANDROID = "ClickControl-Android"
    const val OPENBIKE_BLE_SERVICE_UUID = "d273f680-d548-419d-b9d1-fa0472345229"
    const val MSG_BUTTON_STATE: Byte = 0x01
    const val MSG_DEVICE_STATUS: Byte = 0x02
    const val MSG_HAPTIC_FEEDBACK: Byte = 0x03
    const val MSG_APP_INFO: Byte = 0x04
    const val STATE_PRESSED: Byte = 0x01
    const val STATE_RELEASED: Byte = 0x00
    const val PRESS_HOLD_MS = 90L

    fun desktopServiceName(osName: String = System.getProperty("os.name", "")): String =
        if (osName.lowercase().contains("linux")) SERVICE_NAME_DESKTOP else SERVICE_NAME_DESKTOP_OTHER

    fun deviceStatusPacket(): ByteArray = byteArrayOf(MSG_DEVICE_STATUS, 0x64, 0x01)

    fun buttonPacket(action: MyWhooshAction, pressed: Boolean): ByteArray =
        byteArrayOf(MSG_BUTTON_STATE, action.openBikeOpcode, if (pressed) STATE_PRESSED else STATE_RELEASED)

    fun parseAppInfo(data: ByteArray): String? {
        if (data.size < 5 || data[0] != MSG_APP_INFO) return null
        val idLen = data[2].toInt() and 0xFF
        val appId = if (3 + idLen <= data.size) String(data, 3, idLen, Charsets.UTF_8) else return null
        val verIdx = 3 + idLen
        val verLen = if (verIdx < data.size) data[verIdx].toInt() and 0xFF else 0
        val appVer = if (verIdx + 1 + verLen <= data.size) String(data, verIdx + 1, verLen, Charsets.UTF_8) else "?"
        return "$appId v$appVer"
    }

    fun findPrimaryLanIpv4Address(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
