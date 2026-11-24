package com.ankiride.bridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ankiride.bridge.ble.DesktopZwiftBleController
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.OpenBikeProtocol
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class DesktopPage(val title: String, val subtitle: String, val icon: ImageVector) {
    CONTROL("Control", "Controller status, active preset & quick actions", Icons.Default.Home),
    PRESETS("Presets", "Templates and button mappings", Icons.Default.Tune),
    SETTINGS("Settings", "Connections, startup, desktop input & diagnostics", Icons.Default.Settings)
}

internal object DesktopLauncherPaths {
    fun resolveExecutable(): File? {
        val candidates = listOfNotNull(
            System.getProperty("jpackage.app-path")?.let(::File),
            ProcessHandle.current().info().command().orElse(null)?.let { cmd ->
                val f = File(cmd)
                if (f.name.startsWith("ClickControl", ignoreCase = true)) f else null
            },
            File(System.getProperty("user.dir"), "desktop/build/compose/binaries/main/app/ClickControl/bin/ClickControl").canonicalFile,
            File("/opt/clickcontrol/bin/ClickControl")
        )
        return candidates.firstOrNull { it.isFile && it.canExecute() }
    }

    fun autostartFile(): File = File(System.getProperty("user.home") ?: ".", ".config/autostart/clickcontrol.desktop")
    fun legacyAutostartFile(): File = File(System.getProperty("user.home") ?: ".", ".config/autostart/ankiride-bridge.desktop")
}

