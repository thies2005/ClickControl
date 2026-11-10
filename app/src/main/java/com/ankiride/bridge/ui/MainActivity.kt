package com.ankiride.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ankiride.bridge.anki.AnkiAccessibilityBridge
import com.ankiride.bridge.ble.ZwiftBleController
import com.ankiride.bridge.core.*
import com.ankiride.bridge.service.BridgeAccessibilityService
import com.ankiride.bridge.service.BridgeForegroundService
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val permissionRefreshTrigger = MutableStateFlow(0)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        permissionRefreshTrigger.value += 1
        DiagnosticLogger.info(LogCategory.PERMS, "BLE Permission result: $grants")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestBlePermissions()

        setContent {
            MaterialTheme(colorScheme = ClickControlColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val refreshCount by permissionRefreshTrigger.collectAsState()
                    AnkiRideDashboard(
                        permissionRefreshCount = refreshCount,
                        onRequestPermissions = { requestBlePermissions() },
                        onOpenAccessibility = { openAccessibilitySettings() },
                        onOpenBatteryOpt = { requestIgnoreBatteryOptimizations() }
                    )
                }
            }
        }
    }

    private fun requestBlePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            permissionRefreshTrigger.value += 1
        }
    }

    private fun openAccessibilitySettings() {
        DiagnosticLogger.info(LogCategory.PERMS, "Opening Android Accessibility Settings")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestIgnoreBatteryOptimizations() {
        DiagnosticLogger.info(LogCategory.PERMS, "Requesting battery optimization exemption")
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (BridgeAccessibilityService.activeInstance != null || BridgeAccessibilityService.isConnectedFlow.value) {
        return true
    }
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(context.packageName, ignoreCase = true)
}

fun isBatteryOptimizationExempt(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun areBlePermissionsGranted(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun AnkiRideDashboard(
    permissionRefreshCount: Int,
    onRequestPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBatteryOpt: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isRunning by BridgeForegroundService.isRunningFlow.collectAsState()
    val bleState by BridgeForegroundService.connectionStateFlow.collectAsState()
    val a11yConnected by BridgeAccessibilityService.isConnectedFlow.collectAsState()
    val rightMac by ZwiftBleController.lastRightMac.collectAsState()
    val leftMac by ZwiftBleController.lastLeftMac.collectAsState()
    var config by remember { mutableStateOf(ButtonMappingStore.loadFullConfiguration(context)) }
    var page by rememberSaveable { mutableStateOf(0) }
    var presetDirty by rememberSaveable { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }
    fun navigate(next: Int) { if (page == 1 && next != 1 && presetDirty) pendingPage = next else page = next }
    BackHandler(enabled = page != 0) { navigate(0) }
    var advanced by remember { mutableStateOf(false) }
    var portText by remember { mutableStateOf(config.ubuntuAnkiConnectPort.toString()) }
    var showMappings by remember { mutableStateOf(false) }
    val discovered by ZwiftBleController.discoveredAddresses.collectAsState()
    val activePreset = PresetStore.active(config)
    val accessibilityRequired = activePreset?.bindings?.values?.any { it == MappedTargetAction.SCREEN_CLICK || it == MappedTargetAction.KEYBOARD_INPUT } == true || activePreset?.ankiTarget == AnkiTarget.ANKIDROID ||
        (config.useMyShiftTouchFallbackWhenNoClient && activePreset?.bindings?.values?.any { it.myWhooshAction != null } == true)
    var a11yEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryOptimizationExempt(context)) }
    var bleGranted by remember { mutableStateOf(areBlePermissionsGranted(context)) }
    fun refresh() { a11yEnabled = isAccessibilityServiceEnabled(context); batteryExempt = isBatteryOptimizationExempt(context); bleGranted = areBlePermissionsGranted(context) }
    LaunchedEffect(a11yConnected, permissionRefreshCount) { refresh() }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(config) {
        ButtonMappingStore.saveFullConfiguration(context, config)
        if (isRunning) BridgeForegroundService.startService(context, config)
    }
    LaunchedEffect(bleState, config.mode, config.floatingHudPillEnabled) {
        FloatingHudPill.syncVisibility(BridgeAccessibilityService.activeInstance, config.floatingHudPillEnabled, bleState, config.mode)
    }
    Scaffold(bottomBar = {
        NavigationBar {
            listOf("Control", "Presets", "Settings").forEachIndexed { index, label ->
                NavigationBarItem(selected = page == index, onClick = { navigate(index) }, icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Tune, Icons.Default.Settings)[index], contentDescription = null) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ClickControl", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            when (page) {
                0 -> {
                    Text("Controller", style = MaterialTheme.typography.titleLarge)
                    ConnectionStatusCard(bleState, isRunning, rightMac, leftMac) {
                        if (isRunning) BridgeForegroundService.stopService(context)
                        else if (!bleGranted) onRequestPermissions()
                        else BridgeForegroundService.startService(context, config)
                    }
                    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Active preset", style = MaterialTheme.typography.titleMedium)
                        Text(PresetStore.active(config)?.name ?: "Default")
                        OutlinedButton(onClick = { navigate(1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Edit preset") }
                    } }
                    OutlinedButton(onClick = { showMappings = !showMappings }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (showMappings) "Hide mappings" else "View mappings") }
                    if (showMappings) MappingOverview(config)
                    if (!bleGranted || !batteryExempt || (accessibilityRequired && !a11yEnabled)) Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Setup needs attention", style = MaterialTheme.typography.titleMedium)
                            Text("Review the permissions needed for your controls.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { navigate(2) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review setup") }
                        }
                    }
                }
                1 -> PresetEditor(config, { config = it }, allowAnkiDroid = true, onDirtyChanged = { presetDirty = it })
                else -> {
                    Text("Settings", style = MaterialTheme.typography.headlineSmall)
                    PermissionsChecklistCard(a11yEnabled, batteryExempt, bleGranted, onRequestPermissions, onOpenAccessibility, onOpenBatteryOpt, accessibilityRequired)
                    Text("Controller connection", style = MaterialTheme.typography.titleMedium)
                    listOf(false to "Right controller only", true to "Both controllers").forEach { (both, label) ->
                        val selected = if (both) (config.podAssignment == PodAssignment.RIGHT_ANKI_LEFT_BIKE || config.podAssignment == PodAssignment.LEGACY_SHARED && !config.rightPodOnlyNoZwift) else (config.podAssignment == PodAssignment.RIGHT_ANKI_ONLY || config.podAssignment == PodAssignment.LEGACY_SHARED && config.rightPodOnlyNoZwift)
                        val select = { if (!selected) config = config.copy(podAssignment = if (both) PodAssignment.RIGHT_ANKI_LEFT_BIKE else PodAssignment.RIGHT_ANKI_ONLY, rightPodOnlyNoZwift = !both) }
                        Row(Modifier.fillMaxWidth().clickable(onClick = select).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selected, onClick = select); Text(label)
                        }
                    }
                    Text("MyWhoosh location", style = MaterialTheme.typography.titleMedium)
                    listOf(OperatingMode.MODE_1_TABLET_SPLIT_SCREEN to "On this device", OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH to "On another device").forEach { (mode, label) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = config.mode == mode || (mode == OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH && config.mode == OperatingMode.MODE_5_UBUNTU_ANKICONNECT) || (mode == OperatingMode.MODE_1_TABLET_SPLIT_SCREEN && config.mode == OperatingMode.MODE_4_MYWHOOSH_ONLY), onClick = { config = config.copy(mode = mode) }); Text(label) }
                    }
                    Text("Laptop Anki connection", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(config.ubuntuAnkiConnectHost, { config = config.copy(ubuntuAnkiConnectHost = it) }, label = { Text("Host address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(portText, { portText = it.filter(Char::isDigit); portText.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port -> config = config.copy(ubuntuAnkiConnectPort = port) } }, label = { Text("Port") }, isError = portText.toIntOrNull() !in 1..65535, supportingText = { if (portText.toIntOrNull() !in 1..65535) Text("Enter a port from 1 to 65535") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedButton(onClick = { advanced = !advanced }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (advanced) "Hide advanced settings" else "Advanced settings") }
                    if (advanced) {
                        Text("Controller addresses", style = MaterialTheme.typography.titleMedium)
                        Text("Discovered: ${discovered.joinToString().ifBlank { "none yet" }}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(config.pinnedRightPodMac.orEmpty(), { config = config.copy(pinnedRightPodMac = it.ifBlank { null }) }, label = { Text("Right controller address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(config.pinnedLeftPodMac.orEmpty(), { config = config.copy(pinnedLeftPodMac = it.ifBlank { null }) }, label = { Text("Left controller address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        listOf(Triple("Auto-start on wake", config.autoStartOnControllerWake, { b: Boolean -> config = config.copy(autoStartOnControllerWake = b) }), Triple("Floating status", config.floatingHudPillEnabled, { b: Boolean -> config = config.copy(floatingHudPillEnabled = b) }), Triple("Haptic feedback", config.hapticFeedbackEnabled, { b: Boolean -> config = config.copy(hapticFeedbackEnabled = b) }), Triple("Audio feedback", config.audioFeedbackEnabled, { b: Boolean -> config = config.copy(audioFeedbackEnabled = b) }), Triple("Touch fallback", config.useMyShiftTouchFallbackWhenNoClient, { b: Boolean -> config = config.copy(useMyShiftTouchFallbackWhenNoClient = b) })).forEach { (label, checked, change) ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(label); Switch(checked, change) }
                        }
                        OutlinedButton(onClick = { BridgeAccessibilityService.activeInstance?.let { com.ankiride.bridge.mywhoosh.MyShiftOverlayCalibrator.showCalibrationOverlay(it, config.mode) } }, enabled = a11yEnabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Calibrate touch fallback") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_DOWN) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test shift down") }
                            OutlinedButton(onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_UP) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test shift up") }
                        }
                        DiagnosticLogCard()
                    }
                }
            }
        }
    }
    if (pendingPage != null) AlertDialog(onDismissRequest = { pendingPage = null }, title = { Text("Discard unsaved changes?") }, text = { Text("Save your preset before leaving, or discard the changes.") }, confirmButton = { TextButton(onClick = { page = pendingPage ?: page; presetDirty = false; pendingPage = null }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { pendingPage = null }) { Text("Keep editing") } })

}


