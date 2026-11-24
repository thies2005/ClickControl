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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ankiride.bridge.core.*
import com.ankiride.bridge.service.DesktopGenericInputDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Ubuntu / GNOME Adwaita Dark + Yaru accent palette.
 */
object UbuntuColors {
    val Background = Color(0xFF1E1E1E)
    val SidebarSurface = Color(0xFF242424)
    val CardSurface = Color(0xFF2B2B2B)
    val ElevatedSurface = Color(0xFF333333)
    val HighestSurface = Color(0xFF3D3D3D)
    val TerminalSurface = Color(0xFF141414)
    val Border = Color(0xFF3C3C3C)
    val Outline = Color(0xFF585858)
    val YaruOrange = Color(0xFFE95420)
    val YaruOrangeContainer = Color(0xFF562211)
    val TextPrimary = Color(0xFFF6F5F4)
    val TextSecondary = Color(0xFFA9A7A4)
    val Success = Color(0xFF33D17A)
    val Warning = Color(0xFFF6D32D)
    val Info = Color(0xFF62A0EA)
    val Error = Color(0xFFF66151)
}

val ClickControlColors = darkColorScheme(
    primary = UbuntuColors.YaruOrange,
    onPrimary = Color.White,
    primaryContainer = UbuntuColors.YaruOrangeContainer,
    onPrimaryContainer = Color(0xFFFFDAD0),
    background = UbuntuColors.Background,
    onBackground = UbuntuColors.TextPrimary,
    surface = UbuntuColors.SidebarSurface,
    onSurface = UbuntuColors.TextPrimary,
    surfaceTint = UbuntuColors.YaruOrange,
    surfaceContainerLowest = UbuntuColors.TerminalSurface,
    surfaceContainerLow = UbuntuColors.SidebarSurface,
    surfaceContainer = UbuntuColors.CardSurface,
    surfaceContainerHigh = UbuntuColors.ElevatedSurface,
    surfaceContainerHighest = UbuntuColors.HighestSurface,
    inverseSurface = Color(0xFFF6F5F4),
    inverseOnSurface = Color(0xFF1E1E1E),
    inversePrimary = Color(0xFFC34113),
    error = UbuntuColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFF6E1818),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceVariant = UbuntuColors.CardSurface,
    onSurfaceVariant = UbuntuColors.TextSecondary,
    outline = UbuntuColors.Outline,
    outlineVariant = UbuntuColors.Border,
    secondary = Color(0xFFB8B5B2),
    onSecondary = Color(0xFF1E1E1E),
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = UbuntuColors.TextPrimary
)

@Composable
fun AdwPreferencesGroup(
    title: String? = null,
    description: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!title.isNullOrBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = UbuntuColors.TextPrimary
                )
                if (!description.isNullOrBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = UbuntuColors.TextSecondary
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = UbuntuColors.CardSurface,
            border = BorderStroke(1.dp, UbuntuColors.Border)
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun AdwActionRow(
    title: String,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val rowMod = Modifier
        .fillMaxWidth()
        .heightIn(min = 52.dp)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 16.dp, vertical = 10.dp)

    Row(
        modifier = rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (leading != null) {
            leading()
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = UbuntuColors.TextPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = UbuntuColors.TextSecondary
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun AdwSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    AdwActionRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = UbuntuColors.YaruOrange,
                    uncheckedThumbColor = UbuntuColors.TextSecondary,
                    uncheckedTrackColor = UbuntuColors.HighestSurface,
                    uncheckedBorderColor = UbuntuColors.Outline
                )
            )
        }
    )
}

@Composable
fun AdwRadioRow(
    title: String,
    subtitle: String? = null,
    selected: Boolean,
    onClick: () -> Unit
) {
    AdwActionRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        leading = {
            RadioButton(
                selected = selected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = UbuntuColors.YaruOrange,
                    unselectedColor = UbuntuColors.TextSecondary
                )
            )
        }
    )
}