@Composable
fun DesktopAppUi(
    config: AppConfiguration,
    onConfigChange: (AppConfiguration) -> Unit,
    isBridgeRunning: Boolean,
    onToggleBridge: () -> Unit,
    bleState: BleConnectionState,
    connectedRightMac: String?,
    connectedLeftMac: String?,
    hasMyWhooshClient: Boolean,
    openBikeServerRunning: Boolean = false,
    openBikeEndpoint: String = "",
    openBikeServiceName: String = OpenBikeProtocol.desktopServiceName(),
    openBikeError: String? = null,
    bleWorkerError: String? = null,
    onSimulateGesture: (ClickButton, PressType) -> Unit = { _, _ -> },
    onTestAnkiAction: (AnkiAction) -> Unit = {},
    onTestMyWhooshAction: (MyWhooshAction) -> Unit = {},
    lastRoutedActionLabel: String = "Ready",
    onMinimize: () -> Unit = {},
    onEnsureBridgeStarted: () -> Unit = {},
    onPresetDirtyChange: (Boolean) -> Unit = {},
    discoveredAddresses: List<String> = emptyList()
) {
    val capabilities = remember { PlatformCapabilities.desktop() }
    var page by remember { mutableStateOf(DesktopPage.CONTROL) }
    var presetDirty by remember { mutableStateOf(false) }
    LaunchedEffect(presetDirty) { onPresetDirtyChange(presetDirty) }
    var pendingPage by remember { mutableStateOf<DesktopPage?>(null) }

    fun navigate(next: DesktopPage) {
        if (page == DesktopPage.PRESETS && next != DesktopPage.PRESETS && presetDirty) {
            pendingPage = next
        } else {
            page = next
        }
    }

    var presetDropdownOpen by remember { mutableStateOf(false) }
    var showMappings by remember { mutableStateOf(true) }
    var showAdvanced by remember { mutableStateOf(false) }
    var portText by remember(config.ubuntuAnkiConnectPort) { mutableStateOf(config.ubuntuAnkiConnectPort.toString()) }
    var openBikePortText by remember(config.openBikeServerPort) { mutableStateOf(config.openBikeServerPort.toString()) }

    val activePreset = PresetStore.active(config)
    val hasAnkiActions = activePreset?.bindings?.values?.any { it.ankiAction != null } == true
    val hasBikeActions = activePreset?.bindings?.values?.any { it.myWhooshAction != null } == true
    val isWayland = remember { System.getenv("XDG_SESSION_TYPE")?.equals("wayland", true) == true }
    val testScope = rememberCoroutineScope()
    var ankiTestCountdown by remember { mutableIntStateOf(0) }
    var ankiTestJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(activePreset?.id, activePreset?.ankiTarget) {
        ankiTestJob?.cancel()
        ankiTestCountdown = 0
    }
    DisposableEffect(Unit) { onDispose { ankiTestJob?.cancel() } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(UbuntuColors.Background)
    ) {
        val wide = maxWidth >= 860.dp

        Row(modifier = Modifier.fillMaxSize()) {
            // Ubuntu GNOME Adwaita Navigation Sidebar (wide windows)
            if (wide) {
                Surface(
                    modifier = Modifier.width(252.dp).fillMaxHeight(),
                    color = UbuntuColors.SidebarSurface
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
                            Text(
                                "ClickControl",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = UbuntuColors.TextPrimary
                            )
                            Text(
                                "Desktop Controller Bridge",
                                style = MaterialTheme.typography.bodySmall,
                                color = UbuntuColors.TextSecondary
                            )
                        }

                        HorizontalDivider(color = UbuntuColors.Border)

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            DesktopPage.entries.forEach { dest ->
                                val selected = page == dest
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) UbuntuColors.HighestSurface else Color.Transparent)
                                        .clickable { navigate(dest) }
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = dest.icon,
                                        contentDescription = null,
                                        tint = if (selected) UbuntuColors.YaruOrange else UbuntuColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = dest.title,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) UbuntuColors.TextPrimary else UbuntuColors.TextSecondary,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        // Pinned Sidebar Telemetry Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = UbuntuColors.CardSurface,
                            border = BorderStroke(1.dp, UbuntuColors.Border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val (statusText, statusDot) = when {
                                    !isBridgeRunning -> "Bridge stopped" to UbuntuColors.TextSecondary
                                    bleState == BleConnectionState.CONNECTED_BOTH_PODS -> "Both controllers connected" to UbuntuColors.Success
                                    bleState == BleConnectionState.CONNECTED_RIGHT_ONLY -> "Right controller connected" to UbuntuColors.Success
                                    bleState == BleConnectionState.CONNECTED_LEFT_ONLY -> "Left controller connected" to UbuntuColors.Success
                                    bleState == BleConnectionState.ERROR -> "Controller error" to UbuntuColors.Error
                                    else -> "Searching for controller…" to UbuntuColors.Warning
                                }
                                StatusPill(text = statusText, dotColor = statusDot)
                                Text(
                                    "Right: ${connectedRightMac ?: "—"}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = UbuntuColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Left: ${connectedLeftMac ?: "—"}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = UbuntuColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                Text(
                                    "Preset: ${PresetStore.activePresetName(config)}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UbuntuColors.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "Last: $lastRoutedActionLabel",
                                    fontSize = 11.sp,
                                    color = UbuntuColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                VerticalDivider(color = UbuntuColors.Border, thickness = 1.dp)
            }

            // Main Content Pane
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // GNOME AdwHeaderBar
                Surface(
                    color = UbuntuColors.SidebarSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = if (wide) page.title else "ClickControl · ${page.title}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = UbuntuColors.TextPrimary
                                )
                                Text(
                                    text = page.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UbuntuColors.TextSecondary
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = onMinimize,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.heightIn(min = 40.dp)
                                ) {
                                    Text("Minimize")
                                }
                                Button(
                                    onClick = onToggleBridge,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isBridgeRunning) UbuntuColors.ElevatedSurface else Color(0xFFC74312),
                                        contentColor = Color.White
                                    ),
                                    modifier = Modifier.heightIn(min = 40.dp)
                                ) {
                                    Text(if (isBridgeRunning) "Stop bridge" else "Start bridge", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }

                        if (!wide) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                DesktopPage.entries.forEach { dest ->
                                    FilterChip(
                                        selected = page == dest,
                                        onClick = { navigate(dest) },
                                        label = { Text(dest.title) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = UbuntuColors.YaruOrangeContainer,
                                            selectedLabelColor = UbuntuColors.TextPrimary
                                        ),
                                        modifier = Modifier.heightIn(min = 40.dp)
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = UbuntuColors.Border)
                    }
                }

                // Scrollable Page Body
                val lastIssue by DiagnosticLogger.lastIssue.collectAsState()
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    when (page) {
                        DesktopPage.CONTROL -> {
                            val activeErrorBanner = openBikeError
                                ?: bleWorkerError
                                ?: lastIssue?.takeIf { it.level == LogLevel.ERROR || it.level == LogLevel.WARN }?.let {
                                    "${it.category.name}: ${it.message}"
                                }
                            if (!activeErrorBanner.isNullOrBlank()) {
                                val isErrorLevel = openBikeError != null || bleWorkerError != null || lastIssue?.level == LogLevel.ERROR
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isErrorLevel) MaterialTheme.colorScheme.errorContainer else UbuntuColors.YaruOrangeContainer,
                                    border = BorderStroke(1.dp, if (isErrorLevel) UbuntuColors.Error else UbuntuColors.YaruOrange),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = activeErrorBanner,
                                            color = if (isErrorLevel) MaterialTheme.colorScheme.onErrorContainer else UbuntuColors.TextPrimary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f).padding(end = 12.dp)
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            OutlinedButton(
                                                onClick = {
                                                    showAdvanced = true
                                                    navigate(DesktopPage.SETTINGS)
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) { Text("Logs") }
                                            TextButton(onClick = { DiagnosticLogger.dismissLastIssue() }) {
                                                Text("Dismiss")
                                            }
                                        }
                                    }
                                }
                            }

                            AdwPreferencesGroup(
                                title = "Quick Setup (No External App Required)",
                                description = "Pick how your Ubuntu laptop and Pixel work together."
                            ) {
                                val isOptionBActive = config.podAssignment == PodAssignment.BOTH_CONTROLLERS && hasBikeActions
                                val isOptionCActive = config.podAssignment == PodAssignment.SINGLE_CONTROLLER && !hasBikeActions
                                AdwActionRow(
                                    title = "Option B · Laptop Bridges Both Controllers (Wi-Fi to Pixel)",
                                    subtitle = if (openBikeServerRunning) {
                                        "Ready — pair '$openBikeServiceName' at $openBikeEndpoint in MyWhoosh → Devices → Controllers"
                                    } else {
                                        "Connects both controllers on Ubuntu: 4 arrows for Docs/Web/Slides, second controller shifts MyWhoosh over Wi-Fi"
                                    },
                                    trailing = {
                                        if (isOptionBActive) {
                                            StatusPill("Active", UbuntuColors.Success)
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    val targetPreset = config.presets.firstOrNull {
                                                        it.bindings.values.any { act -> act.myWhooshAction != null } &&
                                                            it.bindings.values.any { act -> act.isGenericInput || act.ankiAction != null }
                                                    } ?: PresetStore.createFromTemplate(PresetTemplate.RIDE_WEB_SLIDES, capabilities)
                                                    val nextPresets = if (config.presets.any { it.id == targetPreset.id }) config.presets else config.presets + targetPreset
                                                    onConfigChange(
                                                        config.copy(
                                                            presets = nextPresets,
                                                            activePresetId = targetPreset.id,
                                                            podAssignment = PodAssignment.BOTH_CONTROLLERS
                                                        )
                                                    )
                                                    onEnsureBridgeStarted()
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) { Text("Use Option B") }
                                        }
                                    }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwActionRow(
                                    title = "Option C · Split Devices (Laptop = Anki/Docs · Pixel = MyWhoosh)",
                                    subtitle = "Connects only your single pinned laptop controller and stops Ubuntu's OpenBikeControl server so your Pixel handles shifting cleanly",
                                    trailing = {
                                        if (isOptionCActive) {
                                            StatusPill("Active", UbuntuColors.Success)
                                        } else {
                                            OutlinedButton(
                                                onClick = {
                                                    val studyPreset = config.presets.firstOrNull { preset ->
                                                        preset.bindings.values.none { it.myWhooshAction != null } &&
                                                            preset.bindings.values.any { it != MappedTargetAction.NONE }
                                                    } ?: PresetStore.createFromTemplate(PresetTemplate.DOCUMENT_SCROLL, capabilities)
                                                    val nextPresets = if (config.presets.any { it.id == studyPreset.id }) config.presets else config.presets + studyPreset
                                                    onConfigChange(
                                                        config.copy(
                                                            presets = nextPresets,
                                                            activePresetId = studyPreset.id,
                                                            podAssignment = PodAssignment.SINGLE_CONTROLLER
                                                        )
                                                    )
                                                    onEnsureBridgeStarted()
                                                },
                                                shape = RoundedCornerShape(8.dp)
                                            ) { Text("Use Option C") }
                                        }
                                    }
                                )
                            }

                            AdwPreferencesGroup(title = "Controller & Bridge Status") {
                                val bleStatus = when (bleState) {
                                    BleConnectionState.DISCONNECTED -> "Disconnected"
                                    BleConnectionState.SCANNING -> "Scanning for Zwift Click…"
                                    BleConnectionState.CONNECTING -> "Connecting…"
                                    BleConnectionState.HANDSHAKING -> "Completing handshake…"
                                    BleConnectionState.CONNECTED_RIGHT_ONLY -> "Right pod connected"
                                    BleConnectionState.CONNECTED_LEFT_ONLY -> "Left pod connected"
                                    BleConnectionState.CONNECTED_BOTH_PODS -> "Both pods connected"
                                    BleConnectionState.ERROR -> bleWorkerError ?: "Connection error (see Diagnostic Log)"
                                }
                                AdwActionRow(
                                    title = if (isBridgeRunning) "Bridge active" else "Bridge stopped",
                                    subtitle = "$bleStatus · Right: ${connectedRightMac ?: "not connected"} · Left: ${connectedLeftMac ?: "not connected"}",
                                    trailing = {
                                        StatusPill(
                                            text = if (isBridgeRunning) "Running" else "Stopped",
                                            dotColor = if (isBridgeRunning) UbuntuColors.Success else UbuntuColors.TextSecondary
                                        )
                                    }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwActionRow(
                                    title = "Last action",
                                    subtitle = "Most recent button press or hold sent from your controller",
                                    trailing = {
                                        Text(
                                            text = lastRoutedActionLabel,
                                            color = UbuntuColors.YaruOrange,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwActionRow(
                                    title = "MyWhoosh OpenBikeControl ($openBikeServiceName)",
                                    subtitle = when {
                                        openBikeError != null -> openBikeError
                                        hasMyWhooshClient -> "Connected on $openBikeEndpoint"
                                        isBridgeRunning && openBikeServerRunning -> "Waiting for MyWhoosh at $openBikeEndpoint (allow TCP ${config.openBikeServerPort} if firewall is on)"
                                        isBridgeRunning && !hasBikeActions -> "Disabled automatically for this preset (keeps Pixel ClickControl free in Option C)"
                                        else -> "Stopped (auto-starts on TCP ${config.openBikeServerPort} when preset uses MyWhoosh)"
                                    },
                                    trailing = {
                                        StatusPill(
                                            text = when {
                                                openBikeError != null -> "Error"
                                                hasMyWhooshClient -> "Connected"
                                                isBridgeRunning && openBikeServerRunning -> "Waiting"
                                                isBridgeRunning && !hasBikeActions -> "Standby"
                                                else -> "Stopped"
                                            },
                                            dotColor = when {
                                                openBikeError != null -> UbuntuColors.Error
                                                hasMyWhooshClient -> UbuntuColors.Info
                                                isBridgeRunning && openBikeServerRunning -> UbuntuColors.Warning
                                                else -> UbuntuColors.TextSecondary
                                            }
                                        )
                                    }
                                )
                            }

                            AdwPreferencesGroup(title = "Active Preset") {
                                val targetLabel = when (capabilities.normalizeAnkiTarget(activePreset?.ankiTarget ?: AnkiTarget.ANKICONNECT)) {
                                    AnkiTarget.ANKIWEB -> "AnkiWeb (Browser)"
                                    else -> "Desktop Anki (AnkiConnect)"
                                }
                                AdwActionRow(
                                    title = PresetStore.activePresetName(config),
                                    subtitle = if (hasAnkiActions) "Anki destination: $targetLabel" else "Switch presets anytime without reconnecting your controller",
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box {
                                                OutlinedButton(
                                                    onClick = { presetDropdownOpen = true },
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text("Switch ▾")
                                                }
                                                DropdownMenu(
                                                    expanded = presetDropdownOpen,
                                                    onDismissRequest = { presetDropdownOpen = false }
                                                ) {
                                                    config.presets.forEach { preset ->
                                                        DropdownMenuItem(
                                                            text = { Text(preset.name) },
                                                            onClick = {
                                                                onConfigChange(config.copy(activePresetId = preset.id))
                                                                presetDropdownOpen = false
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                            OutlinedButton(
                                                onClick = { navigate(DesktopPage.PRESETS) },
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text("Edit")
                                            }
                                        }
                                    }
                                )
                                if (hasAnkiActions || hasBikeActions) {
                                    HorizontalDivider(color = UbuntuColors.Border)
                                    AdwActionRow(
                                        title = "Quick actions test",
                                        subtitle = "Verify your target app responds before riding or studying",
                                        trailing = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                if (hasAnkiActions) {
                                                    val isWebAnki = capabilities.normalizeAnkiTarget(activePreset?.ankiTarget ?: AnkiTarget.ANKICONNECT) == AnkiTarget.ANKIWEB
                                                    OutlinedButton(
                                                        onClick = {
                                                            if (!isWebAnki) {
                                                                onTestAnkiAction(AnkiAction.FLIP_CARD)
                                                            } else if (ankiTestCountdown > 0) {
                                                                ankiTestJob?.cancel()
                                                                ankiTestCountdown = 0
                                                            } else {
                                                                ankiTestJob = testScope.launch {
                                                                    try {
                                                                        for (i in 3 downTo 1) {
                                                                            ankiTestCountdown = i
                                                                            delay(1000)
                                                                        }
                                                                        onTestAnkiAction(AnkiAction.FLIP_CARD)
                                                                    } finally {
                                                                        ankiTestCountdown = 0
                                                                    }
                                                                }
                                                            }
                                                        },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text(
                                                            when {
                                                                ankiTestCountdown > 0 -> "Focus browser ($ankiTestCountdown)…"
                                                                isWebAnki -> "Test Anki in 3s"
                                                                else -> "Test Anki"
                                                            }
                                                        )
                                                    }
                                                }
                                                if (hasBikeActions) {
                                                    OutlinedButton(
                                                        onClick = { onTestMyWhooshAction(MyWhooshAction.SHIFT_UP) },
                                                        shape = RoundedCornerShape(8.dp)
                                                    ) {
                                                        Text("Test bike")
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Current Button Map",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(onClick = { showMappings = !showMappings }) {
                                    Text(if (showMappings) "Hide" else "Show")
                                }
                            }
                            if (showMappings) {
                                MappingOverview(config)
                            }
                        }

                        DesktopPage.PRESETS -> {
                            PresetEditor(
                                config = config,
                                onChange = onConfigChange,
                                allowAnkiDroid = false,
                                onDirtyChanged = { presetDirty = it }
                            )
                        }

                        DesktopPage.SETTINGS -> {
                            AdwPreferencesGroup(
                                title = "Controller Connection",
                                description = "Choose whether ClickControl captures a single controller or both pods."
                            ) {
                                val choices = listOf(
                                    PodAssignment.SINGLE_CONTROLLER to ("Single controller (Option C)" to "Connects only one controller (honors your pinned address so a second controller stays free for Pixel ClickControl)."),
                                    PodAssignment.RIGHT_CONTROLLER_ONLY to ("Right controller only" to "Connects only a right-side controller and ignores left/4-arrow pods."),
                                    PodAssignment.BOTH_CONTROLLERS to ("Both controllers (Option B)" to "Connects both left and right pods through this laptop.")
                                )
                                choices.forEachIndexed { idx, (assignment, labels) ->
                                    val selected = config.podAssignment == assignment
                                    AdwRadioRow(
                                        title = labels.first,
                                        subtitle = labels.second,
                                        selected = selected,
                                        onClick = {
                                            if (!selected) {
                                                onConfigChange(config.copy(podAssignment = assignment))
                                            }
                                        }
                                    )
                                    if (idx < choices.lastIndex) HorizontalDivider(color = UbuntuColors.Border)
                                }
                            }

                            AdwPreferencesGroup(
                                title = "Anki Desktop (AnkiConnect)",
                                description = "Used by presets whose destination is Desktop Anki. For browser AnkiWeb, choose AnkiWeb on the preset itself."
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                        OutlinedTextField(
                                            value = config.ubuntuAnkiConnectHost,
                                            onValueChange = { onConfigChange(config.copy(ubuntuAnkiConnectHost = it)) },
                                            label = { Text("Host") },
                                            singleLine = true,
                                            modifier = Modifier.weight(2f)
                                        )
                                        OutlinedTextField(
                                            value = portText,
                                            onValueChange = { raw ->
                                                portText = raw.filter(Char::isDigit)
                                                portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
                                                    onConfigChange(config.copy(ubuntuAnkiConnectPort = port))
                                                }
                                            },
                                            label = { Text("Port") },
                                            isError = portText.toIntOrNull() !in 1..65535,
                                            supportingText = { if (portText.toIntOrNull() !in 1..65535) Text("1–65535") },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { onTestAnkiAction(AnkiAction.FLIP_CARD) }) {
                                            Text("Test AnkiConnect")
                                        }
                                    }
                                }
                            }

                            AdwPreferencesGroup(title = "Startup, Tray & Feedback") {
                                var startupPresetMenuOpen by remember { mutableStateOf(false) }
                                val startupLabel = config.defaultPresetId
                                    ?.let { id -> config.presets.firstOrNull { it.id == id }?.name }
                                    ?: "Last used preset"
                                AdwActionRow(
                                    title = "Default preset on launch",
                                    subtitle = "Automatically select this preset every time ClickControl starts",
                                    trailing = {
                                        Box {
                                            OutlinedButton(onClick = { startupPresetMenuOpen = true }, shape = RoundedCornerShape(8.dp)) {
                                                Text("$startupLabel ▾")
                                            }
                                            DropdownMenu(expanded = startupPresetMenuOpen, onDismissRequest = { startupPresetMenuOpen = false }) {
                                                DropdownMenuItem(
                                                    text = { Text("Last used preset") },
                                                    onClick = {
                                                        onConfigChange(config.copy(defaultPresetId = null))
                                                        startupPresetMenuOpen = false
                                                    }
                                                )
                                                config.presets.forEach { preset ->
                                                    DropdownMenuItem(
                                                        text = { Text(preset.name) },
                                                        onClick = {
                                                            onConfigChange(config.copy(defaultPresetId = preset.id))
                                                            startupPresetMenuOpen = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwSwitchRow(
                                    title = "Auto-start bridge on launch",
                                    subtitle = "Start scanning for controllers automatically when ClickControl opens",
                                    checked = config.autoStartOnControllerWake,
                                    onCheckedChange = { onConfigChange(config.copy(autoStartOnControllerWake = it)) }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwSwitchRow(
                                    title = "Start minimized in top-bar tray",
                                    subtitle = "Hide window to the top bar on launch (ideal with auto-start)",
                                    checked = config.startMinimizedToTray,
                                    onCheckedChange = { onConfigChange(config.copy(startMinimizedToTray = it)) }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwSwitchRow(
                                    title = "Floating mini status bar",
                                    subtitle = "Compact always-on-top status pill showing active preset and last action",
                                    checked = config.floatingHudPillEnabled,
                                    onCheckedChange = { onConfigChange(config.copy(floatingHudPillEnabled = it)) }
                                )
                                HorizontalDivider(color = UbuntuColors.Border)
                                AdwSwitchRow(
                                    title = "Audio feedback cues",
                                    subtitle = "Play subtle confirmation tones on controller presses",
                                    checked = config.audioFeedbackEnabled,
                                    onCheckedChange = { onConfigChange(config.copy(audioFeedbackEnabled = it)) }
                                )
                                if (capabilities.supportsLinuxAutostart) {
                                    HorizontalDivider(color = UbuntuColors.Border)
                                    var osLoginAutostart by remember {
                                        mutableStateOf(
                                            DesktopLauncherPaths.autostartFile().exists() ||
                                                DesktopLauncherPaths.legacyAutostartFile().exists()
                                        )
                                    }
                                    var autostartError by remember { mutableStateOf("") }
                                    AdwSwitchRow(
                                        title = "Launch at Ubuntu login",
                                        subtitle = "Starts ClickControl automatically when you log into Ubuntu",
                                        checked = osLoginAutostart,
                                        onCheckedChange = { enabled ->
                                            val file = DesktopLauncherPaths.autostartFile()
                                            val legacyFile = DesktopLauncherPaths.legacyAutostartFile()
                                            try {
                                                if (enabled) {
                                                    val launcher = DesktopLauncherPaths.resolveExecutable()
                                                        ?: error("Built ClickControl launcher not found. Run ./start-clickcontrol.sh first.")
                                                    val escapedLauncher = launcher.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
                                                    val args = if (config.startMinimizedToTray) " --minimized" else ""
                                                    file.parentFile?.mkdirs()
                                                    file.writeText(
                                                        """
                                                        [Desktop Entry]
                                                        Type=Application
                                                        Name=ClickControl
                                                        Comment=Zwift Click Controller Bridge
                                                        Exec="$escapedLauncher"$args
                                                        Terminal=false
                                                        X-GNOME-Autostart-enabled=true
                                                        """.trimIndent() + "\n"
                                                    )
                                                    if (legacyFile.exists()) legacyFile.delete()
                                                    osLoginAutostart = true
                                                    autostartError = ""
                                                    DiagnosticLogger.info(LogCategory.SYSTEM, "Installed OS login autostart entry: ${file.absolutePath}")
                                                } else {
                                                    if (file.exists()) check(file.delete()) { "Could not remove ${file.absolutePath}" }
                                                    if (legacyFile.exists()) legacyFile.delete()
                                                    osLoginAutostart = false
                                                    autostartError = ""
                                                    DiagnosticLogger.info(LogCategory.SYSTEM, "Removed OS login autostart entry: ${file.absolutePath}")
                                                }
                                            } catch (e: Exception) {
                                                autostartError = e.message ?: "Failed to update login autostart"
                                                DiagnosticLogger.error(LogCategory.SYSTEM, autostartError)
                                            }
                                        }
                                    )
                                    if (autostartError.isNotEmpty()) {
                                        Text(
                                            text = autostartError,
                                            color = UbuntuColors.Error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }

                            AdwPreferencesGroup(
                                title = "MyWhoosh OpenBikeControl (Option B)",
                                description = "Starts automatically whenever the active preset uses MyWhoosh actions."
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Service name: $openBikeServiceName · LAN endpoint: ${openBikeEndpoint.ifBlank { "port ${config.openBikeServerPort}" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UbuntuColors.TextSecondary
                                    )
                                    OutlinedTextField(
                                        value = openBikePortText,
                                        onValueChange = { raw ->
                                            openBikePortText = raw.filter(Char::isDigit)
                                            openBikePortText.toIntOrNull()?.takeIf { it in 1..65535 }?.let { port ->
                                                onConfigChange(config.copy(openBikeServerPort = port))
                                            }
                                        },
                                        label = { Text("OpenBikeControl TCP Port") },
                                        isError = openBikePortText.toIntOrNull() !in 1..65535,
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { showAdvanced = !showAdvanced },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Text(if (showAdvanced) "Hide advanced & diagnostics" else "Advanced & diagnostics")
                            }

                            if (showAdvanced) {
                                AdwPreferencesGroup(
                                    title = "Controller Hardware Pinning",
                                    description = "Optional: lock specific MAC addresses (Linux/Windows) or Bluetooth UUIDs (macOS) to Right or Left."
                                ) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        if (discoveredAddresses.isNotEmpty()) {
                                            Text("Discovered devices (click to copy into Right or check logs):", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                discoveredAddresses.forEach { addr ->
                                                    AssistChip(
                                                        onClick = { onConfigChange(config.copy(pinnedRightPodMac = addr)) },
                                                        label = { Text(addr, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                                                    )
                                                }
                                            }
                                        }
                                        var rightAddress by remember(config.pinnedRightPodMac) { mutableStateOf(config.pinnedRightPodMac.orEmpty()) }
                                        var leftAddress by remember(config.pinnedLeftPodMac) { mutableStateOf(config.pinnedLeftPodMac.orEmpty()) }
                                        var addressError by remember { mutableStateOf("") }
                                        OutlinedTextField(
                                            value = rightAddress,
                                            onValueChange = { rightAddress = it },
                                            label = { Text("Pinned Right address") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        OutlinedTextField(
                                            value = leftAddress,
                                            onValueChange = { leftAddress = it },
                                            label = { Text("Pinned Left address") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        if (addressError.isNotEmpty()) {
                                            Text(addressError, color = UbuntuColors.Error, fontSize = 12.sp)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(onClick = {
                                                val right = rightAddress.trim().ifEmpty { null }
                                                val left = leftAddress.trim().ifEmpty { null }
                                                val valid = { value: String? -> value == null || DesktopZwiftBleController.isValidMacOrUuid(value) }
                                                if (!valid(right) || !valid(left) || (right != null && right.equals(left, ignoreCase = true))) {
                                                    addressError = "Enter distinct valid MAC addresses or Bluetooth UUIDs."
                                                } else {
                                                    addressError = ""
                                                    onConfigChange(config.copy(pinnedRightPodMac = right, pinnedLeftPodMac = left))
                                                }
                                            }) { Text("Save addresses") }

                                            OutlinedButton(onClick = {
                                                onConfigChange(
                                                    config.copy(
                                                        pinnedRightPodMac = connectedRightMac ?: config.pinnedRightPodMac,
                                                        pinnedLeftPodMac = connectedLeftMac ?: config.pinnedLeftPodMac
                                                    )
                                                )
                                            }) { Text("Lock connected") }

                                            OutlinedButton(onClick = {
                                                rightAddress = ""
                                                leftAddress = ""
                                                addressError = ""
                                                onConfigChange(config.copy(pinnedRightPodMac = null, pinnedLeftPodMac = null))
                                            }) { Text("Clear pins") }
                                        }
                                    }
                                }

                                DiagnosticLogViewerCard()
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingPage != null) {
        AlertDialog(
            onDismissRequest = { pendingPage = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Save your preset before leaving, or discard the changes.") },
            confirmButton = {
                TextButton(onClick = {
                    page = pendingPage ?: page
                    presetDirty = false
                    pendingPage = null
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { pendingPage = null }) { Text("Keep editing") } }
        )
    }
}

@Composable
fun DiagnosticLogViewerCard() {
    val logs by DiagnosticLogger.logs.collectAsState()
    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }

    val filteredLogs = remember(logs, selectedCategory) {
        if (selectedCategory == null) logs else logs.filter { it.category == selectedCategory }
    }

    val logPath = DiagnosticLogger.logFilePath()
    AdwPreferencesGroup(
        title = "Diagnostic Log (${filteredLogs.size})",
        description = if (logPath != null) "Saved to $logPath" else "Real-time Bluetooth events, routing, and Anki / MyWhoosh dispatches."
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val text = DiagnosticLogger.formatForClipboard(selectedCategory)
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    clipboard.setContents(StringSelection(text), null)
                    DiagnosticLogger.info(LogCategory.SYSTEM, "Copied ${filteredLogs.size} logs to system clipboard")
                }) { Text("Copy Logs", fontSize = 12.sp) }

                OutlinedButton(
                    enabled = logPath != null,
                    onClick = {
                        val path = logPath ?: return@OutlinedButton
                        try {
                            val file = File(path)
                            file.parentFile?.mkdirs()
                            if (!file.exists()) file.createNewFile()
                            val isLinux = System.getProperty("os.name", "").lowercase().contains("linux")
                            if (isLinux) {
                                ProcessBuilder("xdg-open", file.absolutePath).start()
                            } else if (java.awt.Desktop.isDesktopSupported()) {
                                java.awt.Desktop.getDesktop().open(file)
                            } else {
                                DiagnosticLogger.warn(LogCategory.SYSTEM, "Open Log File is unsupported on this OS (${file.absolutePath})")
                            }
                        } catch (e: Exception) {
                            DiagnosticLogger.warn(LogCategory.SYSTEM, "Could not open log file $path: ${e.message}")
                        }
                    }
                ) { Text("Open Log File", fontSize = 12.sp) }

                OutlinedButton(onClick = { DiagnosticLogger.clear() }) { Text("Clear", fontSize = 12.sp) }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedCategory == null,
                    onClick = { selectedCategory = null },
                    label = { Text("ALL", fontSize = 11.sp) }
                )
                listOf(
                    LogCategory.BLE,
                    LogCategory.PACKET,
                    LogCategory.ANKI,
                    LogCategory.MYWHOOSH,
                    LogCategory.ROUTER,
                    LogCategory.SYSTEM
                ).forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = if (selectedCategory == category) null else category },
                        label = { Text(category.name, fontSize = 11.sp) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .background(UbuntuColors.TerminalSurface, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, UbuntuColors.Border, shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "No log entries yet. Start the bridge to see live events.",
                        color = UbuntuColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredLogs.takeLast(150).reversed().forEach { entry ->
                            val color = when (entry.level) {
                                LogLevel.ERROR -> UbuntuColors.Error
                                LogLevel.WARN -> UbuntuColors.Warning
                                LogLevel.INFO -> UbuntuColors.TextPrimary
                            }
                            Text(
                                text = "[${entry.formattedTime}] [${entry.category.name}] ${entry.message}",
                                color = color,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
