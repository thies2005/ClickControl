package com.ankiride.bridge.ble

import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.ClickButton
import com.ankiride.bridge.core.PodAssignment

/** Conservative controller selection and per-side pin isolation. */
object PodSelectionPolicy {
    enum class Side { LEFT, RIGHT }

    fun sideFor(address: String, podType: Byte?, config: AppConfiguration): Side? {
        val rightPin = config.pinnedRightPodMac?.takeIf { it.isNotBlank() }
        val leftPin = config.pinnedLeftPodMac?.takeIf { it.isNotBlank() }
        if (address.equals(rightPin, true) && address.equals(leftPin, true)) return null

        return when (config.podAssignment) {
            PodAssignment.SINGLE_CONTROLLER -> {
                if ((rightPin != null || leftPin != null) &&
                    !address.equals(rightPin, true) &&
                    !address.equals(leftPin, true)
                ) {
                    return null
                }
                when {
                    address.equals(leftPin, true) -> Side.LEFT
                    address.equals(rightPin, true) -> Side.RIGHT
                    podType == 0x08.toByte() || podType == 0x0B.toByte() -> Side.LEFT
                    else -> Side.RIGHT
                }
            }

            PodAssignment.RIGHT_CONTROLLER_ONLY -> {
                when {
                    address.equals(rightPin, true) -> Side.RIGHT
                    rightPin == null && podType == 0x07.toByte() -> Side.RIGHT
                    else -> null
                }
            }

            PodAssignment.BOTH_CONTROLLERS -> {
                val side = when {
                    address.equals(rightPin, true) -> Side.RIGHT
                    address.equals(leftPin, true) -> Side.LEFT
                    podType == 0x07.toByte() -> Side.RIGHT
                    podType == 0x08.toByte() || podType == 0x0B.toByte() -> Side.LEFT
                    else -> return null
                }
                val sidePin = if (side == Side.RIGHT) rightPin else leftPin
                if (sidePin != null && !address.equals(sidePin, true)) null else side
            }
        }
    }

    fun filterMask(mask: Int, side: Side, assignment: PodAssignment): Int {
        if (assignment == PodAssignment.SINGLE_CONTROLLER) return mask
        val sideMask = ClickButton.entries.filter { it.isRightPod == (side == Side.RIGHT) }
            .fold(0) { combined, button -> combined or button.bitmask }
        return mask and sideMask
    }
}
