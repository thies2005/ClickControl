package com.ankiride.bridge.mywhoosh

import java.net.InetAddress

object LanAddressValidator {
    private val IPV4_LITERAL_REGEX = Regex(
        "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    )

    fun normalizeIpv4MappedHost(host: String): String {
        val trimmed = host.trim()
        return when {
            trimmed.startsWith("::ffff:", ignoreCase = true) -> trimmed.substring(7)
            trimmed.startsWith("0:0:0:0:0:ffff:", ignoreCase = true) -> trimmed.substring(15)
            else -> trimmed
        }
    }

    fun isLocalOrLanAddress(host: String): Boolean {
        val normalized = normalizeIpv4MappedHost(host)
        if (!IPV4_LITERAL_REGEX.matches(normalized)) return false
        return try {
            val addr = InetAddress.getByName(normalized)
            addr.isLoopbackAddress || addr.isSiteLocalAddress
        } catch (_: Exception) {
            false
        }
    }

    fun isAllowedTargetHost(host: String): Boolean = isLocalOrLanAddress(host)
}
