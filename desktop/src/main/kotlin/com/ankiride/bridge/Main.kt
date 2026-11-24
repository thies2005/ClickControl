package com.ankiride.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ankiride.bridge.anki.AnkiConnectClient
import com.ankiride.bridge.anki.DesktopAnkiDispatcher
import com.ankiride.bridge.ble.DesktopZwiftBleController
import com.ankiride.bridge.ble.PodSelectionPolicy
import com.ankiride.bridge.ble.ZwiftGestureEngine
import com.ankiride.bridge.ble.ZwiftPacketParser
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.DesktopMyWhooshDispatcher
import com.ankiride.bridge.mywhoosh.OpenBikeControlClient
import com.ankiride.bridge.mywhoosh.OpenBikeProtocol
import com.ankiride.bridge.service.DesktopActionRouter
import com.ankiride.bridge.service.DesktopGenericInputDispatcher
import com.ankiride.bridge.ui.DesktopAppUi
import com.ankiride.bridge.ui.DesktopTrayController
import com.ankiride.bridge.ui.FloatingHudDesktopWindow
import com.ankiride.bridge.ui.TraySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.imageio.ImageIO
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

private fun createWindowIconPainter(): BitmapPainter {
    val stream = DesktopTrayController::class.java.getResourceAsStream("/icons/clickcontrol.png")
    if (stream != null) {
        stream.use { input ->
            ImageIO.read(input)?.let { img -> return BitmapPainter(img.toComposeImageBitmap()) }
        }
    }
    return BitmapPainter(
        DesktopTrayController.renderCrispTrayImage(
            128,
            TraySnapshot(running = true, bleState = BleConnectionState.DISCONNECTED, activePresetId = "", hudEnabled = false, presets = emptyList())
        ).toComposeImageBitmap()
    )
}

/**
 * Headless self-test verification executed when `--self-test` CLI argument is passed.
 * Verifies:
 * 1. ZwiftPacketParser (0x23 and 0x37 packets)
 * 2. ZwiftGestureEngine (single click and 450ms long press)
 * 3. ButtonMappingStore (default actions and config persistence)
 * 4. OpenBikeControlClient (ephemeral server start & stop)
 * 5. DesktopActionRouter (gesture routing & action dispatching)
 *
 * Prints "SELF_TEST_OK" and exits with code 0 on success.
 */
