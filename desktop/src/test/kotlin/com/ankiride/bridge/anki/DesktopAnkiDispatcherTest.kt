package com.ankiride.bridge.anki

import com.ankiride.bridge.core.AnkiAction
import com.ankiride.bridge.core.AppConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.concurrent.Executors

class DesktopAnkiDispatcherTest {

    @Test
    fun testDispatchWithConfiguredHostAndPort() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.submit {
                val socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                var line = reader.readLine()
                var len = 0
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        len = line.substringAfter(":").trim().toInt()
                    }
                    line = reader.readLine()
                }
                val body = CharArray(len)
                reader.read(body, 0, len)
                val bodyStr = String(body)
                assertTrue(bodyStr.contains("\"action\":\"guiShowAnswer\""))

                val resp = """{"result":null,"error":null}"""
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${resp.length}\r\nConnection: close\r\n\r\n$resp".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                socket.close()
            }

            var currentConfig = AppConfiguration(
                ubuntuAnkiConnectHost = "127.0.0.1",
                ubuntuAnkiConnectPort = port
            )
            val dispatcher = DesktopAnkiDispatcher(configProvider = { currentConfig })

            val ok = dispatcher.dispatch(AnkiAction.FLIP_CARD)
            assertTrue(ok)
        } finally {
            server.close()
            executor.shutdown()
        }
    }

    @Test
    fun testDispatchWithBlankHostDefaultsToLoopback() {
        val server = ServerSocket(0)
        val port = server.localPort
        val executor = Executors.newSingleThreadExecutor()

        try {
            executor.submit {
                val socket = server.accept()
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                var line = reader.readLine()
                var len = 0
                while (line != null && line.isNotEmpty()) {
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        len = line.substringAfter(":").trim().toInt()
                    }
                    line = reader.readLine()
                }
                if (len > 0) {
                    val body = CharArray(len)
                    reader.read(body, 0, len)
                }
                val resp = """{"result":null,"error":null}"""
                socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${resp.length}\r\nConnection: close\r\n\r\n$resp".toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
                socket.close()
            }

            val currentConfig = AppConfiguration(
                ubuntuAnkiConnectHost = "   ", // blank -> should default to 127.0.0.1
                ubuntuAnkiConnectPort = port
            )
            val dispatcher = DesktopAnkiDispatcher(configProvider = { currentConfig })

            val ok = dispatcher.dispatch(AnkiAction.FLIP_CARD)
            assertTrue(ok)
        } finally {
            server.close()
            executor.shutdown()
        }
    }

    @Test
    fun testDispatchFailsWhenServerUnreachable() {
        // Choose a closed port
        val server = ServerSocket(0)
        val closedPort = server.localPort
        server.close()

        val config = AppConfiguration(
            ubuntuAnkiConnectHost = "127.0.0.1",
            ubuntuAnkiConnectPort = closedPort
        )
        val dispatcher = DesktopAnkiDispatcher(configProvider = { config })

        val ok = dispatcher.dispatch(AnkiAction.FLIP_CARD)
        assertFalse(ok)
    }

    @Test
    fun testAnkiWebPresetRoutesViaAnkiWebKeyboardBridge() {
        val preset = com.ankiride.bridge.core.PresetStore.createFromTemplate(
            com.ankiride.bridge.core.PresetTemplate.ANKI_WEB,
            com.ankiride.bridge.core.PlatformCapabilities.desktop()
        )
        val sentKeys = mutableListOf<com.ankiride.bridge.core.PortableKey>()
        val webBridge = AnkiWebKeyboardBridge(sendKey = { sentKeys.add(it); true })
        var activeConfig = AppConfiguration(presets = listOf(preset), activePresetId = preset.id)
        val dispatcher = DesktopAnkiDispatcher(configProvider = { activeConfig }, ankiWebBridge = webBridge)

        assertTrue(dispatcher.dispatch(AnkiAction.FLIP_OR_GOOD))
        assertEquals(listOf(com.ankiride.bridge.core.PortableKey.SPACE), sentKeys)
        assertTrue(webBridge.isAnswerVisible)

        val preset2 = preset.copy(id = "web-2")
        activeConfig = AppConfiguration(presets = listOf(preset, preset2), activePresetId = preset2.id)
        assertTrue(dispatcher.dispatch(AnkiAction.FLIP_OR_GOOD))
        assertEquals(listOf(com.ankiride.bridge.core.PortableKey.SPACE, com.ankiride.bridge.core.PortableKey.SPACE), sentKeys)
    }

    @Test
    fun queuedActionsArePreserved() {
        val dispatcher = DesktopAnkiDispatcher(configProvider = { AppConfiguration() })
        val latch = java.util.concurrent.CountDownLatch(1)
        dispatcher.executor.execute { latch.await() }
        dispatcher.dispatchAsync(AnkiAction.FLIP_CARD)
        dispatcher.dispatchAsync(AnkiAction.FLIP_OR_GOOD)
        assertEquals(2, dispatcher.executor.queue.size)
        dispatcher.executor.queue.clear()
        latch.countDown()
        dispatcher.executor.shutdown()
        assertTrue(dispatcher.executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS))
    }
}