@Composable
fun PermissionsChecklistCard(
    a11yEnabled: Boolean,
    batteryExempt: Boolean,
    bleGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBatteryOpt: () -> Unit,
    a11yRequired: Boolean = true
) {
    val allReady = (!a11yRequired || a11yEnabled) && batteryExempt && bleGranted
    var expandedWhenReady by remember { mutableStateOf(false) }

    if (allReady && !expandedWhenReady) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expandedWhenReady = true },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Setup Ready",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = "Show",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 12.sp
                )
            }
        }
        return
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
                Text(
                    text = "System Setup & Permissions",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                if (allReady) {
                    Text(
                        text = "Hide",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expandedWhenReady = false }
                    )
                }
            }

            // Accessibility Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (a11yEnabled) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (a11yEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (a11yEnabled) "Accessibility Service (Active)" else if (a11yRequired) "Accessibility Service (Required)" else "Accessibility (optional for laptop Anki)",
                        fontSize = 14.sp
                    )
                }
                OutlinedButton(onClick = onOpenAccessibility) {
                    Text(if (a11yEnabled) "Settings" else "Enable")
                }
            }

            // Battery Optimization
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (batteryExempt) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (batteryExempt) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (batteryExempt) "Battery Unrestricted (Active)" else "Battery Optimization (Restricting)",
                        fontSize = 14.sp
                    )
                }
                OutlinedButton(onClick = onOpenBatteryOpt) {
                    Text(if (batteryExempt) "Exempt ✓" else "Allow Background")
                }
            }

            // Bluetooth LE Permissions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (bleGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (bleGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (bleGranted) "Bluetooth LE Permissions (Granted)" else "Bluetooth LE Permissions (Missing)",
                        fontSize = 14.sp
                    )
                }
                OutlinedButton(onClick = onRequestPermissions) {
                    Text(if (bleGranted) "Granted ✓" else "Grant")
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    bleState: BleConnectionState,
    isRunning: Boolean,
    lastRightMac: String?,
    lastLeftMac: String?,
    onToggleService: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isRunning) "OPENBIKECONTROL SERVER ACTIVE" else "BRIDGE STOPPED",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (isRunning) "Port 21587 • mDNS _openbikecontrol._tcp" else "Tap Start to connect Zwift Click & advertise to MyWhoosh",
                        fontSize = 12.sp,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onToggleService,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (isRunning) "Stop Bridge" else "Start Bridge")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (bleState) {
                    BleConnectionState.DISCONNECTED -> Text("Controller: Disconnected", fontSize = 13.sp)
                    BleConnectionState.SCANNING -> Text("Controller: Scanning for Zwift Click v2...", fontSize = 13.sp)
                    BleConnectionState.CONNECTING, BleConnectionState.HANDSHAKING -> Text("Controller: Sending 'RideOn' Handshake...", fontSize = 13.sp)
                    BleConnectionState.CONNECTED_RIGHT_ONLY -> {
                        PodBadge("Right Pod ${lastRightMac?.takeLast(5) ?: ""}", connected = true)
                        PodBadge("Left Pod", connected = false)
                    }
                    BleConnectionState.CONNECTED_BOTH_PODS -> {
                        PodBadge("Right Pod ${lastRightMac?.takeLast(5) ?: ""}", connected = true)
                        PodBadge("Left Pod ${lastLeftMac?.takeLast(5) ?: ""}", connected = true)
                    }
                    BleConnectionState.ERROR -> Text(
                        "Controller: Connection Error (See Live Log below)",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun PodBadge(label: String, connected: Boolean) {
    Box(
        modifier = Modifier
            .background(
                color = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label: ${if (connected) "Connected" else "Off"}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun CustomButtonMappingCard(
    activeMode: OperatingMode,
    rightPodOnly: Boolean,
    customBindings: Map<ModeButtonBindingKey, MappedTargetAction>,
    onUpdateBindings: (Map<ModeButtonBindingKey, MappedTargetAction>) -> Unit
) {
    var editingMode by remember(activeMode) { mutableStateOf(activeMode) }
    val buttonsToConfigure = remember(rightPodOnly) {
        val rightButtons = listOf(
            ClickButton.Z,
            ClickButton.A,
            ClickButton.Y,
            ClickButton.B,
            ClickButton.SHIFT_UP_RIGHT
        )
        val leftButtons = listOf(
            ClickButton.SHIFT_DOWN_LEFT,
            ClickButton.LEFT,
            ClickButton.RIGHT,
            ClickButton.UP,
            ClickButton.DOWN
        )
        if (rightPodOnly) rightButtons else rightButtons + leftButtons
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Button Mapping by Mode",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Each mode saves its own independent button layout",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        val cleared = customBindings.filterKeys { it.mode != editingMode }
                        onUpdateBindings(cleared)
                        DiagnosticLogger.info(LogCategory.ROUTER, "Reset ${editingMode.shortTitle} bindings to default")
                    }
                ) {
                    Text("Reset Mode", fontSize = 11.sp)
                }
            }

            // 1. Mode Tab Bar to select which mode's buttons to customize
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OperatingMode.entries.forEach { mode ->
                    FilterChip(
                        selected = editingMode == mode,
                        onClick = { editingMode = mode },
                        label = {
                            Text(
                                text = if (mode == activeMode) "★ ${mode.shortTitle}" else mode.shortTitle,
                                fontSize = 12.sp,
                                fontWeight = if (editingMode == mode) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // 2. Quick 1-Tap Presets for Combined Modes (Right Pod 4 Colorful Buttons)
            if (editingMode == OperatingMode.MODE_1_TABLET_SPLIT_SCREEN ||
                editingMode == OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH ||
                editingMode == OperatingMode.MODE_5_UBUNTU_ANKICONNECT
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val updated = customBindings.toMutableMap()
                            updated[ModeButtonBindingKey(editingMode, ClickButton.Z, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_UP
                            updated[ModeButtonBindingKey(editingMode, ClickButton.A, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_DOWN
                            updated[ModeButtonBindingKey(editingMode, ClickButton.Y, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_AGAIN
                            updated[ModeButtonBindingKey(editingMode, ClickButton.B, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_GOOD
                            onUpdateBindings(updated)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Preset: Z/A Shift • Y/B Anki", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            val updated = customBindings.toMutableMap()
                            updated[ModeButtonBindingKey(editingMode, ClickButton.Z, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_UP
                            updated[ModeButtonBindingKey(editingMode, ClickButton.B, PressType.SINGLE_CLICK)] = MappedTargetAction.MW_SHIFT_DOWN
                            updated[ModeButtonBindingKey(editingMode, ClickButton.Y, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_AGAIN
                            updated[ModeButtonBindingKey(editingMode, ClickButton.A, PressType.SINGLE_CLICK)] = MappedTargetAction.ANKI_FLIP_OR_GOOD
                            onUpdateBindings(updated)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Preset: Z/B Shift • Y/A Anki", fontSize = 11.sp)
                    }
                }
            }

            // 3. Color-Coded Button Cards showing Single Press & Long Press for editingMode
            buttonsToConfigure.forEach { button ->
                ColorCodedButtonCard(
                    mode = editingMode,
                    button = button,
                    customBindings = customBindings,
                    onSelectAction = { pressType, target ->
                        val key = ModeButtonBindingKey(editingMode, button, pressType)
                        val updated = if (target == null) {
                            customBindings - key
                        } else {
                            customBindings + (key to target)
                        }
                        onUpdateBindings(updated)
                    }
                )
            }
        }
    }
}

@Composable
fun ColorCodedButtonCard(
    mode: OperatingMode,
    button: ClickButton,
    customBindings: Map<ModeButtonBindingKey, MappedTargetAction>,
    onSelectAction: (PressType, MappedTargetAction?) -> Unit
) {
    val singleResolved = ButtonMappingStore.resolveAction(mode, button, PressType.SINGLE_CLICK, customBindings)
    val longResolved = ButtonMappingStore.resolveAction(mode, button, PressType.LONG_PRESS, customBindings)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Color(button.badgeColorHex), shape = RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = button.shortBadge,
                        color = Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = button.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
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
                        text = { Text(target.label) },
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
fun DiagnosticLogCard() {
    val context = LocalContext.current
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
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = "Real-time BLE packets, Anki clicks & OpenBikeControl events",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Quick MyWhoosh Shift Test Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_DOWN) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Shift Down (–)", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_UP) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Test Shift Up (+)", fontSize = 12.sp)
                }
            }

            // Action Buttons: Copy, Clear, Inspect Windows
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val text = DiagnosticLogger.formatForClipboard(selectedCategory)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("ClickControl Diagnostic Log", text))
                        Toast.makeText(context, "Copied ${filteredLogs.size} log lines to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Copy Logs", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(context, "Cleared diagnostic log", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Clear Logs", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        AnkiAccessibilityBridge.dumpInteractiveWindowsToLog(BridgeAccessibilityService.activeInstance)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Inspect Windows", fontSize = 12.sp)
                }
            }

            // Category Filter Chips
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
                LogCategory.entries.forEach { category ->
                    FilterChip(
                        selected = selectedCategory == category,
                        onClick = { selectedCategory = if (selectedCategory == category) null else category },
                        label = { Text(category.name, fontSize = 11.sp) }
                    )
                }
            }

            // Monospace Log Viewer Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .background(Color(0xFF0D1117), shape = RoundedCornerShape(8.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "No log entries yet. Tap 'Start Bridge' or 'Inspect Windows' to see live events.",
                        color = Color(0xFF8B949E),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        filteredLogs.takeLast(120).reversed().forEach { entry ->
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

@Composable
fun ModeSelector(
    selectedMode: OperatingMode,
    onModeSelected: (OperatingMode) -> Unit
) {
    val modes = listOf(
        Triple(
            OperatingMode.MODE_1_TABLET_SPLIT_SCREEN,
            "Tablet Split-Screen",
            "Runs MyWhoosh + native AnkiDroid side-by-side. Connects over 127.0.0.1; A/B/Y/Z clicks AnkiDroid Reviewer."
        ),
        Triple(
            OperatingMode.MODE_2_TABLET_ANKI_PHONE_MYWHOOSH,
            "Tablet Anki + Phone MyWhoosh",
            "AnkiDroid full-screen on Tablet; MyWhoosh on phone. MyWhoosh connects over local Wi-Fi LAN via mDNS."
        ),
        Triple(
            OperatingMode.MODE_3_ANKI_ONLY,
            "Anki-Only (Study)",
            "Pure study mode. All buttons mapped to AnkiDroid card review and undo."
        ),
        Triple(
            OperatingMode.MODE_4_MYWHOOSH_ONLY,
            "MyWhoosh-Only (Ride)",
            "Pure ride mode. All buttons mapped to MyWhoosh shifting, steering, UI toggle, and emotes."
        ),
        Triple(
            OperatingMode.MODE_5_UBUNTU_ANKICONNECT,
            "Ubuntu Anki + Ride",
            "Desktop Anki on Ubuntu Laptop (AnkiConnect :8765) + MyWhoosh on Tablet/Phone."
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        modes.forEach { (mode, title, description) ->
            val isSelected = mode == selectedMode
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModeSelected(mode) },
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(if (isSelected) 4.dp else 1.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
