package com.ankiride.bridge.ble

import com.ankiride.bridge.core.BleConnectionState
import com.ankiride.bridge.core.ButtonGestureEvent
import com.ankiride.bridge.core.ClickButton
import com.ankiride.bridge.core.PressType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method

class DesktopZwiftBleControllerTest {

    private lateinit var testScope: CoroutineScope
    private lateinit var controller: DesktopZwiftBleController

    private fun encodeVarint(value: Int): ByteArray {
        val out = mutableListOf<Byte>()
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0) {
                out.add((v and 0x7F).toByte())
                break
            } else {
                out.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
        }
        return out.toByteArray()
    }

    private fun buildPacket(buttonMapWire: Int): ByteArray {
        val varintBytes = encodeVarint(buttonMapWire)
        val packet = ByteArray(2 + varintBytes.size)
        packet[0] = ZwiftPacketParser.MSG_KEYPAD_STATUS // 0x23
        packet[1] = ZwiftPacketParser.PROTO_FIELD_1_VARINT // 0x08
        System.arraycopy(varintBytes, 0, packet, 2, varintBytes.size)
        return packet
    }

    @Before
    fun setUp() {
        testScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        controller = DesktopZwiftBleController(scope = testScope)
        val parse = controller.javaClass.getDeclaredMethod("parseWorkerLine", String::class.java).apply { isAccessible = true }
        parse.invoke(controller, "STATE CONNECTED_RIGHT AA:BB:CC:DD:EE:01")
        parse.invoke(controller, "STATE CONNECTED_LEFT AA:BB:CC:DD:EE:02")
    }

    @After
    fun tearDown() {
        controller.disconnect()
        testScope.cancel()
    }

    @Test
    fun testHandleRawNotificationSinglePod() {
        val macRight = "AA:BB:CC:DD:EE:01"
        // Physical packet for Z button pressed: [23 08 FF FE FF FF 0F]
        val packetZ = byteArrayOf(
            0x23, 0x08,
            0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F
        )

        controller.handleRawNotification(macRight, packetZ)

        assertEquals(ClickButton.Z.bitmask, controller.podActiveMasks[macRight])
    }

    @Test
    fun testIdleLeftPodDoesNotOverwriteActiveRightPod() {
        val macRight = "AA:BB:CC:DD:EE:01"
        val macLeft = "AA:BB:CC:DD:EE:02"

        // 1. Right Pod presses and holds Z (Top Orange)
        val packetZ = byteArrayOf(
            0x23, 0x08,
            0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F
        )
        controller.handleRawNotification(macRight, packetZ, nowMs = 1000L)
        assertEquals(ClickButton.Z.bitmask, controller.podActiveMasks[macRight])

        // 2. Idle Left Pod sends all-released packet: [23 08 FF FF FF FF 0F]
        val packetAllReleased = byteArrayOf(
            0x23, 0x08,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F
        )
        controller.handleRawNotification(macLeft, packetAllReleased, nowMs = 1020L)

        // Verify Left Pod active mask is 0, but Right Pod active mask remains Z
        assertEquals(0, controller.podActiveMasks[macLeft])
        assertEquals(ClickButton.Z.bitmask, controller.podActiveMasks[macRight])

        // 3. Right Pod holds SHIFT_UP_RIGHT (+ Rocker: wire = ~(0x1000))
        val packetShiftUp = buildPacket(ClickButton.SHIFT_UP_RIGHT.bitmask.inv())
        controller.handleRawNotification(macRight, packetShiftUp, nowMs = 1050L)
        assertEquals(ClickButton.SHIFT_UP_RIGHT.bitmask, controller.podActiveMasks[macRight])

        // Left Pod sends all-released again
        controller.handleRawNotification(macLeft, packetAllReleased, nowMs = 1070L)
        assertEquals(0, controller.podActiveMasks[macLeft])
        assertEquals(ClickButton.SHIFT_UP_RIGHT.bitmask, controller.podActiveMasks[macRight])
    }

    @Test
    fun testDualPodSimultaneousButtonsBothActive() {
        val macRight = "AA:BB:CC:DD:EE:01"
        val macLeft = "AA:BB:CC:DD:EE:02"

        // Right Pod holds B (0x0020)
        val packetB = buildPacket(ClickButton.B.bitmask.inv())
        controller.handleRawNotification(macRight, packetB, nowMs = 1000L)

        // Left Pod holds D-pad LEFT (0x0001)
        val packetLeft = buildPacket(ClickButton.LEFT.bitmask.inv())
        controller.handleRawNotification(macLeft, packetLeft, nowMs = 1010L)

        assertEquals(ClickButton.B.bitmask, controller.podActiveMasks[macRight])
        assertEquals(ClickButton.LEFT.bitmask, controller.podActiveMasks[macLeft])
    }

    @Test
    fun testHeartbeatPacketsFiltered() {
        val mac = "AA:BB:CC:DD:EE:01"

        // Periodic keepalive 0x15
        controller.handleRawNotification(mac, byteArrayOf(0x15, 0x01, 0x02))
        assertNull(controller.podActiveMasks[mac])

        // Periodic keepalive 0x19
        controller.handleRawNotification(mac, byteArrayOf(0x19, 0x00))
        assertNull(controller.podActiveMasks[mac])

        // Empty packet
        controller.handleRawNotification(mac, byteArrayOf())
        assertNull(controller.podActiveMasks[mac])
    }

    @Test
    fun testEmitSimulatedGestureAllButtons() = runBlocking {
        val receivedEvents = mutableListOf<ButtonGestureEvent>()
        val collectorJob = launch(Dispatchers.Default) {
            controller.gestureEvents.collect { receivedEvents.add(it) }
        }
        delay(150L)

        val testButtons = ClickButton.entries
        for (button in testButtons) {
            controller.emitSimulatedGesture(button, PressType.SINGLE_CLICK)
            delay(5L)
            controller.emitSimulatedGesture(button, PressType.LONG_PRESS)
            delay(5L)
        }

        val start = System.currentTimeMillis()
        while (receivedEvents.size < testButtons.size * 2 && System.currentTimeMillis() - start < 3000L) {
            delay(20L)
        }
        collectorJob.cancel()

        assertEquals(testButtons.size * 2, receivedEvents.size)

        var idx = 0
        for (button in testButtons) {
            assertEquals(button, receivedEvents[idx].button)
            assertEquals(PressType.SINGLE_CLICK, receivedEvents[idx].type)
            idx++

            assertEquals(button, receivedEvents[idx].button)
            assertEquals(PressType.LONG_PRESS, receivedEvents[idx].type)
            idx++
        }
    }

    @Test
    fun testZwiftBleWorkerResourceExistsAndCompiles() {
        val stream = javaClass.getResourceAsStream("/ble/zwift_ble_worker.py")
        assertNotNull("Resource /ble/zwift_ble_worker.py must exist on classpath", stream)

        val bytes = stream!!.readBytes()
        assertTrue("Resource /ble/zwift_ble_worker.py must not be empty", bytes.isNotEmpty())

        val tempFile = File.createTempFile("zwift_ble_worker_test", ".py")
        try {
            FileOutputStream(tempFile).use { it.write(bytes) }

            // Verify with python3 -m py_compile
            val pb = ProcessBuilder("python3", "-m", "py_compile", tempFile.absolutePath)
            val proc = pb.start()
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("python3 py_compile should complete within 5 seconds", finished)
            assertEquals("python3 -m py_compile must exit with code 0 (valid Python syntax)", 0, proc.exitValue())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testParseWorkerLineStateTransitions() {
        val parseWorkerLineMethod: Method = DesktopZwiftBleController::class.java.getDeclaredMethod(
            "parseWorkerLine",
            String::class.java
        )
        parseWorkerLineMethod.isAccessible = true

        controller.disconnect()
        fun parse(line: String) {
            parseWorkerLineMethod.invoke(controller, line)
        }

        parse("STATE SCANNING")
        assertEquals(BleConnectionState.SCANNING, controller.connectionState.value)

        parse("STATE DISCOVERED F4:C4:59:0C:6F:29")
        assertTrue(controller.discoveredAddresses.value.contains("F4:C4:59:0C:6F:29"))

        parse("STATE CONNECTING")
        assertEquals(BleConnectionState.CONNECTING, controller.connectionState.value)

        parse("STATE HANDSHAKING")
        assertEquals(BleConnectionState.HANDSHAKING, controller.connectionState.value)

        parse("STATE CONNECTED_RIGHT_ONLY AA:BB:CC:DD:EE:01")
        assertEquals(BleConnectionState.CONNECTED_RIGHT_ONLY, controller.connectionState.value)
        assertEquals("AA:BB:CC:DD:EE:01", controller.connectedRightMac.value)

        parse("STATE CONNECTED_BOTH_PODS AA:BB:CC:DD:EE:02")
        assertEquals(BleConnectionState.CONNECTED_BOTH_PODS, controller.connectionState.value)
        assertEquals("AA:BB:CC:DD:EE:02", controller.connectedLeftMac.value)

        // PACKET line parsed
        parse("PACKET AA:BB:CC:DD:EE:01 2308FFFEFFFF0F")
        assertEquals(ClickButton.Z.bitmask, controller.podActiveMasks["AA:BB:CC:DD:EE:01"])

        // Disconnect left pod
        parse("STATE DISCONNECTED AA:BB:CC:DD:EE:02")
        assertNull(controller.connectedLeftMac.value)
        assertEquals(BleConnectionState.CONNECTED_RIGHT_ONLY, controller.connectionState.value)

        // Disconnect right pod
        parse("STATE DISCONNECTED AA:BB:CC:DD:EE:01")
        assertNull(controller.connectedRightMac.value)
        assertEquals(BleConnectionState.DISCONNECTED, controller.connectionState.value)

        // ERROR line
        parse("STATE ERROR bleak_not_installed")
        assertEquals(BleConnectionState.ERROR, controller.connectionState.value)
    }

    @Test
    fun testDisconnectResetsControllerState() {
        controller.handleRawNotification(
            "AA:BB:CC:DD:EE:01",
            byteArrayOf(0x23, 0x08, 0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F)
        )
        assertEquals(1, controller.podActiveMasks.size)

        controller.disconnect()

        assertEquals(0, controller.podActiveMasks.size)
        assertNull(controller.connectedRightMac.value)
        assertNull(controller.connectedLeftMac.value)
        assertEquals(BleConnectionState.DISCONNECTED, controller.connectionState.value)
    }

    @Test
    fun testIsValidMacOrUuid() {
        // Valid MAC addresses
        assertTrue(DesktopZwiftBleController.isValidMacOrUuid("F4:C4:59:0C:6F:29"))
        assertTrue(DesktopZwiftBleController.isValidMacOrUuid("AA-BB-CC-DD-EE-FF"))
        assertTrue(DesktopZwiftBleController.isValidMacOrUuid("01:23:45:67:89:ab"))

        // Valid CoreBluetooth UUID
        assertTrue(DesktopZwiftBleController.isValidMacOrUuid("D273F680-D548-419D-B9D1-FA0472345229"))
        assertTrue(DesktopZwiftBleController.isValidMacOrUuid("d273f680-d548-419d-b9d1-fa0472345229"))

        // Invalid / shell injection / argument injection
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("--help"))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("; rm -rf /"))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("F4:C4:59:0C:6F:29 --evil"))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("`reboot`"))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("$(whoami)"))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid(""))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid("   "))
        assertFalse(DesktopZwiftBleController.isValidMacOrUuid(null))
    }

    @Test
    fun testWorkerExtractionCreatesInHomeAnkiRideBinWithPosixPermissions() {
        val extracted = controller.extractWorkerScript()
        assertNotNull(extracted)
        assertTrue(extracted!!.exists())

        // Must be in ~/.ankiride/bin/zwift_ble_worker.py, never in /tmp
        val expectedDir = File(System.getProperty("user.home"), ".ankiride/bin")
        assertEquals(expectedDir.absolutePath, extracted.parentFile.absolutePath)
        assertEquals("zwift_ble_worker.py", extracted.name)
        assertFalse(extracted.absolutePath.startsWith("/tmp"))

        // Check POSIX permissions if on a POSIX filesystem (Linux / macOS)
        val isPosix = java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
        if (isPosix) {
            val perms = java.nio.file.Files.getPosixFilePermissions(extracted.toPath())
            val expectedPerms = java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")
            assertEquals("File permissions must be 0700 (rwx------)", expectedPerms, perms)

            val dirPerms = java.nio.file.Files.getPosixFilePermissions(expectedDir.toPath())
            assertEquals("Directory permissions must be 0700 (rwx------)", expectedPerms, dirPerms)
        }
    }

    @Test
    fun testFindNativeWorkerBinaryStagesNonExecutableResourceToHomeBin() {
        val tempResDir = java.nio.file.Files.createTempDirectory("ankiride_res_test").toFile()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val binaryName = if (isWindows) "zwift_ble_worker.exe" else "zwift_ble_worker"
        val nonExecResource = File(tempResDir, binaryName)
        val stagedHomeFile = File(File(System.getProperty("user.home"), ".ankiride/bin"), binaryName)
        val backupFile = if (stagedHomeFile.exists()) {
            File.createTempFile("zwift_ble_worker_backup", ".bin").also {
                stagedHomeFile.copyTo(it, overwrite = true)
            }
        } else null
        val prevProp = System.getProperty("compose.application.resources.dir")
        try {
            nonExecResource.writeText("#!/bin/sh\necho OK\n")
            nonExecResource.setExecutable(false)
            System.setProperty("compose.application.resources.dir", tempResDir.absolutePath)

            val resolved = controller.findNativeWorkerBinary()
            assertNotNull("findNativeWorkerBinary must resolve even when bundled resource lacks +x bit", resolved)
            assertTrue("Resolved binary must be executable", resolved!!.canExecute())
        } finally {
            if (prevProp != null) {
                System.setProperty("compose.application.resources.dir", prevProp)
            } else {
                System.clearProperty("compose.application.resources.dir")
            }
            if (backupFile != null && backupFile.exists()) {
                backupFile.copyTo(stagedHomeFile, overwrite = true)
                stagedHomeFile.setExecutable(true, true)
                backupFile.delete()
            } else {
                stagedHomeFile.delete()
            }
            nonExecResource.delete()
            tempResDir.delete()
        }
    }
    @Test
    fun rejectsUnknownPodAndOppositeSideBits() {
        controller.handleRawNotification("AA:BB:CC:DD:EE:03", buildPacket(ClickButton.B.bitmask.inv()))
        assertNull(controller.podActiveMasks["AA:BB:CC:DD:EE:03"])
        controller.handleRawNotification("AA:BB:CC:DD:EE:02", buildPacket(ClickButton.B.bitmask.inv()))
        assertEquals(0, controller.podActiveMasks["AA:BB:CC:DD:EE:02"])
    }
    @Test
    fun leftPodCanConnectFirstWithoutBecomingRight() {
        controller.disconnect()
        val parse = controller.javaClass.getDeclaredMethod("parseWorkerLine", String::class.java).apply { isAccessible = true }
        parse.invoke(controller, "STATE CONNECTED_LEFT AA:BB:CC:DD:EE:02")
        assertNull(controller.connectedRightMac.value)
        assertEquals("AA:BB:CC:DD:EE:02", controller.connectedLeftMac.value)
        assertEquals(BleConnectionState.CONNECTED_LEFT_ONLY, controller.connectionState.value)
        parse.invoke(controller, "STATE CONNECTED_RIGHT AA:BB:CC:DD:EE:01")
        assertEquals(BleConnectionState.CONNECTED_BOTH_PODS, controller.connectionState.value)
        parse.invoke(controller, "STATE DISCONNECTED AA:BB:CC:DD:EE:01")
        assertEquals(BleConnectionState.CONNECTED_LEFT_ONLY, controller.connectionState.value)
    }
    @Test
    fun sideProtocolProbeAcceptsOnlyMatchingWorkersAndTimesOut() {
        if (System.getProperty("os.name").lowercase().contains("win")) return
        fun worker(body: String) = File.createTempFile("ble-probe", ".sh").apply {
            writeText("#!/bin/sh\n$body\n"); setExecutable(true); deleteOnExit()
        }
        assertTrue(controller.supportsSideProtocol(worker("echo CLICKCONTROL_BLE_PROTOCOL_3")))
        assertFalse(controller.supportsSideProtocol(worker("echo old-worker")))
        assertFalse(controller.supportsSideProtocol(worker("exec sleep 10")))
    }

    @Test
    fun rockerPacketsAreTranslatedToTheirSourceSide() {
        val right = "AA:BB:CC:DD:EE:01"
        val left = "AA:BB:CC:DD:EE:02"
        controller.handleRawNotification(left, byteArrayOf(0x37, 0x08, 0x00))
        assertEquals(ClickButton.SHIFT_UP_LEFT.bitmask, controller.podActiveMasks[left])
        controller.handleRawNotification(right, byteArrayOf(0x37, 0x08, 0x01))
        assertEquals(ClickButton.SHIFT_DOWN_RIGHT.bitmask, controller.podActiveMasks[right])
    }
    @Test
    fun legacySharedPreservesOppositeSideBitsFromOneLink() {
        controller.updateConfiguration(com.ankiride.bridge.core.AppConfiguration(podAssignment = com.ankiride.bridge.core.PodAssignment.LEGACY_SHARED))
        val right = "AA:BB:CC:DD:EE:01"
        controller.handleRawNotification(right, buildPacket(ClickButton.SHIFT_DOWN_LEFT.bitmask.inv()))
        assertEquals(ClickButton.SHIFT_DOWN_LEFT.bitmask, controller.podActiveMasks[right])
    }
}