fun runHeadlessSelfTest() {
    println("--- ClickControl Desktop Headless Self-Test ---")

    // 1. Verify ZwiftPacketParser
    val zPacket = byteArrayOf(
        0x23, 0x08,
        0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F
    )
    val parsedMaskZ = ZwiftPacketParser.parseActiveButtonMask(zPacket)
    check(parsedMaskZ == ClickButton.Z.bitmask) {
        "ZwiftPacketParser failed on Z packet: expected ${ClickButton.Z.bitmask}, got $parsedMaskZ"
    }

    val shiftUpLegacy = byteArrayOf(0x37, 0x08, 0x00)
    val shiftUpMask = ZwiftPacketParser.parseActiveButtonMask(shiftUpLegacy)
    check(shiftUpMask == ClickButton.SHIFT_UP_RIGHT.bitmask) {
        "ZwiftPacketParser failed on 0x37 Shift Up: expected ${ClickButton.SHIFT_UP_RIGHT.bitmask}, got $shiftUpMask"
    }

    val shiftDownLegacy = byteArrayOf(0x37, 0x08, 0x01)
    val shiftDownMask = ZwiftPacketParser.parseActiveButtonMask(shiftDownLegacy)
    check(shiftDownMask == ClickButton.SHIFT_DOWN_LEFT.bitmask) {
        "ZwiftPacketParser failed on 0x37 Shift Down: expected ${ClickButton.SHIFT_DOWN_LEFT.bitmask}, got $shiftDownMask"
    }
    println("✓ ZwiftPacketParser verification passed")

    // 2. Verify ZwiftGestureEngine
    var detectedEvent: ButtonGestureEvent? = null
    val gestureEngine = ZwiftGestureEngine { detectedEvent = it }

    // Test single click (press and release < 450ms)
    gestureEngine.processButtonStates(mapOf(ClickButton.Z to true), nowMs = 1000L)
    gestureEngine.processButtonStates(mapOf(ClickButton.Z to false), nowMs = 1150L)
    check(detectedEvent != null && detectedEvent?.type == PressType.SINGLE_CLICK && detectedEvent?.button == ClickButton.Z) {
        "ZwiftGestureEngine failed single click test: $detectedEvent"
    }

    // Test long press (press and hold >= 450ms)
    detectedEvent = null
    gestureEngine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 2000L)
    gestureEngine.processButtonStates(mapOf(ClickButton.B to true), nowMs = 2500L)
    check(detectedEvent != null && detectedEvent?.type == PressType.LONG_PRESS && detectedEvent?.button == ClickButton.B) {
        "ZwiftGestureEngine failed long press test: $detectedEvent"
    }
    println("✓ ZwiftGestureEngine verification passed")

    // 3. Verify PresetStore, PodSelectionPolicy & ButtonMappingStore
    val tempFile = File.createTempFile("ankiride_selftest_", ".properties")
    tempFile.deleteOnExit()
    val testPreset = PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, PlatformCapabilities.desktop())
    val resolvedZ = PresetStore.resolve(
        AppConfiguration(presets = listOf(testPreset), activePresetId = testPreset.id),
        ClickButton.Z,
        PressType.SINGLE_CLICK
    )
    check(resolvedZ == MappedTargetAction.ANKI_FLIP_OR_HARD) {
        "PresetStore Z binding failed: expected ANKI_FLIP_OR_HARD, got $resolvedZ"
    }

    val singlePinCfg = AppConfiguration(
        podAssignment = PodAssignment.SINGLE_CONTROLLER,
        pinnedLeftPodMac = "02:00:00:00:00:01"
    )
    check(PodSelectionPolicy.sideFor("02:00:00:00:00:01", 0x0B, singlePinCfg) == PodSelectionPolicy.Side.LEFT) {
        "PodSelectionPolicy failed pinned 0x0B left controller"
    }
    check(PodSelectionPolicy.sideFor("11:22:33:44:55:66", 0x07, singlePinCfg) == null) {
        "PodSelectionPolicy failed to isolate second controller when pinned"
    }

    val testConfig = AppConfiguration(
        presets = listOf(testPreset),
        activePresetId = testPreset.id,
        defaultPresetId = testPreset.id,
        podAssignment = PodAssignment.SINGLE_CONTROLLER,
        startMinimizedToTray = true,
        ubuntuAnkiConnectHost = "127.0.0.1",
        ubuntuAnkiConnectPort = 8765,
        floatingHudPillEnabled = true
    )
    ButtonMappingStore.saveConfiguration(testConfig, tempFile)
    val loadedConfig = ButtonMappingStore.loadConfiguration(tempFile)
    check(
        loadedConfig.podAssignment == PodAssignment.SINGLE_CONTROLLER &&
            loadedConfig.defaultPresetId == testPreset.id &&
            loadedConfig.startMinimizedToTray &&
            loadedConfig.ubuntuAnkiConnectPort == 8765 &&
            loadedConfig.floatingHudPillEnabled
    ) {
        "ButtonMappingStore persistence verification failed: $loadedConfig"
    }
    tempFile.delete()
    println("✓ PresetStore, PodSelectionPolicy & ButtonMappingStore verification passed")

    // 4. Verify OpenBikeControlClient start/stop
    val openBikeClient = OpenBikeControlClient()
    openBikeClient.startServer(0)
    Thread.sleep(150L)
    check(!openBikeClient.hasConnectedClients()) {
        "OpenBikeControlClient expected 0 connected clients initially"
    }
    openBikeClient.stopServer()
    println("✓ OpenBikeControlClient verification passed")

    // 5. Verify DesktopActionRouter
    var routedAnki: AnkiAction? = null
    var routedMyWhoosh: MyWhooshAction? = null
    val fakeAnki = object : AnkiActionDispatcher {
        override fun dispatch(action: AnkiAction): Boolean {
            routedAnki = action
            return true
        }
    }
    val fakeMyWhoosh = object : MyWhooshActionDispatcher {
        override suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration): Boolean {
            routedMyWhoosh = action
            return true
        }
    }
    val testScope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
    val router = DesktopActionRouter(
        scope = testScope,
        configSupplier = { testConfig },
        ankiDispatcher = fakeAnki,
        myWhooshDispatcher = fakeMyWhoosh
    )
    router.routeGesture(ButtonGestureEvent(ClickButton.Z, PressType.SINGLE_CLICK))
    check(router.lastRoutedActionLabel.value == MappedTargetAction.ANKI_FLIP_OR_HARD.label) {
        "DesktopActionRouter failed label check: expected '${MappedTargetAction.ANKI_FLIP_OR_HARD.label}', got '${router.lastRoutedActionLabel.value}'"
    }
    println("✓ DesktopActionRouter verification passed")

    println("SELF_TEST_OK")
    exitProcess(0)
}

