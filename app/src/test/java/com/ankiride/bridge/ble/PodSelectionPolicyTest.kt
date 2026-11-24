package com.ankiride.bridge.ble

import com.ankiride.bridge.core.*
import org.junit.Assert.*
import org.junit.Test

class PodSelectionPolicyTest {
    @Test fun rightControllerOnlyRejectsLeftAndUnidentifiedUnlessPinnedRight() {
        val config = AppConfiguration(podAssignment = PodAssignment.RIGHT_CONTROLLER_ONLY)
        assertNull(PodSelectionPolicy.sideFor("left", 0x08, config))
        assertNull(PodSelectionPolicy.sideFor("unknown", 0x09, config))
        assertEquals(PodSelectionPolicy.Side.RIGHT, PodSelectionPolicy.sideFor("right", 0x07, config))
        assertEquals(PodSelectionPolicy.Side.RIGHT, PodSelectionPolicy.sideFor("unknown", 0x09, config.copy(pinnedRightPodMac = "UNKNOWN")))
    }

    @Test fun pinsConstrainEachSideAndCanOverrideAdvertisedSide() {
        val config = AppConfiguration(podAssignment = PodAssignment.BOTH_CONTROLLERS, pinnedRightPodMac = "A")
        assertEquals(PodSelectionPolicy.Side.RIGHT, PodSelectionPolicy.sideFor("a", 0x08, config))
        assertNull(PodSelectionPolicy.sideFor("other", 0x07, config))
        assertEquals(PodSelectionPolicy.Side.LEFT, PodSelectionPolicy.sideFor("left", 0x08, config))
        assertNull(PodSelectionPolicy.sideFor("a", 0x07, config.copy(pinnedLeftPodMac = "A")))
    }

    @Test fun mirroredBitsCannotCrossSideInTwoControllerAndRightOnlyModes() {
        val mask = ClickButton.Y.bitmask or ClickButton.SHIFT_DOWN_LEFT.bitmask
        assertEquals(ClickButton.Y.bitmask, PodSelectionPolicy.filterMask(mask, PodSelectionPolicy.Side.RIGHT, PodAssignment.RIGHT_CONTROLLER_ONLY))
        assertEquals(ClickButton.SHIFT_DOWN_LEFT.bitmask, PodSelectionPolicy.filterMask(mask, PodSelectionPolicy.Side.LEFT, PodAssignment.BOTH_CONTROLLERS))
        assertEquals(mask, PodSelectionPolicy.filterMask(mask, PodSelectionPolicy.Side.RIGHT, PodAssignment.SINGLE_CONTROLLER))
    }

    @Test fun fourArrowClickType0x0BSelectsLeftAndRespectsSingleControllerPinIsolation() {
        val singlePinned = AppConfiguration(podAssignment = PodAssignment.SINGLE_CONTROLLER, pinnedLeftPodMac = "02:00:00:00:00:01")
        assertEquals(PodSelectionPolicy.Side.LEFT, PodSelectionPolicy.sideFor("02:00:00:00:00:01", 0x0B, singlePinned))
        assertNull(PodSelectionPolicy.sideFor("11:22:33:44:55:66", 0x07, singlePinned))

        val bothPods = AppConfiguration(podAssignment = PodAssignment.BOTH_CONTROLLERS)
        assertEquals(PodSelectionPolicy.Side.LEFT, PodSelectionPolicy.sideFor("02:00:00:00:00:01", 0x0B, bothPods))
    }
}
