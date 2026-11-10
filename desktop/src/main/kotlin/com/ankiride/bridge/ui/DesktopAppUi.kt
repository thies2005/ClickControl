package com.ankiride.bridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.DesktopMyWhooshRobot
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

private enum class StudioInspectorTab(val title: String) {
    KEYMAP("Button Keymap (All 10)"),
    NETWORK("Network & Tests (:8765 / :21587)"),
    HARDWARE("Auto-Start & Hardware"),
    LOGS("Live Logs")
}

/**
 * Option A — Studio Split-Pane Desktop UI for ClickControl.
 * Features:
 * - Persistent Top Telemetry & Lifecycle Bar
 * - 5-Mode Selector Strip
 * - Left Studio Pane: Verified Physical Zwift Click v2 Dual-Pod 2D Visualizer (5 Circular Buttons per Pod + Top-Right Blue Sub-Badges + Diagonal Corner Lugs + Diamond Guide Lines)
 * - Right Studio Pane: Tabbed Inspector (10-Button Keymap, Network & 1-Click Tests, Auto-Start & Hardware, Live Logs)
 */
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
    onSimulateGesture: (ClickButton, PressType) -> Unit,
    onTestAnkiAction: (AnkiAction) -> Unit,
    onTestMyWhooshAction: (MyWhooshAction) -> Unit,
    lastRoutedActionLabel: String = "Ready",
    onMinimizeToTray: () -> Unit = {},
    onPresetDirtyChange: (Boolean) -> Unit = {}
) {
    var page by remember { mutableStateOf(0) }
    var presetDirty by remember { mutableStateOf(false) }
    LaunchedEffect(presetDirty) { onPresetDirtyChange(presetDirty) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    fun navigate(next: Int) { if (page == 1 && next != 1 && presetDirty) pendingPage = next else page = next }
    var advanced by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(config.ubuntuAnkiConnectPort.toString()) }
    var showMappings by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ClickControl", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text("Controller bridge for Anki and MyWhoosh", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onToggleBridge, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (isBridgeRunning) "Stop bridge" else "Start bridge") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Control", "Presets", "Settings").forEachIndexed { index, label ->
                FilterChip(selected = page == index, onClick = { navigate(index) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 900.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide) {
                    Card(Modifier.width(250.dp).fillMaxHeight()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Status", style = MaterialTheme.typography.titleLarge)
                            Text(if (isBridgeRunning) "Bridge running" else "Bridge stopped")
                            Text("Controller: ${bleState.name.replace('_', ' ').lowercase()}")
                            Text("Right: ${connectedRightMac ?: "not connected"}")
                            Text("Left: ${connectedLeftMac ?: "not connected"}")
                            Text(if (hasMyWhooshClient) "MyWhoosh connected" else "Waiting for MyWhoosh")
                            HorizontalDivider()
                            Text("Active preset", style = MaterialTheme.typography.titleMedium)
                            Text(PresetStore.active(config)?.name ?: "Default")
                            Text("Last action: $lastRoutedActionLabel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        0 -> {
                            Text("Control", style = MaterialTheme.typography.headlineSmall)
                            if (!wide) Text("${if (isBridgeRunning) "Running" else "Stopped"} · ${bleState.name.replace('_', ' ').lowercase()} · ${PresetStore.active(config)?.name ?: "Default"}")
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Active preset", style = MaterialTheme.typography.titleMedium)
                                Text(PresetStore.active(config)?.name ?: "Default")
                                Text("Last action: $lastRoutedActionLabel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = { navigate(1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit preset") }
                            } }
                            OutlinedButton(onClick = { showMappings = !showMappings }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showMappings) "Hide mappings" else "View mappings") }
                            if (showMappings) MappingOverview(config)
                            Text("Test an action", style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onTestAnkiAction(AnkiAction.FLIP_CARD) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test Anki") }
                                OutlinedButton(onClick = { onTestMyWhooshAction(MyWhooshAction.SHIFT_UP) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test bike") }
                            }
                        }
                        1 -> PresetEditor(config, onConfigChange, allowAnkiDroid = false, onDirtyChanged = { presetDirty = it })
                        else -> {
                            Text("Settings", style = MaterialTheme.typography.headlineSmall)
                            Text("Controller connection", style = MaterialTheme.typography.titleMedium)
                            listOf(
                                Triple(PodAssignment.RIGHT_ANKI_ONLY, true, "Right controller only"),
                                Triple(PodAssignment.RIGHT_ANKI_LEFT_BIKE, false, "Both controllers"),
                                Triple(PodAssignment.LEGACY_SHARED, false, "Shared / single link (auto-connect any)")
                            ).forEach { (assignment, rightOnly, label) ->
                                val selected = config.podAssignment == assignment
                                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = selected,
                                        onClick = {
                                            if (!selected) onConfigChange(config.copy(podAssignment = assignment, rightPodOnlyNoZwift = rightOnly))
                                        }
                                    )
                                    Text(label)
                                }
                            }
                            OutlinedTextField(config.ubuntuAnkiConnectHost, { onConfigChange(config.copy(ubuntuAnkiConnectHost = it)) }, label = { Text("AnkiConnect host") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(portText, { portText = it.filter(Char::isDigit); portText.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port -> onConfigChange(config.copy(ubuntuAnkiConnectPort = port)) } }, label = { Text("AnkiConnect port") }, isError = portText.toIntOrNull() !in 1..65535, supportingText = { if (portText.toIntOrNull() !in 1..65535) Text("Enter a port from 1 to 65535") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
                            if (advanced) {
                                StudioHardwareAndAutostartTab(config, connectedRightMac, connectedLeftMac, onConfigChange)
                                DesktopOptionsCard(config = config, onConfigChange = onConfigChange)
                                NetworkingTestCard(config = config, onConfigChange = onConfigChange, hasMyWhooshClient = hasMyWhooshClient, onTestAnkiAction = onTestAnkiAction, onTestMyWhooshAction = onTestMyWhooshAction)
                                StudioDiagnosticLogsTab()
                                OutlinedButton(onClick = onMinimizeToTray) { Text("Minimize to tray") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (pendingPage != null) AlertDialog(onDismissRequest = { pendingPage = null }, title = { Text("Discard unsaved changes?") }, text = { Text("Save your preset before leaving, or discard the changes.") }, confirmButton = { TextButton(onClick = { page = pendingPage ?: page; presetDirty = false; pendingPage = null }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { pendingPage = null }) { Text("Keep editing") } })

}


@Composable
private fun TopTelemetryHeaderBar(
    config: AppConfiguration,
    onConfigChange: (AppConfiguration) -> Unit,
    isBridgeRunning: Boolean,
    onToggleBridge: () -> Unit,
    bleState: BleConnectionState,
    connectedRightMac: String?,
    connectedLeftMac: String?,
    hasMyWhooshClient: Boolean,
    lastRoutedActionLabel: String,
    onMinimizeToTray: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111827)),
        border = BorderStroke(1.dp, Color(0xFF1F2937))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            if (isBridgeRunning) Color(0xFF064E3B) else Color(0xFF1E293B),
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (isBridgeRunning) Color(0xFF10B981) else Color(0xFF334155),
                            RoundedCornerShape(10.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "AR",
                        color = if (isBridgeRunning) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "ClickControl",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        TelemetryBadge(
                            text = "BLE: ${bleState.name}",
                            bgColor = when (bleState) {
                                BleConnectionState.CONNECTED_BOTH_PODS,
                                BleConnectionState.CONNECTED_RIGHT_ONLY,
                                BleConnectionState.CONNECTED_LEFT_ONLY -> Color(0xFF064E3B)
                                BleConnectionState.SCANNING,
                                BleConnectionState.CONNECTING,
                                BleConnectionState.HANDSHAKING -> Color(0xFF78350F)
                                BleConnectionState.ERROR -> Color(0xFF7F1D1D)
                                BleConnectionState.DISCONNECTED -> Color(0xFF1E293B)
                            },
                            textColor = when (bleState) {
                                BleConnectionState.CONNECTED_BOTH_PODS,
                                BleConnectionState.CONNECTED_RIGHT_ONLY,
                                BleConnectionState.CONNECTED_LEFT_ONLY -> Color(0xFF6EE7B7)
                                BleConnectionState.SCANNING,
                                BleConnectionState.CONNECTING,
                                BleConnectionState.HANDSHAKING -> Color(0xFFFCD34D)
                                BleConnectionState.ERROR -> Color(0xFFFCA5A5)
                                BleConnectionState.DISCONNECTED -> Color(0xFF94A3B8)
                            }
                        )
                        TelemetryBadge(
                            text = if (hasMyWhooshClient) {
                                "MyWhoosh TCP :${config.openBikeServerPort} CONNECTED"
                            } else {
                                "TCP :${config.openBikeServerPort} Waiting (Robot Fallback Ready)"
                            },
                            bgColor = if (hasMyWhooshClient) Color(0xFF0C4A6E) else Color(0xFF1E293B),
                            textColor = if (hasMyWhooshClient) Color(0xFF7DD3FC) else Color(0xFFFCD34D)
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Right Pod: ${connectedRightMac ?: config.pinnedRightPodMac?.ifBlank { "Scanning..." } ?: "Scanning..."}",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (config.rightPodOnlyNoZwift) {
                                "Left Pod: Standby (1-Pod Mode)"
                            } else {
                                "Left Pod: ${connectedLeftMac ?: config.pinnedLeftPodMac?.ifBlank { "Scanning..." } ?: "Scanning..."}"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "AnkiConnect: ${config.ubuntuAnkiConnectHost}:${config.ubuntuAnkiConnectPort}",
                            color = Color(0xFF6EE7B7),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Center: Last Routed Action Pill
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .background(Color(0xFF0B0F19), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LAST ROUTED",
                        color = Color(0xFF64748B),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = lastRoutedActionLabel,
                        color = Color(0xFFFCD34D),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Right: Auto-Connect Quick Toggle + Start/Stop Bridge + Minimize to Tray
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        onConfigChange(config.copy(autoStartOnControllerWake = !config.autoStartOnControllerWake))
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        1.dp,
                        if (config.autoStartOnControllerWake) Color(0xFF10B981) else Color(0xFF334155)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (config.autoStartOnControllerWake) "Auto-Connect: ON" else "Auto-Connect: OFF",
                        color = if (config.autoStartOnControllerWake) Color(0xFF6EE7B7) else Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Button(
                    onClick = onToggleBridge,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isBridgeRunning) Color(0xFFBE123C) else Color(0xFF10B981),
                        contentColor = Color.White
                    ),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = if (isBridgeRunning) "Stop Bridge" else "Start Bridge",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onMinimizeToTray,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Tray ▾",
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TelemetryBadge(
    text: String,
    bgColor: Color,
    textColor: Color
) {
    Box(
        modifier = Modifier
            .background(bgColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ===========================================================================
// 2. 5-MODE OPERATING SELECTOR STRIP
// ===========================================================================

@Composable
private fun OperatingModePillStrip(
    selectedMode: OperatingMode,
    onSelectMode: (OperatingMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OperatingMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelectMode(mode) },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF0C4A6E) else Color(0xFF111827)
                ),
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1F2937)
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mode.shortTitle,
                            color = if (isSelected) Color(0xFF7DD3FC) else Color(0xFFE2E8F0),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(Color(0xFF38BDF8), CircleShape)
                            )
                        }
                    }
                    Text(
                        text = mode.subtitle,
                        color = if (isSelected) Color(0xFFBAE6FD) else Color(0xFF64748B),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// ===========================================================================
// 3. LEFT PANE: VERIFIED PHYSICAL ZWIFT CLICK V2 DUAL-POD 2D VISUALIZER
//    (Exact 5-Circle Geometry per Pod + Diagonal Corner Lugs + Top-Right Blue Badges)
// ===========================================================================

@Composable
private fun ZwiftClickV2PhysicalPodsVisualizer(
    config: AppConfiguration,
    isBridgeRunning: Boolean,
    selectedButton: ClickButton,
    holdSimulationMode: Boolean,
    onSelectButton: (ClickButton) -> Unit,
    onToggleHoldMode: (Boolean) -> Unit,
    onTogglePodMode: () -> Unit,
    onSimulateGesture: (ClickButton, PressType) -> Unit,
    onApplyPresetBindings: (Map<ModeButtonBindingKey, MappedTargetAction>, Boolean) -> Unit
) {
    val activePressType = if (holdSimulationMode) PressType.LONG_PRESS else PressType.SINGLE_CLICK

    // Top Simulator Controls Row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "ZWIFT CLICK V2 HARDWARE (5-CIRCLE PODS)",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
            )
            Text(
                text = "Y Top Cyan • Z Left Orange • A Right Green • B Bottom Pink • + / – Bottom Black Circles",
                color = Color(0xFF6EE7B7),
                fontSize = 10.sp
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = { onToggleHoldMode(false) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!holdSimulationMode) Color(0xFF0EA5E9) else Color(0xFF1E293B),
                    contentColor = if (!holdSimulationMode) Color(0xFF082F49) else Color(0xFF94A3B8)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Click (Single)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { onToggleHoldMode(true) },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (holdSimulationMode) Color(0xFFF59E0B) else Color(0xFF1E293B),
                    contentColor = if (holdSimulationMode) Color(0xFF082F49) else Color(0xFF94A3B8)
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Hold (450ms)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Dual-Pod Stage (Left Pod + Right Pod side-by-side)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ---------------- LEFT POD (5 Black Circular Buttons: ^, <, >, v, and – Below v) ----------------
        ZwiftClickPodDiagram(
            title = if (config.rightPodOnlyNoZwift) "LEFT POD (Standby)" else "LEFT POD (Active)",
            isActivePod = !config.rightPodOnlyNoZwift,
            isLedLit = !config.rightPodOnlyNoZwift && isBridgeRunning,
            topButton = ClickButton.UP,
            leftButton = ClickButton.LEFT,
            rightButton = ClickButton.RIGHT,
            bottomDiamondButton = ClickButton.DOWN,
            bottomShiftButton = ClickButton.SHIFT_DOWN_LEFT,
            config = config,
            activePressType = activePressType,
            selectedButton = selectedButton,
            onSelectButton = onSelectButton,
            onSimulateGesture = onSimulateGesture
        )

        // ---------------- RIGHT POD (Y Cyan, Z Orange, A Green, B Pink + Black '+' Below B) ----------------
        ZwiftClickPodDiagram(
            title = "RIGHT POD (Primary Active)",
            isActivePod = true,
            isLedLit = isBridgeRunning,
            topButton = ClickButton.Y,
            leftButton = ClickButton.Z,
            rightButton = ClickButton.A,
            bottomDiamondButton = ClickButton.B,
            bottomShiftButton = ClickButton.SHIFT_UP_RIGHT,
            config = config,
            activePressType = activePressType,
            selectedButton = selectedButton,
            onSelectButton = onSelectButton,
            onSimulateGesture = onSimulateGesture
        )
    }

    // Bottom Pod Mode & 1-Tap Ergonomic Presets Bar
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
        border = BorderStroke(1.dp, Color(0xFF1E293B))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Single-Pod Ergonomic Layout Presets (${config.mode.shortTitle}):",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = onTogglePodMode,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(26.dp)
                ) {
                    Text(
                        text = if (config.rightPodOnlyNoZwift) "1-Pod Mode (Right Only)" else "Dual-Pod Mode (Both)",
                        fontSize = 10.sp
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Preset 1: Bottom Pair Shifting (+ Up / B Down) + Y/Z/A Anki
                OutlinedButton(
                    onClick = {
                        val updated = config.customBindings.toMutableMap()
                        val m = config.mode
                        updated[ModeButtonBindingKey(m, ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_UP
                        updated[ModeButtonBindingKey(m, ClickButton.B, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_DOWN
                        updated[ModeButtonBindingKey(m, ClickButton.A, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_GOOD
                        updated[ModeButtonBindingKey(m, ClickButton.Z, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_AGAIN
                        updated[ModeButtonBindingKey(m, ClickButton.Y, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_HARD
                        onApplyPresetBindings(updated, false)
                        DiagnosticLogger.info(LogCategory.ROUTER, "Applied Preset 1: +/B Shift • Y/Z/A Anki")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Preset 1: +/B Shift • Y/Z/A Anki", fontSize = 10.sp, maxLines = 1)
                }

                // Preset 2: Vertical Y/B Shift + Horizontal Z/A Anki
                OutlinedButton(
                    onClick = {
                        val updated = config.customBindings.toMutableMap()
                        val m = config.mode
                        updated[ModeButtonBindingKey(m, ClickButton.Y, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_UP
                        updated[ModeButtonBindingKey(m, ClickButton.B, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_DOWN
                        updated[ModeButtonBindingKey(m, ClickButton.Z, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_AGAIN
                        updated[ModeButtonBindingKey(m, ClickButton.A, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_GOOD
                        updated[ModeButtonBindingKey(m, ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_EASY
                        onApplyPresetBindings(updated, false)
                        DiagnosticLogger.info(LogCategory.ROUTER, "Applied Preset 2: Y/B Shift • Z/A Anki")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Preset 2: Y/B Shift • Z/A Anki", fontSize = 10.sp, maxLines = 1)
                }

                // Preset 3: Classic Z/A Shift + Y/B Anki
                OutlinedButton(
                    onClick = {
                        val updated = config.customBindings.toMutableMap()
                        val m = config.mode
                        updated[ModeButtonBindingKey(m, ClickButton.Z, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_UP
                        updated[ModeButtonBindingKey(m, ClickButton.A, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_DOWN
                        updated[ModeButtonBindingKey(m, ClickButton.Y, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_AGAIN
                        updated[ModeButtonBindingKey(m, ClickButton.B, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_GOOD
                        onApplyPresetBindings(updated, true)
                        DiagnosticLogger.info(LogCategory.ROUTER, "Applied Preset 3: Z/A Shift • Y/B Anki")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                ) {
                    Text("Preset 3: Z/A Shift • Y/B Anki", fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Renders a single Zwift Click v2 physical pod matching the official 5-circle diagram:
 * - Light metallic/slate rounded-octagon housing with 4 diagonal strap lugs at 45°/135°/225°/315°
 * - Inner engraved diamond guide lines connecting Top, Left, Right, and Bottom-of-Diamond buttons
 * - 5 identical circular buttons (54.dp diameter):
 *   - Top (y = 14.dp, centered)
 *   - Left (x = 16.dp, y = 66.dp)
 *   - Right (x = 136.dp, y = 66.dp)
 *   - Bottom of Diamond (y = 118.dp, centered)
 *   - Bottom Shift Circle (– or +, y = 178.dp, centered directly below Bottom-of-Diamond)
 * - Each circular button has a floating blue circular badge at its top-right corner.
 */
@Composable
private fun ZwiftClickPodDiagram(
    title: String,
    isActivePod: Boolean,
    isLedLit: Boolean,
    topButton: ClickButton,
    leftButton: ClickButton,
    rightButton: ClickButton,
    bottomDiamondButton: ClickButton,
    bottomShiftButton: ClickButton,
    config: AppConfiguration,
    activePressType: PressType,
    selectedButton: ClickButton,
    onSelectButton: (ClickButton) -> Unit,
    onSimulateGesture: (ClickButton, PressType) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.alpha(if (isActivePod) 1f else 0.45f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (isLedLit) Color(0xFF00AEEF) else Color(0xFF475569), CircleShape)
            )
            Text(
                text = title,
                color = if (isActivePod) Color(0xFFE2E8F0) else Color(0xFF94A3B8),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Outer Pod Canvas + 5 Circular Buttons Container
        Box(
            modifier = Modifier.size(width = 220.dp, height = 256.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            // 4 Diagonal Mounting Strap Lugs (Top-Left, Top-Right, Bottom-Left, Bottom-Right)
            Box(
                modifier = Modifier
                    .offset(x = (-68).dp, y = 24.dp)
                    .size(width = 30.dp, height = 22.dp)
                    .rotate(-42f)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .offset(x = 68.dp, y = 24.dp)
                    .size(width = 30.dp, height = 22.dp)
                    .rotate(42f)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .offset(x = (-68).dp, y = 166.dp)
                    .size(width = 30.dp, height = 22.dp)
                    .rotate(42f)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .offset(x = 68.dp, y = 166.dp)
                    .size(width = 30.dp, height = 22.dp)
                    .rotate(-42f)
                    .background(Color(0xFFCBD5E1), RoundedCornerShape(6.dp))
                    .border(2.dp, Color(0xFF1E293B), RoundedCornerShape(6.dp))
            )

            // Main Pod Body (Light silver-slate circular housing with bottom chin extension for 5th button)
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(width = 200.dp, height = 206.dp)
                    .background(Color(0xFFE2E8F0), RoundedCornerShape(92.dp))
                    .border(2.5.dp, Color(0xFF1E293B), RoundedCornerShape(92.dp))
            )
            // Bottom Chin Extension Housing for 5th Circular Button (– / +)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = (-4).dp)
                    .size(width = 82.dp, height = 78.dp)
                    .background(Color(0xFFE2E8F0), CircleShape)
                    .border(2.5.dp, Color(0xFF1E293B), CircleShape)
            )

            // Inner Diamond Guide Lines Canvas
            Canvas(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .size(width = 200.dp, height = 206.dp)
            ) {
                val cx = size.width / 2f
                val topPt = Offset(cx, 38f)
                val leftPt = Offset(42f, 95f)
                val rightPt = Offset(size.width - 42f, 95f)
                val bottomPt = Offset(cx, 152f)

                val diamondPath = Path().apply {
                    moveTo(topPt.x, topPt.y)
                    lineTo(rightPt.x, rightPt.y)
                    lineTo(bottomPt.x, bottomPt.y)
                    lineTo(leftPt.x, leftPt.y)
                    close()
                }
                drawPath(
                    path = diamondPath,
                    color = Color(0xFF64748B),
                    style = Stroke(width = 2.5f)
                )
            }

            // 1. TOP DIAMOND CIRCLE BUTTON (^ on Left Pod | Y Cyan on Right Pod)
            CircularHardwareButton(
                button = topButton,
                actionLabel = resolveShortLabel(config, topButton, activePressType),
                isSelected = selectedButton == topButton,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 14.dp),
                onClick = {
                    onSelectButton(topButton)
                    onSimulateGesture(topButton, activePressType)
                }
            )

            // 2. LEFT DIAMOND CIRCLE BUTTON (< on Left Pod | Z Orange on Right Pod)
            CircularHardwareButton(
                button = leftButton,
                actionLabel = resolveShortLabel(config, leftButton, activePressType),
                isSelected = selectedButton == leftButton,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = 20.dp, y = 66.dp),
                onClick = {
                    onSelectButton(leftButton)
                    onSimulateGesture(leftButton, activePressType)
                }
            )

            // 3. RIGHT DIAMOND CIRCLE BUTTON (> on Left Pod | A Green on Right Pod)
            CircularHardwareButton(
                button = rightButton,
                actionLabel = resolveShortLabel(config, rightButton, activePressType),
                isSelected = selectedButton == rightButton,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-20).dp, y = 66.dp),
                onClick = {
                    onSelectButton(rightButton)
                    onSimulateGesture(rightButton, activePressType)
                }
            )

            // 4. BOTTOM-OF-DIAMOND CIRCLE BUTTON (v on Left Pod | B Pink on Right Pod)
            CircularHardwareButton(
                button = bottomDiamondButton,
                actionLabel = resolveShortLabel(config, bottomDiamondButton, activePressType),
                isSelected = selectedButton == bottomDiamondButton,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 118.dp),
                onClick = {
                    onSelectButton(bottomDiamondButton)
                    onSimulateGesture(bottomDiamondButton, activePressType)
                }
            )

            // 5. BOTTOM SHIFT CIRCLE BUTTON (– on Left Pod below v | + on Right Pod below B)
            CircularHardwareButton(
                button = bottomShiftButton,
                actionLabel = resolveShortLabel(config, bottomShiftButton, activePressType),
                isSelected = selectedButton == bottomShiftButton,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 180.dp),
                onClick = {
                    onSelectButton(bottomShiftButton)
                    onSimulateGesture(bottomShiftButton, activePressType)
                }
            )
        }
    }
}

/**
 * Renders an authentic 56.dp circular Zwift Click v2 button with:
 * - Main circular button face (Black for ^/</>/v/–/+, Cyan for Y, Orange for Z, Lime Green for A, Hot Pink for B)
 * - Bold white center glyph (Y, Z, A, B, +, –, ^, <, >, v) + compact mapped action label underneath
 * - Floating blue circular sub-badge (`Color(0xFF0284C7)` with white border) at TopEnd (2 o'clock)
 */
@Composable
private fun CircularHardwareButton(
    button: ClickButton,
    actionLabel: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = Color(button.badgeColorHex)
    val ringColor = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B)
    val ringWidth = if (isSelected) 3.dp else 1.5.dp

    Box(
        modifier = modifier.size(62.dp),
        contentAlignment = Alignment.Center
    ) {
        // Main 54.dp Circular Button Cap
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(bgColor, CircleShape)
                .border(ringWidth, ringColor, CircleShape)
                .clickable { onClick() }
                .padding(2.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = button.shortBadge,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
                Text(
                    text = actionLabel,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Top-Right (2 o'clock) Floating Blue Circular Icon Sub-Badge (matches official diagram)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFF0284C7), CircleShape)
                .border(1.5.dp, Color.White, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = button.subBadgeGlyph.ifEmpty { button.shortBadge },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp
            )
        }
    }
}

private fun resolveShortLabel(
    config: AppConfiguration,
    button: ClickButton,
    pressType: PressType
): String {
    val resolved = if (config.podAssignment != PodAssignment.LEGACY_SHARED) ButtonMappingStore.assignedAction(config.podAssignment, button, pressType) else ButtonMappingStore.resolveAction(
        mode = config.mode,
        button = button,
        pressType = pressType,
        customBindings = config.customBindings,
        rightPodOnlyNoZwift = config.rightPodOnlyNoZwift,
        useZForShiftDownInSinglePodMode = config.useZForShiftDownInSinglePodMode
    )
    return resolved.label
        .replace("MyWhoosh: ", "")
        .replace("Anki: ", "")
        .replace("Flip / ", "")
        .take(9)
}

// ===========================================================================
// 4. RIGHT PANE TAB 1: SELECTED BUTTON SPOTLIGHT + FULL 10-BUTTON KEYMAP
// ===========================================================================

@Composable
private fun StudioKeymapInspectorTab(
    config: AppConfiguration,
    selectedButton: ClickButton,
    onSelectButton: (ClickButton) -> Unit,
    onSimulateGesture: (ClickButton, PressType) -> Unit,
    onUpdateBindings: (Map<ModeButtonBindingKey, MappedTargetAction>) -> Unit
) {
    var editingMode by remember(config.mode) { mutableStateOf(config.mode) }

    val allTenButtons = remember {
        listOf(
            ClickButton.Y,
            ClickButton.Z,
            ClickButton.A,
            ClickButton.B,
            ClickButton.SHIFT_UP_RIGHT,
            ClickButton.SHIFT_DOWN_LEFT,
            ClickButton.UP,
            ClickButton.LEFT,
            ClickButton.RIGHT,
            ClickButton.DOWN
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (config.podAssignment != PodAssignment.LEGACY_SHARED) {
            Text("Preset mappings are active. Editing a binding switches to custom legacy mappings.", color = Color(0xFFFCD34D), fontSize = 11.sp)
        }
        // Spotlight Card for Currently Selected Physical Button
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0F19)),
            border = BorderStroke(1.dp, Color(0xFF0284C7))
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(selectedButton.badgeColorHex), CircleShape)
                                .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = selectedButton.shortBadge,
                                color = Color.White,
                                fontWeight = FontWeight.Black,
                                fontSize = 15.sp
                            )
                        }
                        Column {
                            Text(
                                text = "Selected Button: ${selectedButton.displayName}",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "BLE Bitmask: 0x${selectedButton.bitmask.toString(16).uppercase().padStart(4, '0')} • Mode: ${editingMode.shortTitle}",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { onSimulateGesture(selectedButton, PressType.SINGLE_CLICK) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Test Click", fontSize = 11.sp)
                        }
                        OutlinedButton(
                            onClick = { onSimulateGesture(selectedButton, PressType.LONG_PRESS) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Test Hold", fontSize = 11.sp)
                        }
                    }
                }

                ColorCodedButtonCard(
                    mode = editingMode,
                    button = selectedButton,
                    customBindings = config.customBindings,
                    assignment = config.podAssignment,
                    onSelectAction = { pressType, target ->
                        val key = ModeButtonBindingKey(editingMode, selectedButton, pressType)
                        val updated = if (target == null) config.customBindings - key else config.customBindings + (key to target)
                        onUpdateBindings(updated)
                    }
                )
            }
        }

        // Mode Filter Strip + Reset Mode Defaults Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OperatingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = editingMode == mode,
                        onClick = { editingMode = mode },
                        label = {
                            Text(
                                text = if (mode == config.mode) "★ ${mode.shortTitle}" else mode.shortTitle,
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            OutlinedButton(
                onClick = {
                    val cleared = config.customBindings.filterKeys { it.mode != editingMode }
                    onUpdateBindings(cleared)
                    DiagnosticLogger.info(LogCategory.ROUTER, "Reset ${editingMode.shortTitle} bindings to default")
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("Reset Mode Defaults", fontSize = 11.sp)
            }
        }

        // All 10 Physical Buttons List
        allTenButtons.forEach { button ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectButton(button) }
            ) {
                ColorCodedButtonCard(
                    mode = editingMode,
                    button = button,
                    customBindings = config.customBindings,
                    assignment = config.podAssignment,
                    onSelectAction = { pressType, target ->
                        val key = ModeButtonBindingKey(editingMode, button, pressType)
                        val updated = if (target == null) config.customBindings - key else config.customBindings + (key to target)
                        onUpdateBindings(updated)
                    }
                )
            }
        }
    }
}

// ===========================================================================
// 5. RIGHT PANE TAB 2: NETWORK & 1-CLICK END-TO-END ACTION TESTS
// ===========================================================================

@Composable
private fun StudioNetworkAndTestsTab(
    config: AppConfiguration,
    onConfigChange: (AppConfiguration) -> Unit,
    hasMyWhooshClient: Boolean,
    onTestAnkiAction: (AnkiAction) -> Unit,
    onTestMyWhooshAction: (MyWhooshAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NetworkingTestCard(
            config = config,
            onConfigChange = onConfigChange,
            hasMyWhooshClient = hasMyWhooshClient,
            onTestAnkiAction = onTestAnkiAction,
            onTestMyWhooshAction = onTestMyWhooshAction
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Extended Diagnostics & OpenBikeControl Port",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = config.openBikeServerPort.toString(),
                        onValueChange = { input ->
                            val port = input.filter { it.isDigit() }.toIntOrNull() ?: 21587
                            onConfigChange(config.copy(openBikeServerPort = port))
                        },
                        label = { Text("OpenBikeControl TCP Port (Default 21587)") },
                        modifier = Modifier.weight(1f)
                    )

                    OutlinedButton(
                        onClick = { onTestAnkiAction(AnkiAction.UNDO) }
                    ) {
                        Text("Test Anki Undo", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val title = DesktopMyWhooshRobot.inspectFocusedWindow()
                            DiagnosticLogger.info(LogCategory.SYSTEM, "Active/Focused Desktop Window: $title")
                        }
                    ) {
                        Text("Inspect Focused Window", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 6. RIGHT PANE TAB 3: AUTO-START, HARDWARE & MAC PINNING OPTIONS
// ===========================================================================

@Composable
private fun StudioHardwareAndAutostartTab(
    config: AppConfiguration,
    connectedRightMac: String?,
    connectedLeftMac: String?,
    onConfigChange: (AppConfiguration) -> Unit
) {
    var osLoginAutostart by remember {
        mutableStateOf(
            File(System.getProperty("user.home") ?: ".", ".config/autostart/ankiride-bridge.desktop").exists()
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Auto-Start & Continuous Auto-Connect",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                OptionToggleRow(
                    title = "Auto-Start Bridge & BLE Auto-Connect on App Launch",
                    subtitle = "Automatically starts TCP Server (:21587), mDNS, and BLE controller scan on startup",
                    checked = config.autoStartOnControllerWake,
                    onCheckedChange = { onConfigChange(config.copy(autoStartOnControllerWake = it)) }
                )

                OptionToggleRow(
                    title = "Launch at OS Login (~/.config/autostart)",
                    subtitle = "Automatically launches ClickControl when you sign in to your desktop session",
                    checked = osLoginAutostart,
                    onCheckedChange = { enabled ->
                        val file = File(System.getProperty("user.home") ?: ".", ".config/autostart/ankiride-bridge.desktop")
                        try {
                            if (enabled) {
                                val launcher = System.getProperty("jpackage.app-path")?.let(::File)
                                    ?: File("/opt/clickcontrol/bin/ClickControl")
                                require(launcher.isFile && launcher.canExecute()) { "Install the packaged application before enabling login launch" }
                                val escapedLauncher = launcher.absolutePath.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%")
                                file.parentFile?.mkdirs()
                                file.writeText(
                                    """
                                    [Desktop Entry]
                                    Type=Application
                                    Name=ClickControl
                                    Comment=Zwift Click v2 Bridge for Anki & MyWhoosh
                                    Exec="$escapedLauncher"
                                    Terminal=false
                                    X-GNOME-Autostart-enabled=true
                                    """.trimIndent() + "\n"
                                )
                                osLoginAutostart = true
                                DiagnosticLogger.info(LogCategory.SYSTEM, "Installed OS login autostart entry: ${file.absolutePath}")
                            } else if (file.exists()) {
                                check(file.delete()) { "Could not remove autostart entry" }
                                osLoginAutostart = false
                                DiagnosticLogger.info(LogCategory.SYSTEM, "Removed OS login autostart entry: ${file.absolutePath}")
                            }
                        } catch (e: Exception) {
                            DiagnosticLogger.warn(LogCategory.SYSTEM, "Could not update autostart file: ${e.message}")
                        }
                    }
                )
            }
        }

        DesktopOptionsCard(
            config = config,
            onConfigChange = onConfigChange
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Controller MAC Address Pinning & Watchdog",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = "Pinned Right MAC: ${config.pinnedRightPodMac?.ifBlank { "Any (Auto-Discover)" } ?: "Any (Auto-Discover)"} • Left Watchdog: ${config.leftPodAutoReconnectSec}s",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var rightAddress by remember(config.pinnedRightPodMac) { mutableStateOf(config.pinnedRightPodMac.orEmpty()) }
                var leftAddress by remember(config.pinnedLeftPodMac) { mutableStateOf(config.pinnedLeftPodMac.orEmpty()) }
                var addressError by remember { mutableStateOf("") }
                Text("Unknown devices appear in Live Logs. Enter the MAC address (or macOS Bluetooth UUID) to assign a side.", fontSize = 12.sp)
                OutlinedTextField(value = rightAddress, onValueChange = { rightAddress = it }, label = { Text("Right pod address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = leftAddress, onValueChange = { leftAddress = it }, label = { Text("Left pod address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (addressError.isNotEmpty()) Text(addressError, color = Color(0xFFFCA5A5), fontSize = 12.sp)
                OutlinedButton(onClick = {
                    val right = rightAddress.trim().ifEmpty { null }
                    val left = leftAddress.trim().ifEmpty { null }
                    val valid = { value: String? -> value == null || com.ankiride.bridge.ble.DesktopZwiftBleController.isValidMacOrUuid(value) }
                    if (!valid(right) || !valid(left) || (right != null && right.equals(left, ignoreCase = true))) {
                        addressError = "Enter distinct valid MAC addresses or Bluetooth UUIDs."
                    } else {
                        addressError = ""
                        onConfigChange(config.copy(pinnedRightPodMac = right, pinnedLeftPodMac = left))
                    }
                }) { Text("Save addresses") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val rightToPin = connectedRightMac ?: config.pinnedRightPodMac
                            val leftToPin = connectedLeftMac ?: config.pinnedLeftPodMac
                            onConfigChange(
                                config.copy(
                                    pinnedRightPodMac = rightToPin,
                                    pinnedLeftPodMac = leftToPin
                                )
                            )
                            DiagnosticLogger.info(LogCategory.BLE, "Pinned controller MACs -> Right: $rightToPin, Left: $leftToPin")
                        }
                    ) {
                        Text("Lock to Connected MAC", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            onConfigChange(config.copy(pinnedRightPodMac = null, pinnedLeftPodMac = null))
                            DiagnosticLogger.info(LogCategory.BLE, "Cleared pinned controller MAC addresses")
                        }
                    ) {
                        Text("Clear MAC Pin", fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ===========================================================================
// 7. RIGHT PANE TAB 4: LIVE DIAGNOSTIC LOG VIEWER
// ===========================================================================

@Composable
private fun StudioDiagnosticLogsTab() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        DiagnosticLogViewerCard()
    }
}

// ===========================================================================
// PRESERVED REUSABLE COMPOSABLE CARDS (100% Backwards-Compatible)
// ===========================================================================

@Composable
fun NetworkingTestCard(
    config: AppConfiguration,
    onConfigChange: (AppConfiguration) -> Unit,
    hasMyWhooshClient: Boolean,
    onTestAnkiAction: (AnkiAction) -> Unit,
    onTestMyWhooshAction: (MyWhooshAction) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "AnkiConnect (:8765) & OpenBikeControl (:21587)",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )
            Text(
                text = "Configure AnkiConnect target address and trigger manual test dispatches to verify connectivity.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = config.ubuntuAnkiConnectHost,
                    onValueChange = { onConfigChange(config.copy(ubuntuAnkiConnectHost = it.trim())) },
                    label = { Text("AnkiConnect Host (e.g. 127.0.0.1 or LAN IP)") },
                    modifier = Modifier.weight(1f)
                )

                OutlinedTextField(
                    value = config.ubuntuAnkiConnectPort.toString(),
                    onValueChange = { input ->
                        val port = input.filter { it.isDigit() }.toIntOrNull() ?: 8765
                        onConfigChange(config.copy(ubuntuAnkiConnectPort = port))
                    },
                    label = { Text("Port") },
                    modifier = Modifier.width(110.dp)
                )
            }

            Text(text = "Manual Connection & Shifting Tests:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onTestAnkiAction(AnkiAction.FLIP_CARD) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Anki Flip (guiShowAnswer)", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { onTestAnkiAction(AnkiAction.FLIP_OR_GOOD) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Good (3)", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { onTestMyWhooshAction(MyWhooshAction.SHIFT_UP) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Shift Up (+)", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { onTestMyWhooshAction(MyWhooshAction.SHIFT_DOWN) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Shift Down (–)", fontSize = 11.sp)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1117), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OpenBikeControl Server: Port ${config.openBikeServerPort} • mDNS _openbikecontrol._tcp.local.",
                    color = Color(0xFFC9D1D9),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (hasMyWhooshClient) "MyWhoosh: CONNECTED" else "MyWhoosh: WAITING",
                    color = if (hasMyWhooshClient) Color(0xFF7EE787) else Color(0xFFFFA657),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DesktopOptionsCard(
    config: AppConfiguration,
    onConfigChange: (AppConfiguration) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Desktop Hardware & Feedback Options",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            )

            OptionToggleRow(
                title = "1-Controller Default (Right Pod Only)",
                subtitle = "Zero 24h sleep lock; switch off to enable both Left and Right pods",
                checked = config.rightPodOnlyNoZwift,
                onCheckedChange = { onConfigChange(config.copy(rightPodOnlyNoZwift = it)) }
            )

            if (config.rightPodOnlyNoZwift) {
                OptionToggleRow(
                    title = "Left 'Z' (Orange) Shifts Down in 1-Pod Mode",
                    subtitle = "Instant shifting in 1-pod mode: Bottom '+' = Shift Up, Left 'Z' = Shift Down, Y/B/A = Anki",
                    checked = config.useZForShiftDownInSinglePodMode,
                    onCheckedChange = { onConfigChange(config.copy(useZForShiftDownInSinglePodMode = it)) }
                )
            }

            OptionToggleRow(
                title = "Floating Mini HUD Pill",
                subtitle = "Always-on-top transparent mini HUD pill floating over MyWhoosh and Anki",
                checked = config.floatingHudPillEnabled,
                onCheckedChange = { onConfigChange(config.copy(floatingHudPillEnabled = it)) }
            )

            OptionToggleRow(
                title = "Audio Feedback Cues",
                subtitle = "Plays subtle audio confirmation tones when shifting or reviewing cards",
                checked = config.audioFeedbackEnabled,
                onCheckedChange = { onConfigChange(config.copy(audioFeedbackEnabled = it)) }
            )

            OptionToggleRow(
                title = "Keyboard Robot Fallback (I / K)",
                subtitle = "Injects keystrokes 'I' (Shift Up) and 'K' (Shift Down) via java.awt.Robot when no TCP client is connected",
                checked = config.useMyShiftTouchFallbackWhenNoClient,
                onCheckedChange = { onConfigChange(config.copy(useMyShiftTouchFallbackWhenNoClient = it)) }
            )
        }
    }
}

@Composable
fun OptionToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun ColorCodedButtonCard(
    mode: OperatingMode,
    button: ClickButton,
    customBindings: Map<ModeButtonBindingKey, MappedTargetAction>,
    assignment: PodAssignment = PodAssignment.LEGACY_SHARED,
    onSelectAction: (PressType, MappedTargetAction?) -> Unit
) {
    val singleResolved = if (assignment == PodAssignment.LEGACY_SHARED) ButtonMappingStore.resolveAction(mode, button, PressType.SINGLE_CLICK, customBindings) else ButtonMappingStore.assignedAction(assignment, button, PressType.SINGLE_CLICK)
    val longResolved = if (assignment == PodAssignment.LEGACY_SHARED) ButtonMappingStore.resolveAction(mode, button, PressType.LONG_PRESS, customBindings) else ButtonMappingStore.assignedAction(assignment, button, PressType.LONG_PRESS)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(Color(button.badgeColorHex), shape = CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = button.shortBadge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = button.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }

            GestureActionPickerRow(
                gestureLabel = "Single Press",
                resolvedAction = singleResolved,
                isCustom = customBindings.containsKey(ModeButtonBindingKey(mode, button, PressType.SINGLE_CLICK)),
                onSelect = { onSelectAction(PressType.SINGLE_CLICK, it) }
            )

            GestureActionPickerRow(
                gestureLabel = "Long Press (450ms)",
                resolvedAction = longResolved,
                isCustom = customBindings.containsKey(ModeButtonBindingKey(mode, button, PressType.LONG_PRESS)),
                onSelect = { onSelectAction(PressType.LONG_PRESS, it) }
            )
        }
    }
}

@Composable
fun GestureActionPickerRow(
    gestureLabel: String,
    resolvedAction: MappedTargetAction,
    isCustom: Boolean,
    onSelect: (MappedTargetAction?) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gestureLabel,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = resolvedAction.label,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    resolvedAction.myWhooshAction != null -> Color(0xFF64B5F6)
                    resolvedAction.ankiAction != null -> Color(0xFF81C784)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }

        Box {
            OutlinedButton(onClick = { menuExpanded = true }) {
                Text(if (isCustom) "Custom ▾" else "Change ▾", fontSize = 11.sp)
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Reset to Mode Default") },
                    onClick = {
                        onSelect(null)
                        menuExpanded = false
                    }
                )
                MappedTargetAction.entries.filterNot { it == MappedTargetAction.SCREEN_CLICK || it == MappedTargetAction.KEYBOARD_INPUT }.forEach { target ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = target.label,
                                color = when {
                                    target.myWhooshAction != null -> Color(0xFF64B5F6)
                                    target.ankiAction != null -> Color(0xFF81C784)
                                    else -> Color.Unspecified
                                }
                            )
                        },
                        onClick = {
                            onSelect(target)
                            menuExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DiagnosticLogViewerCard() {
    val logs by DiagnosticLogger.logs.collectAsState()
    var selectedCategory by remember { mutableStateOf<LogCategory?>(null) }

    val filteredLogs = remember(logs, selectedCategory) {
        if (selectedCategory == null) logs else logs.filter { it.category == selectedCategory }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Live Diagnostic Log (${filteredLogs.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Real-time BLE packets, key injections, and OpenBikeControl events",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val text = DiagnosticLogger.formatForClipboard(selectedCategory)
                            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                            clipboard.setContents(StringSelection(text), null)
                            DiagnosticLogger.info(LogCategory.SYSTEM, "Copied ${filteredLogs.size} logs to system clipboard")
                        }
                    ) {
                        Text("Copy Logs", fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = { DiagnosticLogger.clear() }
                    ) {
                        Text("Clear", fontSize = 11.sp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
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

            val logScrollState = rememberScrollState()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(Color(0xFF0D1117), shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(logScrollState)
            ) {
                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "No log entries yet. Click 'Start Bridge' or simulate button gestures to see live events.",
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredLogs.takeLast(150).reversed().forEach { entry ->
                            val color = when (entry.level) {
                                LogLevel.ERROR -> Color(0xFFFF7B72)
                                LogLevel.WARN -> Color(0xFFD29922)
                                LogLevel.INFO -> Color(0xFFC9D1D9)
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
