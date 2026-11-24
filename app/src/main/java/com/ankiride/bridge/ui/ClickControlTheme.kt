package com.ankiride.bridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ankiride.bridge.core.*
import com.ankiride.bridge.service.AndroidGenericInputDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val ClickControlColors = darkColorScheme(
    primary = Color(0xFF70D8C8), onPrimary = Color(0xFF062B28),
    primaryContainer = Color(0xFF24564F), onPrimaryContainer = Color(0xFFCEFFF5),
    background = Color(0xFF101B22), onBackground = Color(0xFFECF5F4),
    surface = Color(0xFF182A33), onSurface = Color(0xFFECF5F4), surfaceTint = Color(0xFF70D8C8),
    surfaceContainerLowest = Color(0xFF0C171D), surfaceContainerLow = Color(0xFF14242B),
    surfaceContainer = Color(0xFF182A33), surfaceContainerHigh = Color(0xFF213943), surfaceContainerHighest = Color(0xFF2B454D),
    inverseSurface = Color(0xFFD7E8E5), inverseOnSurface = Color(0xFF203331), inversePrimary = Color(0xFF216D62),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    surfaceVariant = Color(0xFF213943), onSurfaceVariant = Color(0xFFB5C9C8),
    outline = Color(0xFF829B9A), outlineVariant = Color(0xFF2E4954), secondary = Color(0xFFB5C9C8),
    onSecondary = Color(0xFF173230), secondaryContainer = Color(0xFF344C4A), onSecondaryContainer = Color(0xFFD0E6E2)
)

@Composable
fun AndroidSystemSection(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun SettingsActionRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leading != null) leading()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (trailing != null) trailing()
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
fun SettingsRadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    SettingsActionRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = { RadioButton(selected = selected, onClick = onClick) }
    )
}

@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Text(
            text = text,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun HardwareButtonBadge(button: ClickButton, sizeDp: Int = 32) {
    val bgColor = if (button.badgeColorHex == 0xFF0F172A) {
        MaterialTheme.colorScheme.surfaceContainerHighest
    } else {
        Color(button.badgeColorHex)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = button.shortBadge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp * 0.44f).sp
        )
    }
}

