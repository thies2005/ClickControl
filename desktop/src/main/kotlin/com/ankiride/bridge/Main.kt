package com.ankiride.bridge

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.ankiride.bridge.anki.AnkiConnectClient
import com.ankiride.bridge.anki.DesktopAnkiDispatcher
import com.ankiride.bridge.ble.DesktopZwiftBleController
import com.ankiride.bridge.ble.ZwiftGestureEngine
import com.ankiride.bridge.ble.ZwiftPacketParser
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.DesktopMyWhooshDispatcher
import com.ankiride.bridge.mywhoosh.OpenBikeControlClient
import com.ankiride.bridge.service.DesktopActionRouter
import com.ankiride.bridge.ui.DesktopAppUi
import com.ankiride.bridge.ui.FloatingHudDesktopWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.system.exitProcess

/**
 * Creates a standalone in-memory programmatic tray icon painter.
 * Avoids crashing on missing external image resources.
 */
fun createTrayPainter(): androidx.compose.ui.graphics.painter.Painter {
    val size = 32
    val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(0x38, 0xBD, 0xF8) // Sky Blue
        g.fillRoundRect(2, 2, size - 4, size - 4, 8, 8)
        g.color = Color(0x0F, 0x17, 0x2A) // Dark Slate
        g.font = Font("SansSerif", Font.BOLD, 19)
        g.drawString("A", 9, 23)
    } finally {
        g.dispose()
    }
    return BitmapPainter(img.toComposeImageBitmap())
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

    // 3. Verify ButtonMappingStore
    val resolvedZ = ButtonMappingStore.resolveAction(
        OperatingMode.MODE_1_TABLET_SPLIT_SCREEN,
        ClickButton.Z,
        PressType.SINGLE_CLICK,
        emptyMap()
    )
    check(resolvedZ == MappedTargetAction.MW_SHIFT_UP) {
        "ButtonMappingStore default Z failed: expected MW_SHIFT_UP, got $resolvedZ"
    }

    val tempFile = File.createTempFile("ankiride_selftest_", ".properties")
    tempFile.deleteOnExit()
    val testConfig = AppConfiguration(
        mode = OperatingMode.MODE_5_UBUNTU_ANKICONNECT,
        ubuntuAnkiConnectHost = "127.0.0.1",
        ubuntuAnkiConnectPort = 8765,
        floatingHudPillEnabled = true
    )
    ButtonMappingStore.saveConfiguration(testConfig, tempFile)
    val loadedConfig = ButtonMappingStore.loadConfiguration(tempFile)
    check(
        loadedConfig.mode == OperatingMode.MODE_5_UBUNTU_ANKICONNECT &&
            loadedConfig.ubuntuAnkiConnectPort == 8765 &&
            loadedConfig.floatingHudPillEnabled
    ) {
        "ButtonMappingStore persistence verification failed: $loadedConfig"
    }
    tempFile.delete()
    println("✓ ButtonMappingStore verification passed")

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
        // Explicit, opt-in integration hook: key A, pointer, click LEFT TOP WIDTH HEIGHT, or sequence.
        val i = args.indexOf("--live-input-test")
        val action = args.getOrNull(i + 1) ?: error("Expected key or click")
        Thread.sleep(3000) // Allow the operator to focus the target window.
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
            else -> error("Expected key, click, pointer, or sequence")
        }
        // Keep an owned virtual device alive until the compositor consumes the final event.
        Thread.sleep(250)
        println("LIVE_INPUT_SENT")
        return
    }
    if (args.contains("--self-test")) {
        runHeadlessSelfTest()
        return
    }

    application {
        var config by remember { mutableStateOf(ButtonMappingStore.loadConfiguration()) }
        var isBridgeRunning by remember { mutableStateOf(false) }
        var isMainWindowVisible by remember { mutableStateOf(true) }
        var presetDirty by remember { mutableStateOf(false) }
        var confirmQuit by remember { mutableStateOf(false) }

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
        val connectedRightMac by bleController.connectedRightMac.collectAsState()
        val connectedLeftMac by bleController.connectedLeftMac.collectAsState()
        val lastActionText by actionRouter.lastRoutedActionLabel.collectAsState()
        val connectedClientCount by openBikeClient.connectedClientCountFlow.collectAsState()

        val updateConfig: (AppConfiguration) -> Unit = { newConfig ->
            config = newConfig
            ButtonMappingStore.saveConfiguration(newConfig)
            bleController.updateConfiguration(newConfig)
        }

        val toggleBridge: () -> Unit = {
            if (isBridgeRunning) {
                synchronized(com.ankiride.bridge.service.DesktopGenericInputDispatcher.routingLock) {
                    isBridgeRunning = false
                    com.ankiride.bridge.service.DesktopGenericInputDispatcher.cancelPending()
                }
                bleController.disconnect()
                openBikeClient.stopServer()
                DiagnosticLogger.info(LogCategory.SYSTEM, "Bridge stopped by user")
            } else {
                openBikeClient.startServer(config.openBikeServerPort)
                bleController.startScanAndConnect(config)
                isBridgeRunning = true
                DiagnosticLogger.info(LogCategory.SYSTEM, "Bridge started by user")
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

        // Programmatic Tray Icon
        val trayPainter = remember { createTrayPainter() }
        if (java.awt.SystemTray.isSupported()) Tray(
            icon = trayPainter,
            tooltip = "ClickControl — Zwift Click v2",
            menu = {
                Item(
                    text = if (isBridgeRunning) "Bridge: Active (Click to Stop)" else "Bridge: Stopped (Click to Start)",
                    onClick = toggleBridge
                )
                Item(
                    text = if (isMainWindowVisible) "Hide Main Window" else "Show Main Window",
                    onClick = { isMainWindowVisible = !isMainWindowVisible }
                )
                Item(
                    text = if (config.floatingHudPillEnabled) "Hide Floating HUD" else "Show Floating HUD",
                    onClick = {
                        updateConfig(config.copy(floatingHudPillEnabled = !config.floatingHudPillEnabled))
                    }
                )
                Separator()
                Item(
                    text = "Quit ClickControl",
                    onClick = {
                        if (presetDirty) { isMainWindowVisible = true; confirmQuit = true }
                        else {
                            if (isBridgeRunning) { synchronized(com.ankiride.bridge.service.DesktopGenericInputDispatcher.routingLock) { isBridgeRunning = false; com.ankiride.bridge.service.DesktopGenericInputDispatcher.cancelPending() }; bleController.disconnect(); openBikeClient.stopServer() }
                            exitApplication()
                        }
                    }
                )
            }
        )

        // Main Desktop Window
        val mainWindowState = rememberWindowState(width = 1320.dp, height = 880.dp)
        Window(
            onCloseRequest = {
                if (java.awt.SystemTray.isSupported()) isMainWindowVisible = false
                else if (presetDirty) confirmQuit = true
                else { synchronized(com.ankiride.bridge.service.DesktopGenericInputDispatcher.routingLock) { isBridgeRunning = false; com.ankiride.bridge.service.DesktopGenericInputDispatcher.cancelPending() }; bleController.disconnect(); openBikeClient.stopServer(); exitApplication() }
            },
            state = mainWindowState,
            visible = isMainWindowVisible,
            title = "ClickControl — Zwift Click v2 Desktop (Ubuntu / macOS / Windows)"
        ) {
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
                        lastRoutedActionLabel = lastActionText,
                        onMinimizeToTray = { if (java.awt.SystemTray.isSupported()) isMainWindowVisible = false },
                        onPresetDirtyChange = { presetDirty = it },
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
                        confirmButton = { TextButton(onClick = { synchronized(com.ankiride.bridge.service.DesktopGenericInputDispatcher.routingLock) { isBridgeRunning = false; com.ankiride.bridge.service.DesktopGenericInputDispatcher.cancelPending() }; bleController.disconnect(); openBikeClient.stopServer(); exitApplication() }) { Text("Discard and quit") } },
                        dismissButton = { TextButton(onClick = { confirmQuit = false }) { Text("Keep editing") } }
                    )
                }
            }
        }

        // Floating Mini HUD Window
        FloatingHudDesktopWindow(
            enabled = config.floatingHudPillEnabled,
            bleState = bleState,
            mode = config.mode,
            lastActionText = lastActionText,
            onClose = {
                updateConfig(config.copy(floatingHudPillEnabled = false))
            }
        )
    }
}
