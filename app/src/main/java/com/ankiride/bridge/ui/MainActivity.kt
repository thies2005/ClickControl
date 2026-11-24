package com.ankiride.bridge.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ankiride.bridge.anki.AnkiAccessibilityBridge
import com.ankiride.bridge.ble.ZwiftBleController
import com.ankiride.bridge.core.*
import com.ankiride.bridge.mywhoosh.MyShiftOverlayCalibrator
import com.ankiride.bridge.mywhoosh.OpenBikeProtocol
import com.ankiride.bridge.service.BridgeAccessibilityService
import com.ankiride.bridge.service.BridgeForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

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
        DiagnosticLogger.configureLogFile(File(filesDir, "logs/clickcontrol.log"))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnkiRideDashboard(
    permissionRefreshCount: Int,
    onRequestPermissions: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBatteryOpt: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val capabilities = remember { PlatformCapabilities.android() }
    val isRunning by BridgeForegroundService.isRunningFlow.collectAsState()
    val bleState by BridgeForegroundService.connectionStateFlow.collectAsState()
    val a11yConnected by BridgeAccessibilityService.isConnectedFlow.collectAsState()
    val rightMac by ZwiftBleController.lastRightMac.collectAsState()
    val leftMac by ZwiftBleController.lastLeftMac.collectAsState()
    val openBikeClients by BridgeForegroundService.openBikeConnectedClientsFlow.collectAsState()
    val openBikeServerRunning by BridgeForegroundService.openBikeServerRunningFlow.collectAsState()
    val openBikeError by BridgeForegroundService.openBikeServerErrorFlow.collectAsState()
    val lastIssue by DiagnosticLogger.lastIssue.collectAsState()

    var config by remember {
        mutableStateOf(PresetStore.applyStartupDefaultPreset(ButtonMappingStore.loadFullConfiguration(context)))
    }
    var page by rememberSaveable { mutableStateOf(0) }
    var presetDirty by rememberSaveable { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }

    fun navigate(next: Int) {
        if (page == 1 && next != 1 && presetDirty) pendingPage = next else page = next
    }
    BackHandler(enabled = page != 0) { navigate(0) }

    var advanced by remember { mutableStateOf(false) }
    var showDesktopAnki by remember { mutableStateOf(false) }
    var portText by remember(config.ubuntuAnkiConnectPort) { mutableStateOf(config.ubuntuAnkiConnectPort.toString()) }
    var startupPresetMenuOpen by remember { mutableStateOf(false) }
    var showMappings by remember { mutableStateOf(false) }
    var presetSwitcherOpen by remember { mutableStateOf(false) }
    val discovered by ZwiftBleController.discoveredAddresses.collectAsState()

    val activePreset = PresetStore.active(config)
    val activeTarget = activePreset?.ankiTarget?.let { capabilities.normalizeAnkiTarget(it) } ?: AnkiTarget.ANKIDROID
    val hasAnkiBinding = PresetStore.activePresetUsesAnki(config)
    val hasGenericBinding = activePreset?.bindings?.values?.any { it.isGenericInput } == true
    val hasMyWhooshBinding = PresetStore.activePresetUsesMyWhoosh(config)
    val accessibilityRequired = hasGenericBinding ||
        (hasAnkiBinding && (activeTarget == AnkiTarget.ANKIDROID || activeTarget == AnkiTarget.ANKIWEB)) ||
        (hasMyWhooshBinding && config.myWhooshLocation == MyWhooshLocation.ON_THIS_DEVICE && config.useMyShiftTouchFallbackWhenNoClient)

    var a11yEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var batteryExempt by remember { mutableStateOf(isBatteryOptimizationExempt(context)) }
    var bleGranted by remember { mutableStateOf(areBlePermissionsGranted(context)) }
    var didAutoStart by rememberSaveable { mutableStateOf(false) }

    fun refresh() {
        a11yEnabled = isAccessibilityServiceEnabled(context)
        batteryExempt = isBatteryOptimizationExempt(context)
        bleGranted = areBlePermissionsGranted(context)
    }

    LaunchedEffect(a11yConnected, permissionRefreshCount) { refresh() }
    LaunchedEffect(bleGranted) {
        if (!didAutoStart && bleGranted && config.autoStartOnControllerWake && !BridgeForegroundService.isRunningFlow.value) {
            didAutoStart = true
            BridgeForegroundService.startService(context, config)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(config) {
        ButtonMappingStore.saveFullConfiguration(context, config)
        if (BridgeForegroundService.isRunningFlow.value) BridgeForegroundService.startService(context, config)
    }
    LaunchedEffect(bleState, config.activePresetId, config.floatingHudPillEnabled) {
        val preset = PresetStore.active(config)
        FloatingHudPill.syncVisibility(
            service = BridgeAccessibilityService.activeInstance,
            enabled = config.floatingHudPillEnabled,
            bleState = bleState,
            presetName = preset?.name ?: "Default",
            ankiTarget = preset?.ankiTarget ?: AnkiTarget.ANKIDROID
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("ClickControl", fontWeight = FontWeight.Bold)
                        Text(
                            text = when (page) {
                                0 -> "Controller Bridge"
                                1 -> "Presets & Templates"
                                else -> "System Settings"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    Box(modifier = Modifier.padding(end = 12.dp)) {
                        StatusPill(
                            text = if (isRunning) "Active" else "Stopped",
                            dotColor = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                listOf("Control", "Presets", "Settings").forEachIndexed { index, label ->
                    NavigationBarItem(
                        selected = page == index,
                        onClick = { navigate(index) },
                        icon = { Icon(listOf(Icons.Default.Home, Icons.Default.Tune, Icons.Default.Settings)[index], contentDescription = null) },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (page) {
                0 -> {
                    val activeAlert = openBikeError ?: lastIssue?.takeIf {
                        it.level == LogLevel.ERROR || it.level == LogLevel.WARN
                    }?.let { "${it.category.name}: ${it.message}" }

                    if (!activeAlert.isNullOrBlank()) {
                        val isError = openBikeError != null || lastIssue?.level == LogLevel.ERROR
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = activeAlert,
                                    color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            advanced = true
                                            navigate(2)
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Logs") }
                                    TextButton(
                                        onClick = { DiagnosticLogger.dismissLastIssue() },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Dismiss") }
                                }
                            }
                        }
                    }

                    ConnectionStatusCard(
                        bleState = bleState,
                        isRunning = isRunning,
                        lastRightMac = rightMac,
                        lastLeftMac = leftMac,
                        showBikeNote = hasMyWhooshBinding || openBikeServerRunning,
                        openBikeConnected = openBikeClients > 0,
                        openBikePort = config.openBikeServerPort,
                        onToggleService = {
                            if (isRunning) BridgeForegroundService.stopService(context)
                            else if (!bleGranted) onRequestPermissions()
                            else BridgeForegroundService.startService(context, config)
                        }
                    )

                    AndroidSystemSection(
                        title = "Quick Setup",
                        description = "One-tap profiles for your Pixel + Ubuntu setup (no external app needed)."
                    ) {
                        val isOptionCActive = config.podAssignment == PodAssignment.SINGLE_CONTROLLER &&
                            hasMyWhooshBinding && !hasAnkiBinding &&
                            config.myWhooshLocation == MyWhooshLocation.ON_THIS_DEVICE
                        SettingsActionRow(
                            title = "Option C · Pixel Bike Shifter (MyWhoosh here)",
                            subtitle = "Pairs one controller to your Pixel for shifting/steering via ${OpenBikeProtocol.SERVICE_NAME_ANDROID} (no Accessibility needed)",
                            trailing = {
                                if (isOptionCActive) {
                                    StatusPill("Active", MaterialTheme.colorScheme.primary)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val bikePreset = config.presets.firstOrNull {
                                                it.bindings.values.any { act -> act.myWhooshAction != null } &&
                                                    it.bindings.values.none { act -> act.ankiAction != null }
                                            } ?: PresetStore.createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, capabilities)
                                            val nextPresets = if (config.presets.any { it.id == bikePreset.id }) config.presets else config.presets + bikePreset
                                            val nextCfg = config.copy(
                                                presets = nextPresets,
                                                activePresetId = bikePreset.id,
                                                podAssignment = PodAssignment.SINGLE_CONTROLLER,
                                                myWhooshLocation = MyWhooshLocation.ON_THIS_DEVICE,
                                                useMyShiftTouchFallbackWhenNoClient = false
                                            )
                                            config = nextCfg
                                            if (bleGranted && !isRunning) BridgeForegroundService.startService(context, nextCfg)
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Enable") }
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            title = "Option B · Relay Shifts over Wi-Fi (Another Device)",
                            subtitle = "Pairs one controller to your phone and relays MyWhoosh shifts or Desktop Anki over Wi-Fi",
                            trailing = {
                                val isRelayActive = config.podAssignment == PodAssignment.SINGLE_CONTROLLER &&
                                    hasMyWhooshBinding &&
                                    config.myWhooshLocation == MyWhooshLocation.ON_ANOTHER_DEVICE
                                if (isRelayActive) {
                                    StatusPill("Active", MaterialTheme.colorScheme.primary)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val relayPreset = config.presets.firstOrNull {
                                                it.bindings.values.any { act -> act.myWhooshAction != null }
                                            } ?: PresetStore.createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, capabilities)
                                            val nextPresets = if (config.presets.any { it.id == relayPreset.id }) config.presets else config.presets + relayPreset
                                            val nextCfg = config.copy(
                                                presets = nextPresets,
                                                activePresetId = relayPreset.id,
                                                podAssignment = PodAssignment.SINGLE_CONTROLLER,
                                                myWhooshLocation = MyWhooshLocation.ON_ANOTHER_DEVICE,
                                                useMyShiftTouchFallbackWhenNoClient = false
                                            )
                                            config = nextCfg
                                            if (bleGranted && !isRunning) BridgeForegroundService.startService(context, nextCfg)
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Enable") }
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsActionRow(
                            title = "Anki Study (Single Controller)",
                            subtitle = "Stops phone OpenBikeControl server and maps your controller to AnkiDroid",
                            trailing = {
                                val isAnkiOnly = config.podAssignment == PodAssignment.SINGLE_CONTROLLER && hasAnkiBinding && !hasMyWhooshBinding
                                if (isAnkiOnly) {
                                    StatusPill("Active", MaterialTheme.colorScheme.primary)
                                } else {
                                    OutlinedButton(
                                        onClick = {
                                            val ankiPreset = config.presets.firstOrNull {
                                                it.bindings.values.any { act -> act.ankiAction != null } &&
                                                    it.bindings.values.none { act -> act.myWhooshAction != null }
                                            } ?: PresetStore.createFromTemplate(PresetTemplate.ANKI_LOCAL, capabilities)
                                            val nextPresets = if (config.presets.any { it.id == ankiPreset.id }) config.presets else config.presets + ankiPreset
                                            val nextCfg = config.copy(
                                                presets = nextPresets,
                                                activePresetId = ankiPreset.id,
                                                podAssignment = PodAssignment.SINGLE_CONTROLLER
                                            )
                                            config = nextCfg
                                            if (bleGranted && !isRunning) BridgeForegroundService.startService(context, nextCfg)
                                        },
                                        modifier = Modifier.heightIn(min = 44.dp)
                                    ) { Text("Enable") }
                                }
                            }
                        )
                    }

                    AndroidSystemSection(title = "Active Preset") {
                        val destText = when (activeTarget) {
                            AnkiTarget.ANKIDROID -> "AnkiDroid"
                            AnkiTarget.ANKIWEB -> "AnkiWeb (Browser)"
                            AnkiTarget.ANKICONNECT -> "Desktop Anki (LAN)"
                        }
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = PresetStore.activePresetName(config),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (hasAnkiBinding) {
                                Text(
                                    text = "Anki target: $destText",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box {
                                    OutlinedButton(
                                        onClick = { presetSwitcherOpen = true },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    ) {
                                        Text("Switch preset ▾")
                                    }
                                    DropdownMenu(
                                        expanded = presetSwitcherOpen,
                                        onDismissRequest = { presetSwitcherOpen = false }
                                    ) {
                                        config.presets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = { Text(preset.name) },
                                                onClick = {
                                                    config = config.copy(activePresetId = preset.id)
                                                    presetSwitcherOpen = false
                                                }
                                            )
                                        }
                                    }
                                }
                                OutlinedButton(
                                    onClick = { navigate(1) },
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text("Edit preset")
                                }
                            }
                        }
                    }

                    if (!bleGranted || !batteryExempt || (accessibilityRequired && !a11yEnabled)) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Setup needs attention", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Grant the permissions required by your active preset before using the controller.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(onClick = { navigate(2) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text("Review setup")
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showMappings = !showMappings },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (showMappings) "Hide button mappings" else "View button mappings")
                    }
                    if (showMappings) MappingOverview(config)
                }

                1 -> PresetEditor(
                    config = config,
                    onChange = { config = it },
                    allowAnkiDroid = true,
                    onDirtyChanged = { presetDirty = it }
                )

                else -> {
                    PermissionsChecklistCard(
                        a11yEnabled = a11yEnabled,
                        batteryExempt = batteryExempt,
                        bleGranted = bleGranted,
                        onRequestPermissions = onRequestPermissions,
                        onOpenAccessibility = onOpenAccessibility,
                        onOpenBatteryOpt = onOpenBatteryOpt,
                        a11yRequired = accessibilityRequired
                    )

                    AndroidSystemSection(
                        title = "Controller Connection",
                        description = "Choose which Zwift Click controllers this phone connects to."
                    ) {
                        val choices = listOf(
                            PodAssignment.SINGLE_CONTROLLER to ("Single controller (Option C)" to "Connects one controller only, leaving a second controller free for your laptop."),
                            PodAssignment.RIGHT_CONTROLLER_ONLY to ("Right controller only" to "Connects only the right pod and ignores left/4-arrow pods."),
                            PodAssignment.BOTH_CONTROLLERS to ("Both controllers" to "Connects both left and right pods to this Android device.")
                        )
                        choices.forEachIndexed { idx, (assignment, labels) ->
                            val selected = config.podAssignment == assignment
                            SettingsRadioRow(
                                title = labels.first,
                                subtitle = labels.second,
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        config = config.copy(podAssignment = assignment)
                                    }
                                }
                            )
                            if (idx < choices.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }

                    AndroidSystemSection(title = "Startup & Feedback") {
                        val startupName = config.defaultPresetId
                            ?.let { id -> config.presets.firstOrNull { it.id == id }?.name }
                            ?: "Last used preset"
                        SettingsActionRow(
                            title = "Default preset on launch",
                            subtitle = startupName,
                            trailing = {
                                Box {
                                    OutlinedButton(onClick = { startupPresetMenuOpen = true }) {
                                        Text("Choose ▾")
                                    }
                                    DropdownMenu(expanded = startupPresetMenuOpen, onDismissRequest = { startupPresetMenuOpen = false }) {
                                        DropdownMenuItem(
                                            text = { Text("Last used preset") },
                                            onClick = {
                                                config = config.copy(defaultPresetId = null)
                                                startupPresetMenuOpen = false
                                            }
                                        )
                                        config.presets.forEach { preset ->
                                            DropdownMenuItem(
                                                text = { Text(preset.name) },
                                                onClick = {
                                                    config = config.copy(defaultPresetId = preset.id)
                                                    startupPresetMenuOpen = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsSwitchRow(
                            title = "Auto-start bridge on app open",
                            subtitle = "Automatically start scanning and OpenBikeControl when ClickControl opens",
                            checked = config.autoStartOnControllerWake,
                            onCheckedChange = { config = config.copy(autoStartOnControllerWake = it) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsSwitchRow(
                            title = "Floating status pill",
                            subtitle = "Tap the pill anytime to switch between your saved presets",
                            checked = config.floatingHudPillEnabled,
                            onCheckedChange = { config = config.copy(floatingHudPillEnabled = it) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsSwitchRow(
                            title = "Haptic feedback",
                            subtitle = "Vibrate on controller button actions",
                            checked = config.hapticFeedbackEnabled,
                            onCheckedChange = { config = config.copy(hapticFeedbackEnabled = it) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SettingsSwitchRow(
                            title = "Audio feedback",
                            subtitle = "Play soft confirmation tones on controller presses",
                            checked = config.audioFeedbackEnabled,
                            onCheckedChange = { config = config.copy(audioFeedbackEnabled = it) }
                        )
                    }

                    AndroidSystemSection(
                        title = "MyWhoosh Cycling",
                        description = "Only needed if a preset uses MyWhoosh bike controls."
                    ) {
                        MyWhooshLocation.entries.forEachIndexed { idx, loc ->
                            val selected = config.myWhooshLocation == loc
                            SettingsRadioRow(
                                title = loc.displayName,
                                subtitle = if (loc == MyWhooshLocation.ON_THIS_DEVICE) {
                                    "Enables on-device touch shifting fallback & target calibration"
                                } else {
                                    "Uses OpenBikeControl over Wi-Fi (_openbikecontrol._tcp)"
                                },
                                selected = selected,
                                onClick = { config = config.copy(myWhooshLocation = loc) }
                            )
                            if (idx == 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        if (config.myWhooshLocation == MyWhooshLocation.ON_THIS_DEVICE) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            SettingsSwitchRow(
                                title = "Touch shift fallback",
                                subtitle = "Tap calibrated gear shift buttons when OpenBikeControl is disconnected",
                                checked = config.useMyShiftTouchFallbackWhenNoClient,
                                onCheckedChange = { config = config.copy(useMyShiftTouchFallbackWhenNoClient = it) }
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Column(Modifier.padding(16.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        BridgeAccessibilityService.activeInstance?.let {
                                            MyShiftOverlayCalibrator.showCalibrationOverlay(it)
                                        }
                                    },
                                    enabled = a11yEnabled && config.useMyShiftTouchFallbackWhenNoClient,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                ) {
                                    Text("Calibrate touch shift targets")
                                }
                            }
                        }
                    }

                    val desktopAnkiExpanded = showDesktopAnki || activeTarget == AnkiTarget.ANKICONNECT
                    OutlinedButton(
                        onClick = { showDesktopAnki = !desktopAnkiExpanded },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (desktopAnkiExpanded) "Hide Desktop Anki (LAN) settings" else "Desktop Anki (LAN) settings")
                    }
                    if (desktopAnkiExpanded) {
                        AndroidSystemSection(
                            title = "Desktop Anki (LAN AnkiConnect)",
                            description = "Used only by presets targeting Desktop Anki over Wi-Fi."
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = config.ubuntuAnkiConnectHost,
                                    onValueChange = { config = config.copy(ubuntuAnkiConnectHost = it) },
                                    label = { Text("Host IPv4 address") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = portText,
                                    onValueChange = { raw ->
                                        portText = raw.filter(Char::isDigit)
                                        portText.toIntOrNull()?.takeIf { port -> port in 1..65535 }?.let { port ->
                                            config = config.copy(ubuntuAnkiConnectPort = port)
                                        }
                                    },
                                    label = { Text("Port") },
                                    isError = portText.toIntOrNull() !in 1..65535,
                                    supportingText = { if (portText.toIntOrNull() !in 1..65535) Text("Enter a port from 1 to 65535") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { advanced = !advanced },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text(if (advanced) "Hide advanced & diagnostics" else "Advanced & diagnostics")
                    }
                    if (advanced) {
                        AndroidSystemSection(
                            title = "Hardware Addresses",
                            description = "Optional: pin a verified Bluetooth MAC address to Right or Left."
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "Discovered: ${discovered.joinToString().ifBlank { "none yet" }}",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (discovered.isNotEmpty()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        discovered.forEach { addr ->
                                            AssistChip(
                                                onClick = { config = config.copy(pinnedRightPodMac = addr) },
                                                label = { Text(addr, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                                OutlinedTextField(
                                    value = config.pinnedRightPodMac.orEmpty(),
                                    onValueChange = { config = config.copy(pinnedRightPodMac = it.ifBlank { null }) },
                                    label = { Text("Right controller address") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = config.pinnedLeftPodMac.orEmpty(),
                                    onValueChange = { config = config.copy(pinnedLeftPodMac = it.ifBlank { null }) },
                                    label = { Text("Left controller address") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        DiagnosticLogCard()
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
            modifier = Modifier.fillMaxWidth().clickable { expandedWhenReady = true },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
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
                        text = "System permissions ready",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Show",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        return
    }

    AndroidSystemSection(title = "System Permissions") {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (allReady) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "Hide",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { expandedWhenReady = false }
                    )
                }
            }

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
                        text = when {
                            a11yEnabled -> "Accessibility Service (Active)"
                            a11yRequired -> "Accessibility Service (Required)"
                            else -> "Accessibility (Not needed for MyWhoosh OpenBikeControl)"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = onOpenAccessibility, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (a11yEnabled) "Settings" else "Enable")
                }
            }

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
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = onOpenBatteryOpt, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(if (batteryExempt) "Exempt ✓" else "Allow")
                }
            }

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
                        text = if (bleGranted) "Bluetooth LE (Granted)" else "Bluetooth LE (Missing)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.heightIn(min = 48.dp)) {
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
    showBikeNote: Boolean = false,
    openBikeConnected: Boolean = false,
    openBikePort: Int = OpenBikeProtocol.DEFAULT_PORT,
    onToggleService: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = if (isRunning) "Bridge Active" else "Bridge Stopped",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            isRunning && openBikeConnected -> "Connected to MyWhoosh via ${OpenBikeProtocol.SERVICE_NAME_ANDROID} (:$openBikePort)"
                            isRunning && showBikeNote -> "Advertising ${OpenBikeProtocol.SERVICE_NAME_ANDROID} on :$openBikePort — pair in MyWhoosh"
                            isRunning -> "Listening for controller presses"
                            else -> "Tap Start Bridge to connect your controller"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = onToggleService,
                    modifier = Modifier.heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        contentColor = if (isRunning) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(if (isRunning) "Stop Bridge" else "Start Bridge")
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (bleState) {
                    BleConnectionState.DISCONNECTED -> Text(
                        "Controller: Disconnected",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BleConnectionState.SCANNING -> Text(
                        "Controller: Scanning for Zwift Click…",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BleConnectionState.CONNECTING, BleConnectionState.HANDSHAKING -> Text(
                        "Controller: Connecting & completing handshake…",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isRunning) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BleConnectionState.CONNECTED_RIGHT_ONLY -> {
                        PodBadge("Right ${lastRightMac?.takeLast(5) ?: ""}".trim(), connected = true)
                        PodBadge("Left", connected = false)
                    }
                    BleConnectionState.CONNECTED_LEFT_ONLY -> {
                        PodBadge("Right", connected = false)
                        PodBadge("Left ${lastLeftMac?.takeLast(5) ?: ""}".trim(), connected = true)
                    }
                    BleConnectionState.CONNECTED_BOTH_PODS -> {
                        PodBadge("Right ${lastRightMac?.takeLast(5) ?: ""}".trim(), connected = true)
                        PodBadge("Left ${lastLeftMac?.takeLast(5) ?: ""}".trim(), connected = true)
                    }
                    BleConnectionState.ERROR -> Text(
                        "Controller error — check Diagnostic Log in Settings",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun PodBadge(label: String, connected: Boolean) {
    val container = if (connected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val text = if (connected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .background(color = container, shape = RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = "$label: ${if (connected) "Connected" else "Off"}",
            color = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
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

    val logPath = remember { DiagnosticLogger.logFilePath() }
    AndroidSystemSection(
        title = "Diagnostic Log (${filteredLogs.size})",
        description = if (logPath != null) "Saved to $logPath" else "Live Bluetooth packets, routing events, and Anki / MyWhoosh dispatches."
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_DOWN) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Test Shift Down", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = { BridgeForegroundService.triggerTestShift(MyWhooshAction.SHIFT_UP) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Test Shift Up", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val text = DiagnosticLogger.formatForClipboard(selectedCategory)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("ClickControl Diagnostic Log", text))
                        Toast.makeText(context, "Copied ${filteredLogs.size} log lines", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Copy", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        DiagnosticLogger.clear()
                        Toast.makeText(context, "Cleared diagnostic log", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Clear", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = {
                        AnkiAccessibilityBridge.dumpInteractiveWindowsToLog(BridgeAccessibilityService.activeInstance)
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("Windows", fontSize = 12.sp)
                }
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
                LogCategory.entries.forEach { category ->
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
                    .height(240.dp)
                    .background(Color(0xFF0C171D), shape = RoundedCornerShape(10.dp))
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (filteredLogs.isEmpty()) {
                    Text(
                        text = "No log entries yet. Start the bridge to see live events.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
