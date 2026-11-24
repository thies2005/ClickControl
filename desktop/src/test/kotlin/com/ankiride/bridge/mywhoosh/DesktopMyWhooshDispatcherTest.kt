package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MyWhooshAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

class DesktopMyWhooshDispatcherTest {

    private class NoOpMdnsAdvertiser : DesktopMdnsAdvertiser() {
        override fun registerOpenBikeService(port: Int) {}
        override fun unregister() {}
        override fun close() {}
    }

    private lateinit var openBikeClient: OpenBikeControlClient
    private lateinit var dispatcher: DesktopMyWhooshDispatcher

    @Before
    fun setUp() {
        openBikeClient = OpenBikeControlClient(mdnsAdvertiser = NoOpMdnsAdvertiser())
        dispatcher = DesktopMyWhooshDispatcher(openBikeClient)
    }

    @After
    fun tearDown() {
        openBikeClient.stopServer()
    }

    private suspend fun awaitServerPort(server: OpenBikeControlClient, timeoutMs: Long = 2000L): Int {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val port = server.boundPort()
            if (port != null && port > 0) return port
            delay(25L)
        }
        error("Timed out waiting for OpenBikeControl server to bind port")
    }

    @Test
    fun testDispatchRoutesToTcpClientWhenConnected() = runBlocking {
        openBikeClient.startServer(0)
        val port = awaitServerPort(openBikeClient)

        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 3000
        val input = socket.getInputStream()

        // Read 3-byte handshake [0x02, 0x64, 0x01]
        val handshake = ByteArray(3)
        var total = 0
        while (total < 3) {
            val r = input.read(handshake, total, 3 - total)
            if (r < 0) break
            total += r
        }
        assertArrayEquals(byteArrayOf(0x02, 0x64, 0x01), handshake)
        delay(50L)

        assertTrue(openBikeClient.hasConnectedClients())

        val config = AppConfiguration(openBikeServerPort = port)
        val ok = dispatcher.dispatch(MyWhooshAction.SHIFT_UP, config)
        assertTrue("Dispatch should succeed when client is connected over TCP", ok)

        // Read 6 bytes of SHIFT_UP press + release
        val actionBytes = ByteArray(6)
        total = 0
        while (total < 6) {
            val r = input.read(actionBytes, total, 6 - total)
            if (r < 0) break
            total += r
        }
        assertArrayEquals(byteArrayOf(0x01, 0x01, 0x01, 0x01, 0x01, 0x00), actionBytes)

        socket.close()
    }

    @Test
    fun testDispatchLogsClearMessageWhenNoMyWhooshClientConnected() = runBlocking {
        DiagnosticLogger.clear()
        val config = AppConfiguration()
        val ok = dispatcher.dispatch(MyWhooshAction.SHIFT_UP, config)
        assertFalse(ok)
        val logs = DiagnosticLogger.logs.value
        assertTrue(logs.any { it.category == LogCategory.MYWHOOSH && it.message.contains("MyWhoosh is not connected yet") })
    }

    @Test
    fun testPortConflictReportsActionableBindError() = runBlocking {
        DiagnosticLogger.clear()
        ServerSocket(0).use { busy ->
            val port = busy.localPort
            openBikeClient.startServer(port)
            val deadline = System.currentTimeMillis() + 2000L
            while (openBikeClient.lastServerErrorFlow.value == null && System.currentTimeMillis() < deadline) {
                delay(25L)
            }
            val err = openBikeClient.lastServerErrorFlow.value
            assertNotNull(err)
            assertTrue(err!!.contains("already in use"))
            val ok = dispatcher.dispatch(MyWhooshAction.SHIFT_UP, AppConfiguration(openBikeServerPort = port))
            assertFalse(ok)
        }
    }
}
