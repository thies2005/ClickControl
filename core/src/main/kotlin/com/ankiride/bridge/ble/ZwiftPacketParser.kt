package com.ankiride.bridge.ble

import com.ankiride.bridge.core.ClickButton
import java.util.EnumMap

object ZwiftPacketParser {
    const val MSG_KEYPAD_STATUS: Byte = 0x23
    const val MSG_CLICK_V1_SHIFT: Byte = 0x37
    const val PROTO_FIELD_1_VARINT: Byte = 0x08
    const val PROTO_FIELD_2_LEN_DELIM: Byte = 0x12
    const val MAX_PACKET_SIZE = 64
    const val MAX_VARINT_BYTES = 5

    fun parseActiveButtonMask(payload: ByteArray): Int? {
        if (payload.size < 3 || payload.size > MAX_PACKET_SIZE) return null

        // Support legacy 0x37 Click packet if emitted by top rocker
        if (payload[0] == MSG_CLICK_V1_SHIFT && payload[1] == PROTO_FIELD_1_VARINT) {
            val stateByte = payload[2].toInt() and 0xFF
            return when (stateByte) {
                0x00 -> ClickButton.SHIFT_UP_RIGHT.bitmask
                0x01 -> ClickButton.SHIFT_DOWN_LEFT.bitmask
                else -> 0
            }
        }

        if (payload[0] != MSG_KEYPAD_STATUS || payload[1] != PROTO_FIELD_1_VARINT) return null

        var result = 0
        var shift = 0
        var index = 2
        var bytesRead = 0

        while (index < payload.size) {
            if (bytesRead >= MAX_VARINT_BYTES) return null
            val b = payload[index].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            bytesRead++
            index++
            if ((b and 0x80) == 0) {
                var activeMask = result.inv()
                // Also inspect optional Field 2 (0x12) analog/paddle group if present
                if (index < payload.size && payload[index] == PROTO_FIELD_2_LEN_DELIM) {
                    activeMask = activeMask or parseField2AnalogPaddles(payload, index + 1)
                }
                return activeMask
            }
            shift += 7
        }
        return null
    }

    /**
     * Parses Field 2 (0x12) RideAnalogKeyGroup for any non-zero analog paddle deflection.
     * Format: [groupLen] (0x0A [entryLen] 0x08 [locationId] 0x10 [zigzagSint32])*
     */
    private fun parseField2AnalogPaddles(payload: ByteArray, startIdx: Int): Int {
        if (startIdx >= payload.size) return 0
        val groupLen = payload[startIdx].toInt() and 0xFF
        var idx = startIdx + 1
        val endIdx = minOf(payload.size, idx + groupLen)
        var extraMask = 0

        while (idx + 5 <= endIdx) {
            if (payload[idx] != 0x0A.toByte()) break
            val entryLen = payload[idx + 1].toInt() and 0xFF
            if (entryLen < 4 || idx + 2 + entryLen > endIdx) break
            val locTag = payload[idx + 2].toInt() and 0xFF
            val locId = payload[idx + 3].toInt() and 0xFF
            val valTag = payload[idx + 4].toInt() and 0xFF
            val rawVal = payload[idx + 5].toInt() and 0xFF
            if (locTag == 0x08 && valTag == 0x10 && rawVal != 0) {
                if (locId == 0) extraMask = extraMask or ClickButton.SHIFT_DOWN_LEFT.bitmask
                if (locId == 1) extraMask = extraMask or ClickButton.SHIFT_UP_RIGHT.bitmask
            }
            idx += 2 + entryLen
        }
        return extraMask
    }

    fun parse(data: ByteArray): Map<ClickButton, Boolean> {
        val result = EnumMap<ClickButton, Boolean>(ClickButton::class.java)
        val mask = parseActiveButtonMask(data)
        if (mask == null) {
            ClickButton.entries.forEach { result[it] = false }
            return result
        }
        ClickButton.entries.forEach { button ->
            result[button] = (mask and button.bitmask) != 0
        }
        return result
    }
}
