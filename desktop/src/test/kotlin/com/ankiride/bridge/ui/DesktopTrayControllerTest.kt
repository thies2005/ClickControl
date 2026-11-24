package com.ankiride.bridge.ui

import com.ankiride.bridge.core.BleConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class DesktopTrayControllerTest {
    @Test
    fun snapshotSerializesStateAndRendersTransparentMarginsWithCenteredArtwork() {
        val snapshot = TraySnapshot(
            running = true,
            bleState = BleConnectionState.CONNECTED_LEFT_ONLY,
            activePresetId = "ride-web-slides",
            hudEnabled = false,
            presets = listOf("ride-web-slides" to "Ride + Web, Docs & Slides")
        )
        val json = snapshot.toJson()
        assertTrue(json.contains("\"running\":true"))
        assertTrue(json.contains("\"connected\":true"))
        assertTrue(json.contains("\"activePresetId\":\"ride-web-slides\""))

        val img = DesktopTrayController.renderCrispTrayImage(64, snapshot)
        assertEquals(64, img.width)
        assertEquals(64, img.height)
        // The upper-left cutout distinguishes the directional pad from a tile.
        assertEquals(0, Color(img.getRGB(16, 16), true).alpha)
        assertEquals(Color(0x27, 0x2C, 0x33).rgb, img.getRGB(32, 32))
        assertEquals(Color(0x2E, 0xC2, 0x7E).rgb, img.getRGB(52, 52))

        val idleIcon = DesktopTrayController.renderCrispTrayImage(16, snapshot.copy(running = false))
        assertEquals(0, Color(idleIcon.getRGB(13, 13), true).alpha)

        val trayScript = javaClass.getResourceAsStream("/tray/linux_sni_tray.py")
        assertTrue("Expected /tray/linux_sni_tray.py on classpath", trayScript != null && trayScript.readBytes().isNotEmpty())
        val appIcon = javaClass.getResourceAsStream("/icons/clickcontrol.png")
        assertTrue("Expected /icons/clickcontrol.png on classpath", appIcon != null && appIcon.readBytes().isNotEmpty())
    }
}