@Composable
fun PresetEditor(
    config: AppConfiguration,
    onChange: (AppConfiguration) -> Unit,
    allowAnkiDroid: Boolean = true,
    onDirtyChanged: (Boolean) -> Unit = {}
) {
    val capabilities = remember { PlatformCapabilities.android() }
    val current = PresetStore.active(config) ?: return
    val draftSaver = remember {
        Saver<MappingPreset, String>(
            save = { PresetStore.serialize(listOf(it), it.id) },
            restore = { PresetStore.deserialize(it)?.first?.singleOrNull() }
        )
    }
    var draft by rememberSaveable(current.id, current, stateSaver = draftSaver) { mutableStateOf(current) }
    var selectionOpen by remember { mutableStateOf(false) }
    var templateOpen by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf<PresetBindingKey?>(null) }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val dirty = draft != current
    LaunchedEffect(dirty) { onDirtyChanged(dirty) }
    var managementOpen by remember { mutableStateOf(false) }
    var rightPod by remember { mutableStateOf(true) }
    val testScope = rememberCoroutineScope()
    val testDispatcher = remember { AndroidGenericInputDispatcher }
    var editorRevision by remember { mutableIntStateOf(0) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var selectedButton by remember { mutableStateOf(ClickButton.Y) }
    var buttonMenu by remember { mutableStateOf(false) }

    LaunchedEffect(current.id, selectedButton, draft.bindings, draft.inputConfigs) {
        testJob?.cancel()
        countdown = 0
    }
    DisposableEffect(Unit) { onDispose { testJob?.cancel() } }

    val availableActions = remember(capabilities, draft.ankiTarget) { capabilities.availableActions(draft.ankiTarget) }
    val hasUnsupportedImportedKey = draft.bindings.values.any { it == MappedTargetAction.KEYBOARD_INPUT }
    val hasUnsupportedAnkiWebUndo = draft.ankiTarget == AnkiTarget.ANKIWEB && draft.bindings.values.any { it == MappedTargetAction.ANKI_UNDO }
    val canSave = dirty && draft.name.isNotBlank() && !hasUnsupportedAnkiWebUndo && draft.bindings.all { (key, action) ->
        !action.isGenericInput || draft.inputConfigs[key]?.let { capabilities.isValidInputConfig(action, it) } == true
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        AndroidSystemSection(
            title = "Preset Library",
            description = "Pick a preset, create one from a template, or customize button actions."
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box {
                    OutlinedButton(
                        onClick = { selectionOpen = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Active: ${current.name} ▾")
                    }
                    DropdownMenu(expanded = selectionOpen, onDismissRequest = { selectionOpen = false }) {
                        config.presets.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.name) },
                                onClick = {
                                    selectionOpen = false
                                    if (dirty) pendingSelection = preset.id else onChange(config.copy(activePresetId = preset.id))
                                }
                            )
                        }
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { templateOpen = true },
                        enabled = !dirty,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("New from template ▾")
                    }
                    DropdownMenu(expanded = templateOpen, onDismissRequest = { templateOpen = false }) {
                        PresetTemplate.entries.forEach { template ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(template.displayName(capabilities), fontWeight = FontWeight.SemiBold)
                                        Text(template.description(capabilities), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    val created = PresetStore.createFromTemplate(template, capabilities)
                                    onChange(config.copy(presets = config.presets + created, activePresetId = created.id))
                                    templateOpen = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    singleLine = true,
                    label = { Text("Preset name") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Anki destination", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        AnkiTarget.ANKIDROID to "AnkiDroid",
                        AnkiTarget.ANKIWEB to "AnkiWeb",
                        AnkiTarget.ANKICONNECT to "Desktop LAN"
                    ).forEach { (target, label) ->
                        FilterChip(
                            selected = draft.ankiTarget == target,
                            onClick = { draft = draft.copy(ankiTarget = target) },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }
                if (draft.ankiTarget == AnkiTarget.ANKIWEB) {
                    Text(
                        "Works in Chrome, Firefox, Brave, Edge, and Samsung Internet on ankiweb.net with Accessibility enabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = if (dirty) "Unsaved changes" else "Saved",
                        dotColor = if (dirty) Color(0xFFF6D32D) else MaterialTheme.colorScheme.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (draft.name.isNotBlank()) {
                                onChange(config.copy(presets = config.presets.map {
                                    if (it.id == current.id) draft.copy(name = draft.name.trim()) else it
                                }))
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Save")
                    }
                    Box {
                        OutlinedButton(
                            onClick = { managementOpen = true },
                            modifier = Modifier.heightIn(min = 48.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Manage ▾")
                        }
                        DropdownMenu(expanded = managementOpen, onDismissRequest = { managementOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                onClick = {
                                    val duplicate = draft.copy(id = PresetStore.newId(), name = "${draft.name} copy")
                                    onChange(config.copy(presets = config.presets + duplicate, activePresetId = duplicate.id))
                                    managementOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Reset to standard Ride + Anki") },
                                onClick = {
                                    draft = draft.copy(ankiTarget = AnkiTarget.ANKIDROID, bindings = PresetStore.standardBindings(), inputConfigs = emptyMap())
                                    managementOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear all actions") },
                                onClick = {
                                    draft = draft.copy(bindings = current.bindings.mapValues { MappedTargetAction.NONE }, inputConfigs = emptyMap())
                                    managementOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Discard changes") },
                                enabled = dirty,
                                onClick = {
                                    draft = current
                                    editorRevision++
                                    managementOpen = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete preset") },
                                enabled = config.presets.size > 1,
                                onClick = {
                                    confirmDelete = true
                                    managementOpen = false
                                }
                            )
                        }
                    }
                }
            }
        }

        if (hasUnsupportedImportedKey) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "This preset contains keyboard shortcut bindings imported from another setup. Keyboard shortcuts are unavailable on Android — replace them with Touch swipe or Screen tap.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (hasUnsupportedAnkiWebUndo) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "AnkiWeb does not support Undo. Replace Undo bindings with Flip card or a grade before saving.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        AndroidSystemSection(
            title = "Button Actions",
            description = "Choose a controller side and button to set Press and Hold actions."
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "Right controller", false to "Left controller").forEach { (right, label) ->
                        FilterChip(
                            selected = rightPod == right,
                            onClick = {
                                rightPod = right
                                selectedButton = if (right) ClickButton.Y else ClickButton.SHIFT_DOWN_LEFT
                            },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp)
                        )
                    }
                }

                val buttons = ClickButton.entries.map { it.canonicalButton }.distinct().filter {
                    it.isRightPod == rightPod && (it.isPrimaryConfigurable || it == ClickButton.SHIFT_UP_LEFT || it == ClickButton.SHIFT_DOWN_RIGHT)
                }

                Box {
                    OutlinedButton(
                        onClick = { buttonMenu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HardwareButtonBadge(selectedButton, sizeDp = 26)
                            Text("${selectedButton.displayName} ▾", modifier = Modifier.weight(1f))
                        }
                    }
                    DropdownMenu(expanded = buttonMenu, onDismissRequest = { buttonMenu = false }) {
                        buttons.forEach { button ->
                            DropdownMenuItem(
                                text = { Text(button.displayName) },
                                onClick = { selectedButton = button; buttonMenu = false }
                            )
                        }
                    }
                }

                val singleKey = PresetBindingKey(selectedButton, PressType.SINGLE_CLICK)
                val singleAction = draft.bindings[singleKey] ?: MappedTargetAction.NONE
                val singleRepeats = singleAction.isGenericInput && draft.inputConfigs[singleKey]?.repeatWhileHeld == true

                PressType.entries.forEach { press ->
                    val key = PresetBindingKey(selectedButton, press)
                    val action = draft.bindings[key] ?: MappedTargetAction.NONE

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = if (press == PressType.SINGLE_CLICK) "Single Press" else "Long Press (450ms)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (press == PressType.LONG_PRESS && singleRepeats) {
                                Text(
                                    text = "Paused while Single Press repeats on hold",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF6D32D)
                                )
                            }

                            Box {
                                OutlinedButton(
                                    onClick = { actionMenu = key },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(PresetStore.bindingLabel(draft, key, PlatformKind.ANDROID))
                                }
                                DropdownMenu(
                                    expanded = actionMenu == key,
                                    onDismissRequest = { actionMenu = null },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    availableActions.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(candidate.displayLabel(PlatformKind.ANDROID)) },
                                            onClick = {
                                                val defaults = when (candidate) {
                                                    MappedTargetAction.SCREEN_CLICK -> InputConfig(left = .4f, top = .4f, width = .2f, height = .2f)
                                                    MappedTargetAction.SCREEN_SCROLL -> InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 4, left = .2f, top = .2f, width = .6f, height = .6f)
                                                    else -> null
                                                }
                                                draft = draft.copy(
                                                    bindings = draft.bindings + (key to candidate),
                                                    inputConfigs = if (defaults == null) draft.inputConfigs - key else draft.inputConfigs + (key to defaults)
                                                )
                                                actionMenu = null
                                            }
                                        )
                                    }
                                }
                            }

                            if (action == MappedTargetAction.SCREEN_CLICK || action == MappedTargetAction.SCREEN_SCROLL) {
                                val payload = draft.inputConfigs[key] ?: InputConfig()
                                fun update(c: InputConfig) {
                                    draft = draft.copy(inputConfigs = draft.inputConfigs + (key to c))
                                }

                                if (action == MappedTargetAction.SCREEN_CLICK) {
                                    Text(
                                        "Screen tap area (% of display). Taps the center of the area.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    AreaPercentFields(current.id, current, key, action, editorRevision, payload, ::update)
                                } else {
                                    Text(
                                        "Touch swipe inside the chosen area. Next/Prev swipes sideways for slides; Scroll swipes up/down for documents.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    val directionRows = listOf(
                                        listOf(ScrollDirection.RIGHT to "Next →", ScrollDirection.LEFT to "Prev ←"),
                                        listOf(ScrollDirection.DOWN to "Scroll ↓", ScrollDirection.UP to "Scroll ↑")
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        directionRows.forEach { rowDirs ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                rowDirs.forEach { (dir, label) ->
                                                    FilterChip(
                                                        selected = payload.scrollDirection == dir,
                                                        onClick = { update(payload.copy(scrollDirection = dir, clickAtPointer = false)) },
                                                        label = { Text(label, maxLines = 1) },
                                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = { if (payload.scrollAmount > 1) update(payload.copy(scrollAmount = payload.scrollAmount - 1)) },
                                            enabled = payload.scrollAmount > 1,
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) { Text("-") }
                                        Text("Swipe distance: ${payload.scrollAmount}")
                                        OutlinedButton(
                                            onClick = { if (payload.scrollAmount < 20) update(payload.copy(scrollAmount = payload.scrollAmount + 1)) },
                                            enabled = payload.scrollAmount < 20,
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) { Text("+") }
                                    }
                                    AreaPercentFields(current.id, current, key, action, editorRevision, payload, ::update)
                                }

                                if (press == PressType.SINGLE_CLICK) {
                                    FilterChip(
                                        selected = payload.repeatWhileHeld,
                                        onClick = { update(payload.copy(repeatWhileHeld = !payload.repeatWhileHeld)) },
                                        label = { Text("Repeat while held") },
                                        modifier = Modifier.heightIn(min = 48.dp)
                                    )
                                }

                                OutlinedButton(
                                    onClick = {
                                        if (countdown > 0) {
                                            testJob?.cancel()
                                            countdown = 0
                                        } else {
                                            testJob = testScope.launch {
                                                try {
                                                    for (i in 3 downTo 1) {
                                                        countdown = i
                                                        delay(1000)
                                                    }
                                                    testDispatcher.dispatch(action, payload)
                                                } finally {
                                                    countdown = 0
                                                }
                                            }
                                        }
                                    },
                                    enabled = capabilities.isValidInputConfig(action, payload),
                                    modifier = Modifier.heightIn(min = 48.dp)
                                ) {
                                    Text(if (countdown > 0) "Cancel test ($countdown)" else "Test in 3 seconds")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingSelection != null) {
        AlertDialog(
            onDismissRequest = { pendingSelection = null },
            title = { Text("Discard unsaved changes?") },
            text = { Text("Your changes to ${current.name} have not been saved.") },
            confirmButton = { TextButton(onClick = { onChange(config.copy(activePresetId = pendingSelection)); pendingSelection = null }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { pendingSelection = null }) { Text("Keep editing") } }
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${current.name}?") },
            text = { Text("This removes the saved preset.") },
            confirmButton = {
                TextButton(onClick = {
                    val remaining = config.presets.filterNot { it.id == current.id }
                    onChange(config.copy(presets = remaining, activePresetId = remaining.first().id))
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AreaPercentFields(
    presetId: String,
    current: MappingPreset,
    key: PresetBindingKey,
    action: MappedTargetAction,
    editorRevision: Int,
    payload: InputConfig,
    onUpdate: (InputConfig) -> Unit
) {
    val capabilities = remember { PlatformCapabilities.android() }
    val normalizedPayload = if (payload.clickAtPointer) payload.copy(clickAtPointer = false) else payload
    if (payload.clickAtPointer) {
        LaunchedEffect(key) { onUpdate(normalizedPayload) }
    }
    listOf("Left" to normalizedPayload.left, "Top" to normalizedPayload.top, "Width" to normalizedPayload.width, "Height" to normalizedPayload.height).forEachIndexed { index, (label, value) ->
        var text by rememberSaveable(presetId, current, key, action, index, editorRevision) {
            mutableStateOf(
                if (value.isFinite()) {
                    val rounded = kotlin.math.round(value * 1000f) / 10f
                    if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
                } else ""
            )
        }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw
                val number = raw.toFloatOrNull()?.div(100f) ?: Float.NaN
                onUpdate(
                    when (index) {
                        0 -> normalizedPayload.copy(left = number)
                        1 -> normalizedPayload.copy(top = number)
                        2 -> normalizedPayload.copy(width = number)
                        else -> normalizedPayload.copy(height = number)
                    }
                )
            },
            label = { Text("$label (%)") },
            singleLine = true,
            isError = !capabilities.isValidInputConfig(action, normalizedPayload),
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (!capabilities.isValidInputConfig(action, normalizedPayload)) {
        Text("Enter a positive screen area within 0–100%.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MappingOverview(config: AppConfiguration) {
    val preset = PresetStore.active(config) ?: return
    val targetLabel = when (preset.ankiTarget) {
        AnkiTarget.ANKIDROID -> "AnkiDroid"
        AnkiTarget.ANKIWEB -> "AnkiWeb (Browser)"
        AnkiTarget.ANKICONNECT -> "Desktop Anki (LAN)"
    }
    val buttons = remember {
        ClickButton.entries.map { it.canonicalButton }.distinct()
            .filter { it.isPrimaryConfigurable || it == ClickButton.SHIFT_UP_LEFT || it == ClickButton.SHIFT_DOWN_RIGHT }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Anki destination: $targetLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AndroidSystemSection(title = "Button Mappings") {
            buttons.forEachIndexed { idx, button ->
                val press = PresetStore.bindingLabel(preset, PresetBindingKey(button, PressType.SINGLE_CLICK), PlatformKind.ANDROID)
                val hold = PresetStore.bindingLabel(preset, PresetBindingKey(button, PressType.LONG_PRESS), PlatformKind.ANDROID)
                SettingsActionRow(
                    title = button.displayName,
                    subtitle = "Press: $press\nHold: $hold",
                    leading = { HardwareButtonBadge(button) }
                )
                if (idx < buttons.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
