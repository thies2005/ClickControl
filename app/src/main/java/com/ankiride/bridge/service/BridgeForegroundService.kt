package com.ankiride.bridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ankiride.bridge.anki.AnkiActionDispatcherImpl
import com.ankiride.bridge.ble.ZwiftBleController
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.MyWhooshNetworkDispatcherImpl
import com.ankiride.bridge.mywhoosh.OpenBikeControlClient
import com.ankiride.bridge.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BridgeForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "ankiride_bridge_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.ankiride.bridge.START"
        const val ACTION_UPDATE_CONFIG = "com.ankiride.bridge.UPDATE_CONFIG"
        const val ACTION_STOP = "com.ankiride.bridge.STOP"

        private val _isRunningFlow = MutableStateFlow(false)
        val isRunningFlow: StateFlow<Boolean> = _isRunningFlow.asStateFlow()

        private val _connectionStateFlow = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val connectionStateFlow: StateFlow<BleConnectionState> = _connectionStateFlow.asStateFlow()

        var currentConfig: AppConfiguration = AppConfiguration()
            private set

        private var activeServiceInstance: BridgeForegroundService? = null

        fun startService(context: Context, config: AppConfiguration) {
            currentConfig = config
            if (_isRunningFlow.value && activeServiceInstance != null) {
                activeServiceInstance?.bleController?.updateConfiguration(config)
                DiagnosticLogger.info(LogCategory.SYSTEM, "Updated live configuration (mode=${config.mode}, 1Pod=${config.rightPodOnlyNoZwift})")
                return
            }
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun triggerTestShift(action: MyWhooshAction) {
            val svc = activeServiceInstance ?: run {
                DiagnosticLogger.warn(LogCategory.MYWHOOSH, "Start Bridge first before testing $action")
                return
            }
            svc.serviceScope.launch {
                svc.myWhooshDispatcher?.dispatch(action, currentConfig)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var bleController: ZwiftBleController? = null
    private var openBikeServer: OpenBikeControlClient? = null
    private var myWhooshDispatcher: MyWhooshNetworkDispatcherImpl? = null
    private var ankiDispatcher: AnkiActionDispatcherImpl? = null
    private var actionRouter: ActionRouter? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeServiceInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopBridge()
            stopSelf()
            return START_NOT_STICKY
        }

        if (_isRunningFlow.value && bleController != null) {
            bleController?.updateConfiguration(currentConfig)
            return START_STICKY
        }

        startForegroundWithNotification()
        startBridge()
        return START_STICKY
    }

    private fun startForegroundWithNotification() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClickControl Active")
            .setContentText("OpenBikeControl server + Zwift Click controller running")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startBridge() {
        stopBridge()
        _isRunningFlow.value = true

        val server = OpenBikeControlClient(this, serviceScope)
        openBikeServer = server
        server.startServer(currentConfig.openBikeServerPort)

        val whoosh = MyWhooshNetworkDispatcherImpl(
            client = server,
            accessibilityServiceSupplier = { BridgeAccessibilityService.activeInstance }
        )
        val anki = AnkiActionDispatcherImpl(
            configSupplier = { currentConfig },
            serviceProvider = { BridgeAccessibilityService.activeInstance }
        )
        val ble = ZwiftBleController(this, serviceScope)
        val router = ActionRouter(
            context = this,
            scope = serviceScope,
            configSupplier = { currentConfig },
            ankiDispatcher = anki,
            myWhooshDispatcher = whoosh,
            routingEnabled = { _isRunningFlow.value && bleController === ble }
        )

        bleController = ble
        myWhooshDispatcher = whoosh
        ankiDispatcher = anki
        actionRouter = router

        serviceScope.launch {
            ble.connectionState.collect { state ->
                _connectionStateFlow.value = state
            }
        }

        serviceScope.launch {
            ble.gestureEvents.collect { event ->
                router.routeGesture(event)
            }
        }

        ble.startScanAndConnect(currentConfig)
    }

    private fun stopBridge() {
        synchronized(AndroidGenericInputDispatcher.routingLock) {
            _isRunningFlow.value = false
            AndroidGenericInputDispatcher.cancelPending()
        }
        bleController?.disconnect()
        openBikeServer?.stopServer()

        bleController = null
        openBikeServer = null
        myWhooshDispatcher = null
        ankiDispatcher?.ioExecutor?.shutdownNow()
        ankiDispatcher = null
        actionRouter = null

        _connectionStateFlow.value = BleConnectionState.DISCONNECTED
    }

    override fun onDestroy() {
        if (activeServiceInstance == this) {
            activeServiceInstance = null
        }
        stopBridge()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ClickControl Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ClickControl OpenBikeControl server and BLE routing alive in background"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
