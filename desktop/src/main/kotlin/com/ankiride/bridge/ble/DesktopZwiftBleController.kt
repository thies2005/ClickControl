package com.ankiride.bridge.ble

import com.ankiride.bridge.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap

class DesktopZwiftBleController(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : ClickBleController {

    companion object {
        val MAC_OR_UUID_REGEX = Regex("^[0-9A-Fa-f:-]{12,36}$")

        fun isValidMacOrUuid(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            return MAC_OR_UUID_REGEX.matches(value.trim())
        }
    }

    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _gestureEvents = MutableSharedFlow<ButtonGestureEvent>(extraBufferCapacity = 64)
    override val gestureEvents: SharedFlow<ButtonGestureEvent> = _gestureEvents

    private val _connectedRightMac = MutableStateFlow<String?>(null)
    val connectedRightMac: StateFlow<String?> = _connectedRightMac.asStateFlow()

    private val _connectedLeftMac = MutableStateFlow<String?>(null)
    val connectedLeftMac: StateFlow<String?> = _connectedLeftMac.asStateFlow()

    private val _discoveredAddresses = MutableStateFlow<List<String>>(emptyList())
    val discoveredAddresses: StateFlow<List<String>> = _discoveredAddresses.asStateFlow()

    val podActiveMasks = ConcurrentHashMap<String, Int>()
    private val lastLoggedPayloadHex = ConcurrentHashMap<String, String>()

    private val currentButtonMap = EnumMap<ClickButton, Boolean>(ClickButton::class.java).apply {
        ClickButton.entries.forEach { put(it, false) }
    }
    private val currentButtonMapLock = Any()

    private val gestureEngine = ZwiftGestureEngine { event ->
        DiagnosticLogger.info(
            LogCategory.BLE,
            "Gesture detected: button=${event.button.name} (${event.button.displayName}), type=${event.type.name}"
        )
        scope.launch { _gestureEvents.emit(event) }
    }

    private var workerProcess: Process? = null
    private var workerJob: Job? = null
    private var tickerJob: Job? = null
    private var currentConfig = AppConfiguration()
    @Volatile private var isRunningBridge = false

    fun updateConfiguration(config: AppConfiguration) {
        val needsRestart = isRunningBridge && (
            currentConfig.rightPodOnlyNoZwift != config.rightPodOnlyNoZwift ||
            currentConfig.podAssignment != config.podAssignment ||
            currentConfig.pinnedRightPodMac != config.pinnedRightPodMac ||
            currentConfig.pinnedLeftPodMac != config.pinnedLeftPodMac ||
            currentConfig.leftPodAutoReconnectSec != config.leftPodAutoReconnectSec
        )
        currentConfig = config
        if (needsRestart) {
            DiagnosticLogger.info(LogCategory.BLE, "BLE pod configuration changed; restarting BLE worker automatically...")
            startScanAndConnect(config)
        }
    }

    override fun startScanAndConnect(config: AppConfiguration) {
        currentConfig = config
        disconnect()

        isRunningBridge = true
        _connectionState.value = BleConnectionState.SCANNING
        DiagnosticLogger.info(LogCategory.BLE, "Starting Desktop BLE Controller (1-Pod Default=${config.rightPodOnlyNoZwift})")

        startTicker()
        startBleWorkerProcess(config)
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(25L)
                val snapshot = synchronized(currentButtonMapLock) {
                    EnumMap(currentButtonMap)
                }
                if (snapshot.values.any { it }) {
                    gestureEngine.processButtonStates(snapshot, System.currentTimeMillis())
                }
            }
        }
    }

    private fun startBleWorkerProcess(config: AppConfiguration) {
        currentConfig = config
        workerJob?.cancel()
        workerProcess?.let { proc ->
            try {
                proc.destroy()
            } catch (_: Exception) {
            }
        }
        workerProcess = null

        workerJob = scope.launch(Dispatchers.IO) {
            while (isActive && isRunningBridge) {
                var process: Process? = null
                var stderrJob: Job? = null
                try {
                    val nativeBinary = findNativeWorkerBinary()?.takeIf { supportsSideProtocol(it) }
                    val command: MutableList<String>

                    if (nativeBinary != null) {
                        DiagnosticLogger.info(LogCategory.BLE, "Spawning standalone native BLE worker binary: ${nativeBinary.absolutePath}")
                        command = mutableListOf(
                            nativeBinary.absolutePath,
                            "--right-only", (currentConfig.podAssignment == PodAssignment.RIGHT_ANKI_ONLY || (currentConfig.podAssignment == PodAssignment.LEGACY_SHARED && currentConfig.rightPodOnlyNoZwift)).toString()
                        )
                    } else {
                        val scriptFile = extractWorkerScript()
                        if (scriptFile == null || !scriptFile.exists()) {
                            DiagnosticLogger.error(LogCategory.BLE, "Unable to extract or locate zwift_ble_worker.py")
                            _connectionState.value = BleConnectionState.ERROR
                            if (isRunningBridge) delay(2000L)
                            continue
                        }

                        val pythonCmd = getOrBootstrapPythonExecutable()
                        if (pythonCmd == null) {
                            DiagnosticLogger.error(LogCategory.BLE, "Python executable with 'bleak' could not be resolved or bootstrapped")
                            _connectionState.value = BleConnectionState.ERROR
                            if (isRunningBridge) delay(2000L)
                            continue
                        }

                        command = mutableListOf(
                            pythonCmd,
                            scriptFile.absolutePath,
                            "--right-only", (currentConfig.podAssignment == PodAssignment.RIGHT_ANKI_ONLY || (currentConfig.podAssignment == PodAssignment.LEGACY_SHARED && currentConfig.rightPodOnlyNoZwift)).toString()
                        )
                    }

                    command.addAll(listOf("--legacy-shared", (currentConfig.podAssignment == PodAssignment.LEGACY_SHARED).toString()))

                    if (!currentConfig.pinnedRightPodMac.isNullOrBlank()) {
                        if (isValidMacOrUuid(currentConfig.pinnedRightPodMac)) {
                            command.add("--pinned-right")
                            command.add(currentConfig.pinnedRightPodMac!!.trim())
                        } else {
                            DiagnosticLogger.warn(LogCategory.BLE, "Ignoring invalid pinnedRightPodMac: '${currentConfig.pinnedRightPodMac}'")
                        }
                    }
                    if (!currentConfig.pinnedLeftPodMac.isNullOrBlank()) {
                        if (isValidMacOrUuid(currentConfig.pinnedLeftPodMac)) {
                            command.add("--pinned-left")
                            command.add(currentConfig.pinnedLeftPodMac!!.trim())
                        } else {
                            DiagnosticLogger.warn(LogCategory.BLE, "Ignoring invalid pinnedLeftPodMac: '${currentConfig.pinnedLeftPodMac}'")
                        }
                    }

                    DiagnosticLogger.info(LogCategory.BLE, "Spawning BLE worker: ${command.joinToString(" ")}")
                    val pb = ProcessBuilder(command)
                    pb.redirectErrorStream(false)
                    val proc = pb.start()
                    process = proc
                    workerProcess = proc

                    // Read stderr in a separate coroutine
                    stderrJob = launch(Dispatchers.IO) {
                        try {
                            val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
                            var errLine: String? = null
                            while (isActive && errReader.readLine().also { errLine = it } != null) {
                                errLine?.let {
                                    if (it.isNotBlank()) {
                                        DiagnosticLogger.warn(LogCategory.BLE, "BLE worker stderr: $it")
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    // Read stdout lines
                    val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                    var line: String? = null
                    while (isActive && reader.readLine().also { line = it } != null) {
                        line?.let { parseWorkerLine(it.trim()) }
                    }

                    val exitCode = proc.waitFor()
                    podActiveMasks.clear()
                    gestureEngine.reset()
                    synchronized(currentButtonMapLock) { ClickButton.entries.forEach { currentButtonMap[it] = false } }
                    _connectedRightMac.value = null
                    _connectedLeftMac.value = null
                    stderrJob.cancel()
                    DiagnosticLogger.warn(LogCategory.BLE, "BLE worker process terminated with exitCode=$exitCode")

                    if (!isRunningBridge) {
                        if (_connectionState.value != BleConnectionState.DISCONNECTED) {
                            _connectionState.value = BleConnectionState.DISCONNECTED
                        }
                        break
                    } else {
                        DiagnosticLogger.warn(LogCategory.BLE, "BLE worker exited unexpectedly while bridge is running; reconnecting in 2s...")
                        _connectionState.value = BleConnectionState.SCANNING
                        delay(2000L)
                    }
                } catch (e: CancellationException) {
                    // Expected on cancel or disconnect
                    break
                } catch (e: Exception) {
                    DiagnosticLogger.error(LogCategory.BLE, "Exception in BLE worker supervisor: ${e.message}")
                    if (isRunningBridge) {
                        _connectionState.value = BleConnectionState.SCANNING
                        delay(2000L)
                    } else {
                        _connectionState.value = BleConnectionState.ERROR
                        break
                    }
                } finally {
                    stderrJob?.cancel()
                    process?.let { p ->
                        try {
                            p.destroy()
                            if (!p.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                p.destroyForcibly()
                            }
                        } catch (_: Exception) {
                        }
                    }
                    if (workerProcess === process) {
                        workerProcess = null
                    }
                }
            }
        }
    }

    private fun parseWorkerLine(line: String) {
        if (line.isBlank()) return

        when {
            line.startsWith("STATE ") -> {
                val parts = line.removePrefix("STATE ").trim().split(" ")
                val stateName = parts.getOrNull(0) ?: return
                val mac = parts.getOrNull(1)

                when (stateName) {
                    "SCANNING" -> {
                        if (_connectedRightMac.value == null && _connectedLeftMac.value == null) _connectionState.value = BleConnectionState.SCANNING
                    }
                    "DISCOVERED" -> {
                        if (mac != null && !_discoveredAddresses.value.contains(mac)) {
                            _discoveredAddresses.value = (_discoveredAddresses.value + mac).takeLast(20)
                            DiagnosticLogger.info(LogCategory.BLE, "Discovered Zwift controller: $mac")
                        }
                    }
                    "CONNECTING" -> {
                        _connectionState.value = BleConnectionState.CONNECTING
                    }
                    "HANDSHAKING" -> {
                        _connectionState.value = BleConnectionState.HANDSHAKING
                    }
                    "CONNECTED_RIGHT", "CONNECTED_LEFT" -> {
                        if (mac == null) return
                        if (stateName == "CONNECTED_RIGHT") _connectedRightMac.value = mac else _connectedLeftMac.value = mac
                        _connectionState.value = if (_connectedRightMac.value != null && _connectedLeftMac.value != null)
                            BleConnectionState.CONNECTED_BOTH_PODS else if (_connectedRightMac.value != null) BleConnectionState.CONNECTED_RIGHT_ONLY else BleConnectionState.CONNECTED_LEFT_ONLY
                    }
                    "CONNECTED_RIGHT_ONLY" -> {
                        if (mac != null) _connectedRightMac.value = mac
                        _connectionState.value = BleConnectionState.CONNECTED_RIGHT_ONLY
                        DiagnosticLogger.info(LogCategory.BLE, "Connected to Right Pod: $mac")
                    }
                    "CONNECTED_BOTH_PODS" -> {
                        if (mac != null && _connectedRightMac.value != mac) {
                            _connectedLeftMac.value = mac
                        }
                        _connectionState.value = BleConnectionState.CONNECTED_BOTH_PODS
                        DiagnosticLogger.info(LogCategory.BLE, "Connected to both pods (active: $mac)")
                    }
                    "DISCONNECTED" -> {
                        if (mac != null) {
                            podActiveMasks.remove(mac)
                            gestureEngine.reset()
                            if (_connectedRightMac.value.equals(mac, ignoreCase = true)) {
                                _connectedRightMac.value = null
                            }
                            if (_connectedLeftMac.value.equals(mac, ignoreCase = true)) {
                                _connectedLeftMac.value = null
                            }
                            updateCurrentButtonsAndProcess(System.currentTimeMillis())
                        }
                        if (_connectedRightMac.value != null) {
                            _connectionState.value = BleConnectionState.CONNECTED_RIGHT_ONLY
                        } else if (_connectedLeftMac.value != null) {
                            _connectionState.value = BleConnectionState.CONNECTED_LEFT_ONLY
                        } else {
                            _connectionState.value = BleConnectionState.DISCONNECTED
                        }
                    }
                    "ERROR" -> {
                        _connectionState.value = BleConnectionState.ERROR
                        DiagnosticLogger.error(LogCategory.BLE, "BLE worker error: ${parts.drop(1).joinToString(" ")}")
                    }
                }
            }

            line.startsWith("PACKET ") -> {
                val parts = line.removePrefix("PACKET ").trim().split(" ")
                if (parts.size >= 2) {
                    val mac = parts[0]
                    val hex = parts[1]
                    val bytes = hexStringToByteArray(hex)
                    if (bytes.isNotEmpty()) {
                        handleRawNotification(mac, bytes)
                    }
                }
            }

            line.startsWith("LOG ") -> {
                val parts = line.removePrefix("LOG ").trim().split(" ", limit = 2)
                val level = parts.getOrNull(0)?.uppercase() ?: "INFO"
                val msg = parts.getOrNull(1) ?: ""
                when (level) {
                    "WARN" -> DiagnosticLogger.warn(LogCategory.BLE, "BLE worker: $msg")
                    "ERROR" -> DiagnosticLogger.error(LogCategory.BLE, "BLE worker: $msg")
                    else -> DiagnosticLogger.info(LogCategory.BLE, "BLE worker: $msg")
                }
            }

            else -> {
                DiagnosticLogger.info(LogCategory.BLE, "BLE worker stdout: $line")
            }
        }
    }

    fun handleRawNotification(mac: String, payload: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        if (payload.isEmpty()) return
        val msgId = payload[0]
        // Suppress periodic idle keep-alive heartbeats (0x15, 0x19)
        if (msgId == 0x15.toByte() || msgId == 0x19.toByte()) return

        val isRight = _connectedRightMac.value.equals(mac, ignoreCase = true)
        val isLeft = _connectedLeftMac.value.equals(mac, ignoreCase = true)
        if (!isRight && !isLeft) return
        var mask = ZwiftPacketParser.parseActiveButtonMask(payload)
        if (mask != null && currentConfig.podAssignment != PodAssignment.LEGACY_SHARED && payload[0] == ZwiftPacketParser.MSG_CLICK_V1_SHIFT) {
            mask = when {
                isLeft && mask == ClickButton.SHIFT_UP_RIGHT.bitmask -> ClickButton.SHIFT_UP_LEFT.bitmask
                isRight && mask == ClickButton.SHIFT_DOWN_LEFT.bitmask -> ClickButton.SHIFT_DOWN_RIGHT.bitmask
                else -> mask
            }
        }
        if (mask == null) {
            val hex = payload.joinToString(" ") { "%02X".format(it) }
            DiagnosticLogger.info(LogCategory.PACKET, "Non-0x23 packet from $mac (msgId=0x${"%02X".format(msgId)}): [$hex]")
            return
        }

        val allowedMask = ClickButton.entries.filter { it.isRightPod == isRight }.fold(0) { acc, button -> acc or button.bitmask }
        val sideMask = if (currentConfig.podAssignment == PodAssignment.LEGACY_SHARED) mask else mask and allowedMask
        val prevMask = podActiveMasks.put(mac, sideMask) ?: 0
        val hex = payload.joinToString(" ") { "%02X".format(it) }
        if (mask != 0 || prevMask != 0 || lastLoggedPayloadHex.put(mac, hex) != hex) {
            if (mask != 0) {
                val matchedNames = ClickButton.entries.filter { (mask and it.bitmask) != 0 }.joinToString(",") { it.name }
                DiagnosticLogger.info(
                    LogCategory.PACKET,
                    "0x23 packet from $mac: [$hex] -> activeMask=0x${"%05X".format(mask)} (${matchedNames.ifEmpty { "UNMAPPED_BIT" }})"
                )
            }
        }

        updateCurrentButtonsAndProcess(nowMs)
    }

    private fun updateCurrentButtonsAndProcess(nowMs: Long = System.currentTimeMillis()) {
        val combinedMask = podActiveMasks.values.fold(0) { acc, m -> acc or m }
        val combinedMap = EnumMap<ClickButton, Boolean>(ClickButton::class.java)
        ClickButton.entries.forEach { button ->
            combinedMap[button] = (combinedMask and button.bitmask) != 0
        }
        synchronized(currentButtonMapLock) {
            currentButtonMap.clear()
            currentButtonMap.putAll(combinedMap)
        }
        gestureEngine.processButtonStates(combinedMap, nowMs)
    }

    fun emitSimulatedGesture(button: ClickButton, pressType: PressType) {
        val event = ButtonGestureEvent(button, pressType, System.currentTimeMillis())
        DiagnosticLogger.info(
            LogCategory.BLE,
            "Simulated gesture emitted: ${button.displayName} (${pressType.name})"
        )
        scope.launch { _gestureEvents.emit(event) }
    }

    override fun disconnect() {
        isRunningBridge = false

        tickerJob?.cancel()
        tickerJob = null

        workerJob?.cancel()
        workerJob = null

        workerProcess?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                }
            } catch (_: Exception) {
            }
        }
        workerProcess = null

        podActiveMasks.clear()
        gestureEngine.reset()
        lastLoggedPayloadHex.clear()
        synchronized(currentButtonMapLock) {
            ClickButton.entries.forEach { currentButtonMap[it] = false }
        }
        _connectedRightMac.value = null
        _connectedLeftMac.value = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    fun extractWorkerScript(): File? {
        val binDir = File(System.getProperty("user.home"), ".ankiride/bin")
        if (!binDir.exists()) {
            binDir.mkdirs()
        }
        runCatching {
            Files.setPosixFilePermissions(binDir.toPath(), PosixFilePermissions.fromString("rwx------"))
        }

        val target = File(binDir, "zwift_ble_worker.py")

        // Try classpath resources
        val resourceStream = javaClass.getResourceAsStream("/ble/zwift_ble_worker.py")
            ?: javaClass.getResourceAsStream("/zwift_ble_worker.py")

        if (resourceStream != null) {
            FileOutputStream(target).use { fos ->
                resourceStream.copyTo(fos)
            }
            target.setExecutable(true, true)
            runCatching {
                Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
            return target
        }

        // Direct filesystem search fallback for development runs
        val devPaths = listOf(
            File("desktop/src/main/resources/ble/zwift_ble_worker.py"),
            File("desktop/src/main/resources/zwift_ble_worker.py"),
            File("src/main/resources/ble/zwift_ble_worker.py"),
            File("resources/ble/zwift_ble_worker.py")
        )
        val devFile = devPaths.firstOrNull { it.exists() && it.isFile }
        if (devFile != null) {
            devFile.copyTo(target, overwrite = true)
            target.setExecutable(true, true)
            runCatching {
                Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
            return target
        }

        if (target.exists() && target.isFile) {
            return target
        }

        return null
    }

    internal fun supportsSideProtocol(binary: File): Boolean {
        var process: Process? = null
        return try {
            process = ProcessBuilder(binary.absolutePath, "--protocol-version").redirectErrorStream(true).start()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                false
            } else {
                process.exitValue() == 0 && process.inputStream.bufferedReader().use { it.readText().trim() } == "CLICKCONTROL_BLE_PROTOCOL_3"
            }
        } catch (_: Exception) {
            false
        } finally {
            process?.let { if (it.isAlive) it.destroyForcibly() }
        }
    }

    fun findNativeWorkerBinary(): File? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val binaryName = if (isWindows) "zwift_ble_worker.exe" else "zwift_ble_worker"

        // 1. System.getProperty("compose.application.resources.dir")
        val composeResDir = System.getProperty("compose.application.resources.dir")
        if (!composeResDir.isNullOrBlank()) {
            val candidates = listOf(
                File(composeResDir, binaryName),
                File(File(composeResDir, "bin"), binaryName),
                File(File(composeResDir, "ble"), binaryName)
            )
            for (candidate in candidates) {
                if (candidate.exists() && candidate.isFile) {
                    if (candidate.canExecute()) return candidate
                    // On Linux .deb / packaged installs (e.g. /opt/ankiridebridge/lib/app/resources),
                    // jpackage may strip the +x bit (0644) and the directory is root-owned.
                    // Copy the bundled binary to ~/.ankiride/bin and set 0700 executable permissions.
                    val staged = stageNativeBinaryToHome(candidate, binaryName)
                    if (staged != null) return staged
                }
            }
        }

        // 2. File(System.getProperty("user.home"), ".ankiride/bin")
        val homeBinCandidate = File(File(System.getProperty("user.home"), ".ankiride/bin"), binaryName)
        if (homeBinCandidate.exists() && homeBinCandidate.isFile) {
            if (composeResDir.isNullOrBlank()) {
                val devScripts = listOf(
                    File("desktop/src/main/resources/ble/zwift_ble_worker.py"),
                    File("src/main/resources/ble/zwift_ble_worker.py")
                )
                if (devScripts.any { it.exists() && it.isFile && it.lastModified() > homeBinCandidate.lastModified() }) {
                    DiagnosticLogger.info(LogCategory.BLE, "Dev Python worker script is newer than ~/.ankiride/bin binary, bypassing cached binary")
                    return null
                }
            }
            if (!homeBinCandidate.canExecute()) {
                runCatching { homeBinCandidate.setExecutable(true, true) }
            }
            if (homeBinCandidate.canExecute()) {
                return homeBinCandidate
            }
        }

        return null
    }

    private fun stageNativeBinaryToHome(sourceFile: File, binaryName: String): File? {
        return try {
            val binDir = File(System.getProperty("user.home"), ".ankiride/bin")
            if (!binDir.exists()) {
                binDir.mkdirs()
            }
            runCatching {
                Files.setPosixFilePermissions(binDir.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
            val stagedFile = File(binDir, binaryName)
            val shouldOverwrite = !stagedFile.exists() || (sourceFile.lastModified() >= stagedFile.lastModified() && (stagedFile.length() != sourceFile.length() || stagedFile.lastModified() < sourceFile.lastModified()))
            if (shouldOverwrite) {
                sourceFile.copyTo(stagedFile, overwrite = true)
            }
            stagedFile.setExecutable(true, true)
            runCatching {
                Files.setPosixFilePermissions(stagedFile.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
            if (stagedFile.canExecute()) stagedFile else null
        } catch (e: Exception) {
            DiagnosticLogger.warn(LogCategory.BLE, "Failed to stage bundled BLE worker binary to ~/.ankiride/bin: ${e.message}")
            null
        }
    }

    fun getOrBootstrapPythonExecutable(): String? {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val isLinux = System.getProperty("os.name").lowercase().contains("linux")
        val venvDir = File(System.getProperty("user.home"), ".ankiride/venv")
        val venvPython = if (isWindows) {
            File(venvDir, "Scripts/python.exe")
        } else {
            File(venvDir, "bin/python")
        }

        if (venvPython.exists() && canImportBleak(venvPython.absolutePath)) {
            return venvPython.absolutePath
        }

        DiagnosticLogger.info(LogCategory.BLE, "Bootstrapping private BLE Python venv at ~/.ankiride/venv...")

        val basePython = findSystemPythonExecutable()
        if (basePython == null) {
            DiagnosticLogger.error(LogCategory.BLE, "System python executable not found on system PATH to bootstrap venv")
            return null
        }

        val dotAnkiRide = File(System.getProperty("user.home"), ".ankiride")
        if (!dotAnkiRide.exists()) {
            dotAnkiRide.mkdirs()
            runCatching {
                Files.setPosixFilePermissions(dotAnkiRide.toPath(), PosixFilePermissions.fromString("rwx------"))
            }
        }

        try {
            // 1. Create venv: python3 -m venv ~/.ankiride/venv
            val venvProc = ProcessBuilder(basePython, "-m", "venv", venvDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val venvOut = venvProc.inputStream.bufferedReader().readText()
            val venvExit = venvProc.waitFor()
            if (venvExit != 0) {
                DiagnosticLogger.error(LogCategory.BLE, "Failed to create python venv (exitCode=$venvExit): $venvOut")
                return null
            }

            if (!venvPython.exists()) {
                DiagnosticLogger.error(LogCategory.BLE, "Venv python not found at ${venvPython.absolutePath}")
                return null
            }

            // 2. Install dependencies: ~/.ankiride/venv/bin/pip install --quiet bleak dbus-fast (Linux) or bleak (macOS/Win)
            val pipExecutable = if (isWindows) {
                File(venvDir, "Scripts/pip.exe")
            } else {
                File(venvDir, "bin/pip")
            }

            val pipArgs = if (isLinux) {
                listOf("install", "--quiet", "bleak", "dbus-fast")
            } else {
                listOf("install", "--quiet", "bleak")
            }

            val pipCmd = if (pipExecutable.exists()) {
                listOf(pipExecutable.absolutePath) + pipArgs
            } else {
                listOf(venvPython.absolutePath, "-m", "pip") + pipArgs
            }

            DiagnosticLogger.info(LogCategory.BLE, "Installing bleak into venv: ${pipCmd.joinToString(" ")}")
            val pipProc = ProcessBuilder(pipCmd)
                .redirectErrorStream(true)
                .start()
            val pipOut = pipProc.inputStream.bufferedReader().readText()
            val pipExit = pipProc.waitFor()
            if (pipExit != 0) {
                DiagnosticLogger.error(LogCategory.BLE, "Failed to install bleak via pip (exitCode=$pipExit): $pipOut")
                return null
            }

            if (canImportBleak(venvPython.absolutePath)) {
                DiagnosticLogger.info(LogCategory.BLE, "Successfully bootstrapped BLE Python venv with bleak")
                return venvPython.absolutePath
            } else {
                DiagnosticLogger.error(LogCategory.BLE, "Bootstrapped venv python still cannot import bleak")
                return null
            }
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.BLE, "Exception bootstrapping Python venv: ${e.message}")
            return null
        }
    }

    private fun canImportBleak(pythonExec: String): Boolean {
        return try {
            val pb = ProcessBuilder(pythonExec, "-c", "import bleak")
            val proc = pb.start()
            proc.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun findPythonExecutable(): String? {
        val venvOrBootstrapped = getOrBootstrapPythonExecutable()
        if (venvOrBootstrapped != null) return venvOrBootstrapped
        return findSystemPythonExecutable()
    }

    private fun findSystemPythonExecutable(): String? {
        val candidates = if (System.getProperty("os.name").lowercase().contains("win")) {
            listOf("python.exe", "python3.exe", "python")
        } else {
            listOf("python3", "python", "/usr/bin/python3", "/usr/local/bin/python3")
        }

        for (cmd in candidates) {
            try {
                val pb = ProcessBuilder(cmd, "--version")
                val proc = pb.start()
                if (proc.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) && proc.exitValue() == 0) {
                    return cmd
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val clean = s.replace(" ", "").trim()
        val len = clean.length
        if (len == 0 || len % 2 != 0) return ByteArray(0)
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val high = Character.digit(clean[i], 16)
            val low = Character.digit(clean[i + 1], 16)
            if (high == -1 || low == -1) return ByteArray(0)
            data[i / 2] = ((high shl 4) + low).toByte()
            i += 2
        }
        return data
    }
}