@Composable
fun StatusPill(
    text: String,
    dotColor: Color,
    containerColor: Color = UbuntuColors.ElevatedSurface,
    textColor: Color = UbuntuColors.TextPrimary
) {
    Row(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(999.dp))
            .border(1.dp, UbuntuColors.Border, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun HardwareButtonBadge(button: ClickButton, sizeDp: Int = 30) {
    val bgColor = if (button.badgeColorHex == 0xFF0F172A) UbuntuColors.ElevatedSurface else Color(button.badgeColorHex)
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
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
    allowAnkiDroid: Boolean = false,
    onDirtyChanged: (Boolean) -> Unit = {}
) {
    val capabilities = remember { PlatformCapabilities.desktop() }
    val current = PresetStore.active(config) ?: return
    var draft by remember(current.id, current) { mutableStateOf(current) }
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
    val testDispatcher = remember { DesktopGenericInputDispatcher }
    var editorRevision by remember { mutableIntStateOf(0) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var countdown by remember { mutableIntStateOf(0) }
    var selectedButton by remember { mutableStateOf(ClickButton.Y) }

    LaunchedEffect(current.id, selectedButton, draft.bindings, draft.inputConfigs) {
        testJob?.cancel()
        countdown = 0
    }
    DisposableEffect(Unit) { onDispose { testJob?.cancel() } }

    val normalizedTarget = capabilities.normalizeAnkiTarget(draft.ankiTarget)
    val availableActions = remember(capabilities, normalizedTarget) { capabilities.availableActions(normalizedTarget) }
    val hasUnsupportedAnkiWebUndo = normalizedTarget == AnkiTarget.ANKIWEB && draft.bindings.values.any { it == MappedTargetAction.ANKI_UNDO }

    val canSave = dirty && draft.name.isNotBlank() && !hasUnsupportedAnkiWebUndo && draft.bindings.all { (key, action) ->
        !action.isGenericInput || draft.inputConfigs[key]?.let { capabilities.isValidInputConfig(action, it) } == true
    }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        // Header Bar & Preset Management
        AdwPreferencesGroup(
            title = "Preset Library",
            description = "Choose a preset, start from a ready-made template, or customize each button."
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { selectionOpen = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                            shape = RoundedCornerShape(8.dp)
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
                            modifier = Modifier.heightIn(min = 44.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("New from template ▾")
                        }
                        DropdownMenu(expanded = templateOpen, onDismissRequest = { templateOpen = false }) {
                            PresetTemplate.entries.forEach { template ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(template.displayName(capabilities), fontWeight = FontWeight.SemiBold)
                                            Text(template.description(capabilities), style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
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
                }

                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    singleLine = true,
                    label = { Text("Preset name") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Desktop Anki Target selector (Desktop Anki / AnkiWeb — never AnkiDroid)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Anki destination (for Anki actions in this preset)", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            AnkiTarget.ANKICONNECT to "Desktop Anki (AnkiConnect)",
                            AnkiTarget.ANKIWEB to "AnkiWeb (Browser)"
                        ).forEach { (target, label) ->
                            FilterChip(
                                selected = normalizedTarget == target,
                                onClick = { draft = draft.copy(ankiTarget = target) },
                                label = { Text(label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = UbuntuColors.YaruOrangeContainer,
                                    selectedLabelColor = UbuntuColors.TextPrimary
                                ),
                                modifier = Modifier.heightIn(min = 40.dp)
                            )
                        }
                    }
                    if (normalizedTarget == AnkiTarget.ANKIWEB) {
                        Text(
                            "Keep an AnkiWeb browser tab focused. First grading press flips the card (Space); next press grades it (1–4).",
                            style = MaterialTheme.typography.bodySmall,
                            color = UbuntuColors.TextSecondary
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        text = if (dirty) "Unsaved edits" else "Saved",
                        dotColor = if (dirty) UbuntuColors.Warning else UbuntuColors.Success
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(
                                onClick = { managementOpen = true },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.heightIn(min = 42.dp)
                            ) {
                                Text("Manage ▾")
                            }
                            DropdownMenu(expanded = managementOpen, onDismissRequest = { managementOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Duplicate") },
                                    onClick = {
                                        val duplicate = draft.copy(
                                            id = PresetStore.newId(),
                                            name = "${draft.name} copy",
                                            ankiTarget = capabilities.normalizeAnkiTarget(draft.ankiTarget)
                                        )
                                        onChange(config.copy(presets = config.presets + duplicate, activePresetId = duplicate.id))
                                        managementOpen = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset to standard Ride + Anki") },
                                    onClick = {
                                        draft = draft.copy(
                                            ankiTarget = AnkiTarget.ANKICONNECT,
                                            bindings = PresetStore.standardBindings(),
                                            inputConfigs = emptyMap()
                                        )
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
                                    text = { Text("Discard unsaved changes") },
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
                        Button(
                            onClick = {
                                if (draft.name.isNotBlank()) {
                                    onChange(config.copy(presets = config.presets.map {
                                        if (it.id == current.id) draft.copy(
                                            name = draft.name.trim(),
                                            ankiTarget = capabilities.normalizeAnkiTarget(draft.ankiTarget)
                                        ) else it
                                    }))
                                }
                            },
                            enabled = canSave,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.heightIn(min = 42.dp)
                        ) {
                            Text("Save preset")
                        }
                    }
                }
            }
        }

        if (hasUnsupportedAnkiWebUndo) {
            Surface(
                shape = RoundedCornerShape(10.dp),
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

        // Button Selector + Press/Hold Binding Editor
        AdwPreferencesGroup(
            title = "Button Actions",
            description = "Select a controller side and button to configure Press and Hold actions."
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = UbuntuColors.YaruOrangeContainer,
                                selectedLabelColor = UbuntuColors.TextPrimary
                            ),
                            modifier = Modifier.heightIn(min = 40.dp)
                        )
                    }
                }

                val buttons = ClickButton.entries.map { it.canonicalButton }.distinct().filter {
                    it.isRightPod == rightPod && (it.isPrimaryConfigurable || it == ClickButton.SHIFT_UP_LEFT || it == ClickButton.SHIFT_DOWN_RIGHT)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, UbuntuColors.Border, RoundedCornerShape(10.dp))
                ) {
                    buttons.forEachIndexed { idx, button ->
                        val isSelected = button == selectedButton
                        val pressSummary = PresetStore.bindingLabel(draft, PresetBindingKey(button, PressType.SINGLE_CLICK), PlatformKind.DESKTOP)
                        val holdKey = PresetBindingKey(button, PressType.LONG_PRESS)
                        val singleRepeats = draft.inputConfigs[PresetBindingKey(button, PressType.SINGLE_CLICK)]?.repeatWhileHeld == true &&
                            (draft.bindings[PresetBindingKey(button, PressType.SINGLE_CLICK)]?.isGenericInput == true)
                        val holdSummary = if (singleRepeats) "Paused while Press repeats" else PresetStore.bindingLabel(draft, holdKey, PlatformKind.DESKTOP)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) UbuntuColors.ElevatedSurface else UbuntuColors.SidebarSurface)
                                .clickable { selectedButton = button }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            HardwareButtonBadge(button, sizeDp = 28)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(button.displayName, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, fontSize = 13.sp)
                                Text(
                                    "Press: $pressSummary  •  Hold: $holdSummary",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UbuntuColors.TextSecondary
                                )
                            }
                            if (isSelected) {
                                Text("Editing", color = UbuntuColors.YaruOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (idx < buttons.lastIndex) HorizontalDivider(color = UbuntuColors.Border)
                    }
                }

                val singleKey = PresetBindingKey(selectedButton, PressType.SINGLE_CLICK)
                val singleAction = draft.bindings[singleKey] ?: MappedTargetAction.NONE
                val singleRepeats = singleAction.isGenericInput && draft.inputConfigs[singleKey]?.repeatWhileHeld == true

                PressType.entries.forEach { press ->
                    val key = PresetBindingKey(selectedButton, press)
                    val action = draft.bindings[key] ?: MappedTargetAction.NONE
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = UbuntuColors.SidebarSurface,
                        border = BorderStroke(1.dp, UbuntuColors.Border),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (press == PressType.SINGLE_CLICK) "Single Press" else "Long Press (450ms)",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp
                                )
                                if (press == PressType.LONG_PRESS && singleRepeats) {
                                    Text(
                                        "Paused while Single Press repeats on hold",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UbuntuColors.Warning
                                    )
                                }
                            }

                            Box {
                                OutlinedButton(
                                    onClick = { actionMenu = key },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(PresetStore.bindingLabel(draft, key, PlatformKind.DESKTOP))
                                }
                                DropdownMenu(
                                    expanded = actionMenu == key,
                                    onDismissRequest = { actionMenu = null },
                                    modifier = Modifier.heightIn(max = 420.dp)
                                ) {
                                    availableActions.forEach { candidate ->
                                        DropdownMenuItem(
                                            text = { Text(candidate.displayLabel(PlatformKind.DESKTOP)) },
                                            onClick = {
                                                val defaults = when (candidate) {
                                                    MappedTargetAction.SCREEN_CLICK -> InputConfig(left = .4f, top = .4f, width = .2f, height = .2f, clickAtPointer = true)
                                                    MappedTargetAction.KEYBOARD_INPUT -> InputConfig(key = PortableKey.RIGHT)
                                                    MappedTargetAction.SCREEN_SCROLL -> InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 3, clickAtPointer = true, left = .25f, top = .25f, width = .5f, height = .5f)
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

                            if (action.isGenericInput) {
                                val payload = draft.inputConfigs[key] ?: InputConfig()
                                fun update(c: InputConfig) {
                                    draft = draft.copy(inputConfigs = draft.inputConfigs + (key to c))
                                }
                                Text(testDispatcher.inputStatus, style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                                when (action) {
                                    MappedTargetAction.SCREEN_CLICK -> {
                                        FilterChip(
                                            selected = payload.clickAtPointer,
                                            onClick = { update(payload.copy(clickAtPointer = !payload.clickAtPointer)) },
                                            label = { Text("Click at current pointer") }
                                        )
                                        if (!payload.clickAtPointer) {
                                            Text("Fixed screen area (% of primary display). Wayland fixed-area clicks require GNOME + flat acceleration + GTK4.", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                                            AreaPercentFields(current.id, current, key, action, editorRevision, payload, ::update)
                                        }
                                    }
                                    MappedTargetAction.KEYBOARD_INPUT -> {
                                        Text("Sends a key shortcut to the focused window.", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                                        var keyMenu by remember { mutableStateOf(false) }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box {
                                                OutlinedButton(onClick = { keyMenu = true }) {
                                                    Text("Key: ${payload.key?.name?.removePrefix("DIGIT_") ?: "Select"} ▾")
                                                }
                                                DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                                                    PortableKey.entries.forEach { portableKey ->
                                                        DropdownMenuItem(
                                                            text = { Text(portableKey.name.removePrefix("DIGIT_")) },
                                                            onClick = { update(payload.copy(key = portableKey)); keyMenu = false }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("Ctrl" to payload.ctrl, "Alt" to payload.alt, "Shift" to payload.shift, "Meta" to payload.meta).forEachIndexed { index, (label, selected) ->
                                                FilterChip(
                                                    selected = selected,
                                                    onClick = {
                                                        update(when (index) {
                                                            0 -> payload.copy(ctrl = !selected)
                                                            1 -> payload.copy(alt = !selected)
                                                            2 -> payload.copy(shift = !selected)
                                                            else -> payload.copy(meta = !selected)
                                                        })
                                                    },
                                                    label = { Text(label) }
                                                )
                                            }
                                        }
                                    }
                                    MappedTargetAction.SCREEN_SCROLL -> {
                                        Text("Scrolls the mouse wheel smoothly. Enable 'Repeat while held' for continuous document scrolling.", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            ScrollDirection.entries.forEach { dir ->
                                                FilterChip(
                                                    selected = payload.scrollDirection == dir,
                                                    onClick = { update(payload.copy(scrollDirection = dir)) },
                                                    label = { Text(dir.name.lowercase().replaceFirstChar { it.uppercase() }) }
                                                )
                                            }
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(onClick = { if (payload.scrollAmount > 1) update(payload.copy(scrollAmount = payload.scrollAmount - 1)) }, enabled = payload.scrollAmount > 1) { Text("-") }
                                            Text("Steps: ${payload.scrollAmount}")
                                            OutlinedButton(onClick = { if (payload.scrollAmount < 20) update(payload.copy(scrollAmount = payload.scrollAmount + 1)) }, enabled = payload.scrollAmount < 20) { Text("+") }
                                        }
                                        FilterChip(
                                            selected = payload.clickAtPointer,
                                            onClick = { update(payload.copy(clickAtPointer = !payload.clickAtPointer)) },
                                            label = { Text("Scroll at current pointer") }
                                        )
                                        if (!payload.clickAtPointer) {
                                            AreaPercentFields(current.id, current, key, action, editorRevision, payload, ::update)
                                        }
                                    }
                                    else -> Unit
                                }
                                if (press == PressType.SINGLE_CLICK) {
                                    FilterChip(
                                        selected = payload.repeatWhileHeld,
                                        onClick = { update(payload.copy(repeatWhileHeld = !payload.repeatWhileHeld)) },
                                        label = { Text("Repeat while held") }
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
                                    enabled = capabilities.isValidInputConfig(action, payload)
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
    val capabilities = remember { PlatformCapabilities.desktop() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        listOf("Left" to payload.left, "Top" to payload.top, "Width" to payload.width, "Height" to payload.height).forEachIndexed { index, (label, value) ->
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
                            0 -> payload.copy(left = number)
                            1 -> payload.copy(top = number)
                            2 -> payload.copy(width = number)
                            else -> payload.copy(height = number)
                        }
                    )
                },
                label = { Text("$label (%)") },
                singleLine = true,
                isError = !capabilities.isValidInputConfig(action, payload),
                modifier = Modifier.weight(1f)
            )
        }
    }
    if (!capabilities.isValidInputConfig(action, payload)) {
        Text("Enter a positive area within 0–100%.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun MappingOverview(config: AppConfiguration) {
    val preset = PresetStore.active(config) ?: return
    val capabilities = remember { PlatformCapabilities.desktop() }
    val targetLabel = when (capabilities.normalizeAnkiTarget(preset.ankiTarget)) {
        AnkiTarget.ANKIWEB -> "AnkiWeb (Browser)"
        else -> "Desktop Anki (AnkiConnect)"
    }
    val buttons = remember {
        ClickButton.entries.map { it.canonicalButton }.distinct()
            .filter { it.isPrimaryConfigurable || it == ClickButton.SHIFT_UP_LEFT || it == ClickButton.SHIFT_DOWN_RIGHT }
    }
    val rightButtons = remember(buttons) { buttons.filter { it.isRightPod } }
    val leftButtons = remember(buttons) { buttons.filter { !it.isRightPod } }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Anki destination: $targetLabel", style = MaterialTheme.typography.bodySmall, color = UbuntuColors.TextSecondary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Right Controller" to rightButtons, "Left Controller" to leftButtons).forEach { (heading, group) ->
                AdwPreferencesGroup(title = heading, modifier = Modifier.weight(1f)) {
                    group.forEachIndexed { idx, button ->
                        val press = PresetStore.bindingLabel(preset, PresetBindingKey(button, PressType.SINGLE_CLICK), PlatformKind.DESKTOP)
                        val hold = PresetStore.bindingLabel(preset, PresetBindingKey(button, PressType.LONG_PRESS), PlatformKind.DESKTOP)
                        AdwActionRow(
                            title = button.displayName,
                            subtitle = "Press: $press\nHold: $hold",
                            leading = { HardwareButtonBadge(button) }
                        )
                        if (idx < group.lastIndex) HorizontalDivider(color = UbuntuColors.Border)
                    }
                }
            }
        }
    }
}