fun main(args: Array<String>) {
    if (args.contains("--live-input-test")) {
        // Explicit, opt-in integration hook: key A, pointer, click LEFT TOP WIDTH HEIGHT, scroll DIR [AMOUNT], or sequence.
        val i = args.indexOf("--live-input-test")
        val action = args.getOrNull(i + 1) ?: error("Expected key, click, pointer, scroll, or sequence")
        Thread.sleep(3000) // Allow the operator to focus the target window.
        try {
            when (action) {
                "key" -> com.ankiride.bridge.service.DesktopGenericInputDispatcher.executeNow(
                    com.ankiride.bridge.core.MappedTargetAction.KEYBOARD_INPUT,
                    com.ankiride.bridge.core.InputConfig(key = com.ankiride.bridge.core.PortableKey.valueOf(args[i + 2])))
                "pointer" -> com.ankiride.bridge.service.DesktopGenericInputDispatcher.executeNow(
                    com.ankiride.bridge.core.MappedTargetAction.SCREEN_CLICK,
                    com.ankiride.bridge.core.InputConfig(clickAtPointer = true))
                "click" -> com.ankiride.bridge.service.DesktopGenericInputDispatcher.executeNow(
                    com.ankiride.bridge.core.MappedTargetAction.SCREEN_CLICK,
                    com.ankiride.bridge.core.InputConfig(args[i + 2].toFloat(), args[i + 3].toFloat(), args[i + 4].toFloat(), args[i + 5].toFloat()))
                "scroll" -> com.ankiride.bridge.service.DesktopGenericInputDispatcher.executeNow(
                    com.ankiride.bridge.core.MappedTargetAction.SCREEN_SCROLL,
                    com.ankiride.bridge.core.InputConfig(
                        clickAtPointer = true,
                        scrollDirection = com.ankiride.bridge.core.ScrollDirection.valueOf(args.getOrNull(i + 2)?.uppercase() ?: "DOWN"),
                        scrollAmount = args.getOrNull(i + 3)?.toIntOrNull()?.coerceIn(1, 20) ?: 3
                    ))
                "sequence" -> {
                    val dispatcher = com.ankiride.bridge.service.DesktopGenericInputDispatcher
                    dispatcher.executeNow(com.ankiride.bridge.core.MappedTargetAction.KEYBOARD_INPUT,
                        com.ankiride.bridge.core.InputConfig(key = com.ankiride.bridge.core.PortableKey.A))
                    Thread.sleep(250)
                    dispatcher.executeNow(com.ankiride.bridge.core.MappedTargetAction.SCREEN_CLICK,
                        com.ankiride.bridge.core.InputConfig(.19f, .19f, .02f, .02f))
                    Thread.sleep(250)
                    dispatcher.executeNow(com.ankiride.bridge.core.MappedTargetAction.SCREEN_CLICK,
                        com.ankiride.bridge.core.InputConfig(.79f, .79f, .02f, .02f))
                    Thread.sleep(250)
                    dispatcher.executeNow(com.ankiride.bridge.core.MappedTargetAction.SCREEN_CLICK,
                        com.ankiride.bridge.core.InputConfig(clickAtPointer = true))
                }
                else -> error("Expected key, click, pointer, scroll, or sequence")
            }
            // Keep an owned virtual device alive until the compositor consumes the final event.
            Thread.sleep(250)
            println("LIVE_INPUT_SENT")
        } catch (e: Exception) {
            System.err.println("LIVE_INPUT_UNAVAILABLE: ${e.message}")
            exitProcess(1)
        }
        return
    }
    if (args.contains("--self-test")) {
        runHeadlessSelfTest()
        return
    }

    val settingsDir = ButtonMappingStore.defaultSettingsFile().parentFile
    DiagnosticLogger.configureLogFile(File(settingsDir, "logs/clickcontrol.log"))

    val instance = try {
        DesktopInstance.open(settingsDir.toPath()) ?: return
    } catch (e: IOException) {
        System.err.println("ClickControl could not open its desktop instance lock: ${e.message}")
        exitProcess(1)
    }
    instance.use { showDesktop(it, startMinimizedArg = args.contains("--minimized")) }
    exitProcess(0)
}

