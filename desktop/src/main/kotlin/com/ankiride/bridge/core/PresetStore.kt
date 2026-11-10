package com.ankiride.bridge.core

import java.util.Base64
import java.util.UUID

/** Stable, versioned representation; old settings remain untouched for recovery. */
object PresetStore {
    private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun decode(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
    fun serialize(presets: List<MappingPreset>, active: String?): String = buildString {
        append("3|").append(active.orEmpty()).append('\n')
        presets.forEach { preset ->
            append(preset.id).append('|').append(encode(preset.name)).append('|').append(preset.ankiTarget.name)
            preset.bindings.forEach { (key, action) ->
                append('|').append(key.button.name).append(',').append(key.pressType.name).append(',').append(action.name)
                preset.inputConfigs[key]?.let { c -> append(',').append(encode(listOf(c.left, c.top, c.width, c.height, c.key?.name ?: "", c.ctrl, c.alt, c.shift, c.meta, c.clickAtPointer).joinToString(";"))) }
            }
            append('\n')
        }
    }
    fun deserialize(raw: String?): Pair<List<MappingPreset>, String?>? { return try {
        val lines = raw?.lines() ?: return null
        val header = lines.first().split('|')
        if (header.firstOrNull() !in listOf("1", "2", "3")) return null
        val presets = lines.drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split('|')
            val bindings = mutableMapOf<PresetBindingKey, MappedTargetAction>()
            val configs = mutableMapOf<PresetBindingKey, InputConfig>()
            parts.drop(3).forEach { entry ->
                val values = entry.split(',')
                val key = PresetBindingKey(ClickButton.valueOf(values[0]), PressType.valueOf(values[1]))
                val action = MappedTargetAction.valueOf(values[2])
                bindings[key] = action
                if (values.size > 3) try {
                    val c = decode(values[3]).split(';')
                    val payload = InputConfig(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat(), c[4].takeIf { it.isNotEmpty() }?.let(PortableKey::valueOf), c[5].toBooleanStrict(), c[6].toBooleanStrict(), c[7].toBooleanStrict(), c[8].toBooleanStrict(), c.getOrNull(9)?.toBooleanStrict() ?: false)
                    configs[key] = payload
                } catch (_: Exception) { /* A bad payload disables only this binding. */ }
            }
            MappingPreset(parts[0], decode(parts[1]), AnkiTarget.valueOf(parts[2]), bindings, configs)
        }
        if (presets.isEmpty() || presets.map { it.id }.distinct().size != presets.size) return null
        presets to header.getOrNull(1)?.takeIf { id -> presets.any { it.id == id } }
    } catch (_: Exception) { null } }

    fun bindingLabel(preset: MappingPreset, key: PresetBindingKey): String {
        val action = preset.bindings[key] ?: MappedTargetAction.NONE
        if (action != MappedTargetAction.SCREEN_CLICK && action != MappedTargetAction.KEYBOARD_INPUT) return action.label
        return preset.inputConfigs[key]?.takeIf { it.validFor(action) }?.label(action) ?: "${action.label} (not configured)"
    }
    fun standardBindings(): Map<PresetBindingKey, MappedTargetAction> = snapshot("Standard", AnkiTarget.ANKICONNECT) { button, press ->
        when (button) {
            ClickButton.Y, ClickButton.SHIFT_DOWN_RIGHT -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_AGAIN
            ClickButton.Z -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_HARD
            ClickButton.A -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_FLIP_OR_EASY else MappedTargetAction.ANKI_FLIP_OR_GOOD
            ClickButton.B -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_OR_GOOD
            ClickButton.SHIFT_UP_RIGHT -> if (press == PressType.LONG_PRESS) MappedTargetAction.ANKI_UNDO else MappedTargetAction.ANKI_FLIP_ONLY
            ClickButton.SHIFT_UP_LEFT -> MappedTargetAction.MW_SHIFT_UP
            ClickButton.SHIFT_DOWN_LEFT -> if (press == PressType.LONG_PRESS) MappedTargetAction.MW_SHIFT_UP else MappedTargetAction.MW_SHIFT_DOWN
            ClickButton.LEFT -> MappedTargetAction.MW_STEER_LEFT
            ClickButton.RIGHT -> MappedTargetAction.MW_STEER_RIGHT
            ClickButton.UP -> MappedTargetAction.MW_CAMERA
            ClickButton.DOWN -> MappedTargetAction.MW_EMOTE
            else -> MappedTargetAction.NONE
        }
    }.bindings
    fun newId(): String = UUID.randomUUID().toString()
    fun active(config: AppConfiguration): MappingPreset? = config.presets.firstOrNull { it.id == config.activePresetId }
    fun resolve(config: AppConfiguration, button: ClickButton, press: PressType): MappedTargetAction? =
        active(config)?.bindings?.get(PresetBindingKey(button.canonicalButton, press)) ?: active(config)?.let { MappedTargetAction.NONE }
    fun snapshot(name: String, target: AnkiTarget, resolve: (ClickButton, PressType) -> MappedTargetAction): MappingPreset =
        MappingPreset(newId(), name, target, ClickButton.entries.map { it.canonicalButton }.distinct().flatMap { button ->
            PressType.entries.map { press -> PresetBindingKey(button, press) to resolve(button, press) }
        }.toMap())
}
