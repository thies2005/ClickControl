package com.ankiride.bridge.mywhoosh

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MyWhooshAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class OpenBikeControlClient(
    private val context: Context? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    companion object {
        const val DEFAULT_PORT = OpenBikeProtocol.DEFAULT_PORT
        const val MAX_CONNECTED_CLIENTS = OpenBikeProtocol.MAX_CONNECTED_CLIENTS
        const val CLIENT_SO_TIMEOUT_MS = OpenBikeProtocol.CLIENT_SO_TIMEOUT_MS
        const val SERVICE_TYPE = OpenBikeProtocol.SERVICE_TYPE
        const val SERVICE_NAME = OpenBikeProtocol.SERVICE_NAME_ANDROID
        const val OPENBIKE_BLE_SERVICE_UUID = OpenBikeProtocol.OPENBIKE_BLE_SERVICE_UUID
        const val MSG_BUTTON_STATE = OpenBikeProtocol.MSG_BUTTON_STATE
        const val MSG_DEVICE_STATUS = OpenBikeProtocol.MSG_DEVICE_STATUS
        const val MSG_HAPTIC_FEEDBACK = OpenBikeProtocol.MSG_HAPTIC_FEEDBACK
        const val MSG_APP_INFO = OpenBikeProtocol.MSG_APP_INFO
        const val STATE_PRESSED = OpenBikeProtocol.STATE_PRESSED
        const val STATE_RELEASED = OpenBikeProtocol.STATE_RELEASED
        const val PRESS_HOLD_MS = OpenBikeProtocol.PRESS_HOLD_MS
    }

    private val lifecycleLock = Any()
    private var serverGeneration = 0L
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    val connectedClients = CopyOnWriteArrayList<Socket>()

    private val _connectedClientCountFlow = MutableStateFlow(0)
    val connectedClientCountFlow: StateFlow<Int> = _connectedClientCountFlow.asStateFlow()

    private val _isServerRunningFlow = MutableStateFlow(false)
    val isServerRunningFlow: StateFlow<Boolean> = _isServerRunningFlow.asStateFlow()

    private val _boundPortFlow = MutableStateFlow<Int?>(null)
    val boundPortFlow: StateFlow<Int?> = _boundPortFlow.asStateFlow()

    private val _lastServerErrorFlow = MutableStateFlow<String?>(null)
    val lastServerErrorFlow: StateFlow<String?> = _lastServerErrorFlow.asStateFlow()

    fun boundPort(): Int? = _boundPortFlow.value

    fun startServer(port: Int = DEFAULT_PORT, bindLocalhostOnly: Boolean = false) {
        synchronized(lifecycleLock) {
            if (acceptJob?.isActive == true || serverSocket != null) return
            val generation = ++serverGeneration
            _lastServerErrorFlow.value = null
            acceptJob = scope.launch {
                var ownedSocket: ServerSocket? = null
                try {
                    val bindAddr = if (bindLocalhostOnly) {
                        InetAddress.getByName("127.0.0.1")
                    } else {
                        InetAddress.getByName("0.0.0.0")
                    }
                    val srv = ServerSocket(port, 50, bindAddr)
                    ownedSocket = srv
                    synchronized(lifecycleLock) {
                        if (generation != serverGeneration || !isActive) return@launch
                        serverSocket = srv
                        _isServerRunningFlow.value = true
                        _boundPortFlow.value = srv.localPort
                        _lastServerErrorFlow.value = null
                        DiagnosticLogger.info(
                            LogCategory.MYWHOOSH,
                            "OpenBikeControl TCP Server '$SERVICE_NAME' listening on port ${srv.localPort}"
                        )
                        registerMdnsService(srv.localPort)
                    }
                    while (isActive && !srv.isClosed) {
                        val client = srv.accept()
                        synchronized(lifecycleLock) {
                            if (generation != serverGeneration || !isActive) {
                                client.close()
                                return@launch
                            }
                            val rawHost = client.inetAddress?.hostAddress ?: ""
                            if (LanAddressValidator.isLocalOrLanAddress(rawHost)) {
                                client.tcpNoDelay = true
                                client.soTimeout = CLIENT_SO_TIMEOUT_MS
                                connectedClients.removeIf { it.isClosed || !it.isConnected }
                                while (connectedClients.size >= MAX_CONNECTED_CLIENTS) {
                                    val oldest = if (connectedClients.isNotEmpty()) connectedClients.removeAt(0) else break
                                    runCatching { oldest.close() }
                                }
                                connectedClients.add(client)
                                _connectedClientCountFlow.value = connectedClients.count { !it.isClosed && it.isConnected }
                                DiagnosticLogger.info(
                                    LogCategory.MYWHOOSH,
                                    "MyWhoosh connected from $rawHost (clients=${connectedClients.size})"
                                )
                                sendRawBytes(client, OpenBikeProtocol.deviceStatusPacket())
                                listenToClientMessages(client, rawHost)
                            } else {
                                DiagnosticLogger.warn(LogCategory.MYWHOOSH, "Rejected non-LAN socket from $rawHost")
                                client.close()
                            }
                        }
                    }
                } catch (_: BindException) {
                    val msg = "OpenBikeControl port $port is already in use by another app. Stop the other app or change the port."
                    _lastServerErrorFlow.value = msg
                    DiagnosticLogger.error(LogCategory.MYWHOOSH, msg)
                } catch (e: Exception) {
                    if (isActive && generation == serverGeneration) {
                        val msg = "OpenBikeControl server error: ${e.message}"
                        _lastServerErrorFlow.value = msg
                        DiagnosticLogger.error(LogCategory.MYWHOOSH, msg)
                    }
                } finally {
                    runCatching { ownedSocket?.close() }
                    synchronized(lifecycleLock) {
                        if (generation == serverGeneration) {
                            serverSocket = null
                            _isServerRunningFlow.value = false
                            _boundPortFlow.value = null
                            registrationListener?.let { listener ->
                                runCatching { nsdManager?.unregisterService(listener) }
                            }
                            registrationListener = null
                        }
                    }
                }
            }
        }
    }

    private fun listenToClientMessages(client: Socket, host: String = client.inetAddress?.hostAddress ?: "") {
        scope.launch(Dispatchers.IO) {
            val buf = ByteArray(256)
            var windowStartMs = System.currentTimeMillis()
            var logCountInWindow = 0
            try {
                val input: InputStream = client.getInputStream()
                while (isActive && !client.isClosed) {
                    val readCount = try {
                        input.read(buf)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    if (readCount <= 0) break
                    val packet = buf.copyOfRange(0, readCount)

                    val now = System.currentTimeMillis()
                    if (now - windowStartMs >= 1000L) {
                        windowStartMs = now
                        logCountInWindow = 0
                    }
                    val canLog = if (logCountInWindow < 20) {
                        logCountInWindow++
                        true
                    } else {
                        false
                    }
                    parseIncomingAppPacket(host, packet, canLog)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { client.close() }
                connectedClients.remove(client)
                _connectedClientCountFlow.value = connectedClients.count { !it.isClosed && it.isConnected }
                DiagnosticLogger.warn(LogCategory.MYWHOOSH, "MyWhoosh client $host disconnected")
            }
        }
    }

    private fun parseIncomingAppPacket(host: String, data: ByteArray, canLog: Boolean = true) {
        if (data.isEmpty()) return
        when (data[0]) {
            MSG_APP_INFO -> {
                val parsed = OpenBikeProtocol.parseAppInfo(data)
                if (canLog) {
                    if (parsed != null) {
                        DiagnosticLogger.info(LogCategory.MYWHOOSH, "Received 0x04 AppInfo from $host: $parsed")
                    } else {
                        val hex = data.joinToString(" ") { "%02X".format(it) }
                        DiagnosticLogger.info(LogCategory.MYWHOOSH, "Received 0x04 AppInfo from $host: [$hex]")
                    }
                }
            }
            MSG_HAPTIC_FEEDBACK -> {
                if (canLog) {
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    DiagnosticLogger.info(LogCategory.MYWHOOSH, "MyWhoosh confirmed action with 0x03 Haptic packet: [$hex]")
                }
            }
            else -> {
                if (canLog) {
                    val hex = data.joinToString(" ") { "%02X".format(it) }
                    DiagnosticLogger.info(LogCategory.MYWHOOSH, "Incoming packet from MyWhoosh ($host): [$hex]")
                }
            }
        }
    }

    fun connectedClientCount(): Int = connectedClients.count { !it.isClosed && it.isConnected }
    fun hasConnectedClients(): Boolean = connectedClientCount() > 0

    suspend fun sendAction(ip: String, port: Int, action: MyWhooshAction): Boolean = withContext(Dispatchers.IO) {
        if (!LanAddressValidator.isLocalOrLanAddress(ip)) return@withContext false

        val pressPacket = OpenBikeProtocol.buttonPacket(action, pressed = true)
        val releasePacket = OpenBikeProtocol.buttonPacket(action, pressed = false)

        var delivered = false
        for (client in connectedClients) {
            if (client.isClosed || !client.isConnected) {
                connectedClients.remove(client)
                _connectedClientCountFlow.value = connectedClients.count { !it.isClosed && it.isConnected }
                continue
            }
            if (sendRawBytes(client, pressPacket)) {
                delivered = true
            } else {
                connectedClients.remove(client)
                _connectedClientCountFlow.value = connectedClients.count { !it.isClosed && it.isConnected }
            }
        }
        if (delivered) {
            DiagnosticLogger.info(
                LogCategory.MYWHOOSH,
                "Sent OpenBikeControl 0x01 PRESS [01 ${"%02X".format(action.openBikeOpcode)} 01] (${action.name}) -> holding ${PRESS_HOLD_MS}ms"
            )
            delay(PRESS_HOLD_MS)
            for (client in connectedClients) {
                sendRawBytes(client, releasePacket)
            }
        }
        delivered
    }

    private fun sendRawBytes(socket: Socket, bytes: ByteArray): Boolean {
        return try {
            val out: OutputStream = socket.getOutputStream()
            synchronized(out) {
                out.write(bytes)
                out.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun registerMdnsService(port: Int) {
        val ctx = context ?: return
        val manager = ctx.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        nsdManager = manager

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("version", "1")
            setAttribute("id", "ankirideandroid01")
            setAttribute("name", SERVICE_NAME)
            setAttribute("service-uuids", OPENBIKE_BLE_SERVICE_UUID)
            setAttribute("manufacturer", "ClickControl")
            setAttribute("model", "Zwift Click v2 Android Bridge")
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo?) {
                DiagnosticLogger.info(LogCategory.MYWHOOSH, "mDNS advertising '${info?.serviceName}' ($SERVICE_TYPE) on port $port")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo?, errorCode: Int) {
                DiagnosticLogger.warn(LogCategory.MYWHOOSH, "mDNS registration failed (errorCode=$errorCode); direct localhost/IP still works")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo?) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo?, errorCode: Int) {}
        }
        registrationListener = listener
        try {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            DiagnosticLogger.warn(LogCategory.MYWHOOSH, "Failed to register mDNS: ${e.message}")
        }
    }

    fun stopServer() {
        synchronized(lifecycleLock) {
            ++serverGeneration
            acceptJob?.cancel()
            acceptJob = null
            registrationListener?.let { listener ->
                runCatching { nsdManager?.unregisterService(listener) }
            }
            registrationListener = null
            connectedClients.forEach { runCatching { it.close() } }
            connectedClients.clear()
            _connectedClientCountFlow.value = 0
            _isServerRunningFlow.value = false
            _boundPortFlow.value = null
            _lastServerErrorFlow.value = null
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    fun close() {
        stopServer()
    }
}