private fun showDesktop(instance: DesktopInstance, startMinimizedArg: Boolean = false) {
    application(exitProcessOnExit = false) {
        val initialConfig = remember {
            PresetStore.applyStartupDefaultPreset(ButtonMappingStore.loadConfiguration())
        }
        var config by remember { mutableStateOf(initialConfig) }
        var isBridgeRunning by remember { mutableStateOf(false) }
        var isMainWindowVisible by remember {
            mutableStateOf(!(startMinimizedArg || initialConfig.startMinimizedToTray))
        }
        var reopenRequests by remember { mutableIntStateOf(0) }
        var presetDirty by remember { mutableStateOf(false) }
        var confirmQuit by remember { mutableStateOf(false) }
        val mainWindowState = rememberWindowState(width = 1180.dp, height = 820.dp)

        val scope = rememberCoroutineScope()
        val openBikeClient = remember { OpenBikeControlClient() }
        val ankiConnectClient = remember { AnkiConnectClient() }
        val desktopAnkiDispatcher = remember {
            DesktopAnkiDispatcher(configProvider = { config }, ankiConnectClient = ankiConnectClient)
        }
        val desktopMyWhooshDispatcher = remember {
            DesktopMyWhooshDispatcher(openBikeClient = openBikeClient)
        }
        val actionRouter = remember {
            DesktopActionRouter(
                scope = scope,
                configSupplier = { config },
                ankiDispatcher = desktopAnkiDispatcher,
                myWhooshDispatcher = desktopMyWhooshDispatcher,
                routingEnabled = { isBridgeRunning }
            )
        }
        val bleController = remember { DesktopZwiftBleController() }

        val bleState by bleController.connectionState.collectAsState()
        val bleWorkerError by bleController.lastWorkerError.collectAsState()
        val connectedRightMac by bleController.connectedRightMac.collectAsState()
        val connectedLeftMac by bleController.connectedLeftMac.collectAsState()
        val discoveredAddresses by bleController.discoveredAddresses.collectAsState()
        val lastActionText by actionRouter.lastRoutedActionLabel.collectAsState()
        val connectedClientCount by openBikeClient.connectedClientCountFlow.collectAsState()
        val openBikeServerRunning by openBikeClient.isServerRunningFlow.collectAsState()
        val openBikeBoundPort by openBikeClient.boundPortFlow.collectAsState()
        val openBikeServerError by openBikeClient.lastServerErrorFlow.collectAsState()

        val syncOpenBikeServer: (Boolean, AppConfiguration) -> Unit = { running, cfg ->
            val shouldServe = running && PresetStore.activePresetUsesMyWhoosh(cfg)
            if (!shouldServe) {
                if (openBikeClient.isServerRunningFlow.value) {
                    DiagnosticLogger.info(
                        LogCategory.MYWHOOSH,
                        "Stopping OpenBikeControl server (active preset does not use MyWhoosh)"
                    )
                    openBikeClient.stopServer()
                }
            } else if (!openBikeClient.isServerRunningFlow.value || openBikeClient.boundPort() != cfg.openBikeServerPort) {
                openBikeClient.stopServer()
                openBikeClient.startServer(cfg.openBikeServerPort)
            }
        }

        val updateConfig: (AppConfiguration) -> Unit = { newConfig ->
            config = newConfig
            ButtonMappingStore.saveConfiguration(newConfig)
            bleController.updateConfiguration(newConfig)
            syncOpenBikeServer(isBridgeRunning, newConfig)
        }

        fun stopBridge() {
            synchronized(DesktopGenericInputDispatcher.routingLock) {
                isBridgeRunning = false
                DesktopGenericInputDispatcher.cancelAllPending()
            }
            bleController.disconnect()
            openBikeClient.stopServer()
        }

        val toggleBridge: () -> Unit = {
            if (isBridgeRunning) {
                stopBridge()
                DiagnosticLogger.info(LogCategory.SYSTEM, "Bridge stopped by user")
            } else {
                isBridgeRunning = true
                syncOpenBikeServer(true, config)
                bleController.startScanAndConnect(config)
                DiagnosticLogger.info(LogCategory.SYSTEM, "Bridge started (preset='${PresetStore.activePresetName(config)}')")
            }
        }

        LaunchedEffect(Unit) {
            if (config.autoStartOnControllerWake) {
                toggleBridge()
            }
        }

        LaunchedEffect(bleController) {
            bleController.gestureEvents.collect { event ->
                actionRouter.routeGesture(event)
            }
        }

        fun showWindow() {
            isMainWindowVisible = true
            mainWindowState.isMinimized = false
            reopenRequests++
        }

        fun quit() {
            stopBridge()
            exitApplication()
        }

        fun requestQuit() {
            if (presetDirty) {
                showWindow()
                confirmQuit = true
            } else {
                quit()
            }
        }

        LaunchedEffect(instance) {
            while (instance.awaitReopen()) showWindow()
        }

        val traySnapshot = TraySnapshot(
            running = isBridgeRunning,
            bleState = bleState,
            activePresetId = config.activePresetId ?: "",
            hudEnabled = config.floatingHudPillEnabled,
            presets = config.presets.map { it.id to it.name }
        )
        val trayController = remember {
            DesktopTrayController(
                onShowWindow = ::showWindow,
                onToggleBridge = toggleBridge,
                onSelectPreset = { id ->
                    updateConfig(config.copy(activePresetId = id))
                },
                onToggleHud = {
                    updateConfig(config.copy(floatingHudPillEnabled = !config.floatingHudPillEnabled))
                },
                onQuit = ::requestQuit
            )
        }
        DisposableEffect(Unit) {
            trayController.start(traySnapshot)
            if (!isMainWindowVisible) {
                if (trayController.isActive) {
                    DiagnosticLogger.info(LogCategory.SYSTEM, "ClickControl started minimized in the top-bar tray")
                } else {
                    DiagnosticLogger.warn(LogCategory.SYSTEM, "System tray unavailable; showing ClickControl window")
                    isMainWindowVisible = true
                }
            }
            onDispose { trayController.close() }
        }
        LaunchedEffect(traySnapshot) {
            trayController.update(traySnapshot)
        }

        val windowIconPainter = remember { createWindowIconPainter() }
        Window(
            visible = isMainWindowVisible,
            onCloseRequest = {
                if (isBridgeRunning && trayController.isActive) {
                    isMainWindowVisible = false
                } else if (isBridgeRunning) {
                    mainWindowState.isMinimized = true
                } else {
                    requestQuit()
                }
            },
            state = mainWindowState,
            title = "ClickControl",
            icon = windowIconPainter
        ) {
            LaunchedEffect(reopenRequests, isMainWindowVisible) {
                if (isMainWindowVisible && reopenRequests > 0) {
                    mainWindowState.isMinimized = false
                    window.isVisible = true
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.isAlwaysOnTop = true
                    window.toFront()
                    window.requestFocus()
                    window.isAlwaysOnTop = false
                }
            }
            MaterialTheme(colorScheme = com.ankiride.bridge.ui.ClickControlColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DesktopAppUi(
                        config = config,
                        onConfigChange = updateConfig,
                        isBridgeRunning = isBridgeRunning,
                        onToggleBridge = toggleBridge,
                        bleState = bleState,
                        connectedRightMac = connectedRightMac,
                        connectedLeftMac = connectedLeftMac,
                        hasMyWhooshClient = connectedClientCount > 0,
                        openBikeServerRunning = openBikeServerRunning,
                        openBikeEndpoint = openBikeClient.lanEndpointSummary(openBikeBoundPort ?: config.openBikeServerPort),
                        openBikeServiceName = openBikeClient.serviceName,
                        openBikeError = openBikeServerError,
                        bleWorkerError = bleWorkerError,
                        lastRoutedActionLabel = lastActionText,
                        onMinimize = { mainWindowState.isMinimized = true },
                        onEnsureBridgeStarted = {
                            if (!isBridgeRunning) {
                                toggleBridge()
                            } else {
                                syncOpenBikeServer(true, config)
                            }
                        },
                        onPresetDirtyChange = { presetDirty = it },
                        discoveredAddresses = discoveredAddresses,
                        onSimulateGesture = { button, pressType ->
                            bleController.emitSimulatedGesture(button, pressType)
                        },
                        onTestAnkiAction = { ankiAction ->
                            scope.launch(Dispatchers.IO) {
                                desktopAnkiDispatcher.dispatch(ankiAction)
                            }
                        },
                        onTestMyWhooshAction = { mwAction ->
                            scope.launch {
                                desktopMyWhooshDispatcher.dispatch(mwAction, config)
                            }
                        }
                    )
                    if (confirmQuit) AlertDialog(
                        onDismissRequest = { confirmQuit = false },
                        title = { Text("Discard unsaved changes?") },
                        text = { Text("Save your preset before quitting, or discard the changes.") },
                        confirmButton = { TextButton(onClick = ::quit) { Text("Discard and quit") } },
                        dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep editing") } }
                    )
                }
            }
        }

        // Floating Mini HUD Window
        FloatingHudDesktopWindow(
            enabled = config.floatingHudPillEnabled,
            bleState = bleState,
            presetName = PresetStore.activePresetName(config),
            lastActionText = lastActionText,
            onClose = {
                updateConfig(config.copy(floatingHudPillEnabled = false))
            }
        )
    }
}
