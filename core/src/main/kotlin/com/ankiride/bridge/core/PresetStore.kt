package com.ankiride.bridge.core

import java.util.Base64
import java.util.UUID

enum class PresetTemplate(val label: String, val description: String) {
    PRESENTATION("Presentations", "Next/previous slide, start slideshow, and blank screen"),
    DOCUMENT_SCROLL("Document Scrolling", "Smooth hold-to-scroll up/down and page navigation"),
    ANKI_LOCAL("Anki", "Flip, grade (Again/Hard/Good/Easy), and Undo in Anki"),
    ANKI_WEB("AnkiWeb (Browser)", "Flip and grade cards directly on ankiweb.net"),
    MYWHOOSH_SHIFTING("MyWhoosh Shifting", "Virtual gears, steering & camera via OpenBikeControl"),
    RIDE_WEB_SLIDES("Ride + Web, Docs & Slides", "Scroll docs/websites, flip Slides, and shift MyWhoosh gears"),
    RIDE_AND_ANKI("Ride + Anki", "Anki on the right controller, MyWhoosh shifting/steering on the left"),
    BLANK("Blank", "Empty preset with no button actions");

    fun displayName(capabilities: PlatformCapabilities): String = when (this) {
        ANKI_LOCAL -> if (capabilities.platform == PlatformKind.DESKTOP) "Anki Desktop" else "AnkiDroid"
        PRESENTATION -> if (capabilities.platform == PlatformKind.ANDROID) "Presentations (Swipe)" else "Presentations"
        else -> label
    }

    fun description(capabilities: PlatformCapabilities): String =
        if (this == PRESENTATION && capabilities.platform == PlatformKind.ANDROID) {
            "Swipe left or right between slides in presentation or PDF apps"
        } else {
            description
        }
}

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
                preset.inputConfigs[key]?.let { c ->
                    append(',').append(
                        encode(
                            listOf(
                                c.left, c.top, c.width, c.height,
                                c.key?.name ?: "", c.ctrl, c.alt, c.shift, c.meta,
                                c.clickAtPointer, c.scrollDirection.name, c.scrollAmount, c.repeatWhileHeld
                            ).joinToString(";")
                        )
                    )
                }
            }
            append('\n')
        }
    }

    fun deserialize(raw: String?): Pair<List<MappingPreset>, String?>? {
        return try {
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
                        val payload = InputConfig(
                            left = c[0].toFloat(), top = c[1].toFloat(), width = c[2].toFloat(), height = c[3].toFloat(),
                            key = c[4].takeIf { it.isNotEmpty() }?.let(PortableKey::valueOf),
                            ctrl = c[5].toBooleanStrict(), alt = c[6].toBooleanStrict(), shift = c[7].toBooleanStrict(), meta = c[8].toBooleanStrict(),
                            clickAtPointer = c.getOrNull(9)?.toBooleanStrict() ?: false,
                            scrollDirection = c.getOrNull(10)?.takeIf { it.isNotEmpty() }?.let(ScrollDirection::valueOf) ?: ScrollDirection.DOWN,
                            scrollAmount = c.getOrNull(11)?.toIntOrNull() ?: 3,
                            repeatWhileHeld = c.getOrNull(12)?.toBooleanStrict() ?: false
                        )
                        configs[key] = payload
                    } catch (_: Exception) { /* A bad payload disables only this binding. */ }
                }
                MappingPreset(parts[0], decode(parts[1]), AnkiTarget.valueOf(parts[2]), bindings, configs)
            }
            if (presets.isEmpty() || presets.map { it.id }.distinct().size != presets.size) return null
            presets to header.getOrNull(1)?.takeIf { id -> presets.any { it.id == id } }
        } catch (_: Exception) {
            null
        }
    }

    fun bindingLabel(
        preset: MappingPreset,
        key: PresetBindingKey,
        platform: PlatformKind = PlatformKind.DESKTOP
    ): String {
        val action = preset.bindings[key] ?: MappedTargetAction.NONE
        if (platform == PlatformKind.ANDROID && action == MappedTargetAction.KEYBOARD_INPUT) {
            return "Not available on Android"
        }
        if (!action.isGenericInput) return action.label
        val capabilities = if (platform == PlatformKind.ANDROID) PlatformCapabilities.android() else PlatformCapabilities.desktop()
        val payload = preset.inputConfigs[key]
        if (payload == null || !capabilities.isValidInputConfig(action, payload)) {
            return "${action.displayLabel(platform)} (not configured)"
        }
        return payload.label(action, platform)
    }

    fun isRepeatEnabled(config: AppConfiguration, button: ClickButton): Boolean {
        val preset = active(config) ?: return false
        val key = PresetBindingKey(button.canonicalButton, PressType.SINGLE_CLICK)
        val action = preset.bindings[key] ?: return false
        if (!action.isGenericInput) return false
        val payload = preset.inputConfigs[key] ?: return false
        return payload.validFor(action) && payload.repeatWhileHeld
    }

    fun activePresetName(config: AppConfiguration): String = active(config)?.name ?: "Default"

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

    fun createFromTemplate(
        template: PresetTemplate,
        capabilities: PlatformCapabilities,
        nameOverride: String? = null
    ): MappingPreset {
        val name = nameOverride?.trim()?.ifBlank { null } ?: template.displayName(capabilities)
        val blank = snapshot(name, capabilities.defaultAnkiTarget) { _, _ -> MappedTargetAction.NONE }
        if (template == PresetTemplate.BLANK) return blank

        val bindings = blank.bindings.toMutableMap()
        val inputConfigs = mutableMapOf<PresetBindingKey, InputConfig>()

        fun setGeneric(button: ClickButton, press: PressType, action: MappedTargetAction, config: InputConfig) {
            val key = PresetBindingKey(button.canonicalButton, press)
            bindings[key] = action
            inputConfigs[key] = config
        }

        fun setAction(button: ClickButton, press: PressType, action: MappedTargetAction) {
            bindings[PresetBindingKey(button.canonicalButton, press)] = action
        }

        val target = when (template) {
            PresetTemplate.PRESENTATION -> {
                if (capabilities.platform == PlatformKind.DESKTOP) {
                    setGeneric(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT))
                    setGeneric(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN))
                    setGeneric(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.LEFT))
                    setGeneric(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.B))
                    setGeneric(ClickButton.Y, PressType.LONG_PRESS, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.ESCAPE))
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.F5))
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.LONG_PRESS, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.HOME))
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT))
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.LEFT))
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_UP))
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN))
                    setGeneric(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.ESCAPE))
                } else {
                    val nextSwipe = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.RIGHT, scrollAmount = 5)
                    val prevSwipe = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.LEFT, scrollAmount = 5)
                    val upSwipe = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.UP, scrollAmount = 4)
                    val downSwipe = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 4)
                    setGeneric(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextSwipe)
                    setGeneric(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextSwipe)
                    setGeneric(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevSwipe)
                    setGeneric(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevSwipe)
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextSwipe)
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextSwipe)
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevSwipe)
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, upSwipe)
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, downSwipe)
                    setGeneric(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevSwipe)
                }
                capabilities.defaultAnkiTarget
            }

            PresetTemplate.DOCUMENT_SCROLL -> {
                if (capabilities.platform == PlatformKind.DESKTOP) {
                    setGeneric(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.UP, scrollAmount = 3, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 3, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_UP))
                    setGeneric(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN))
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.HOME))
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.LONG_PRESS, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.END))
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.UP, scrollAmount = 3, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 3, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_UP))
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN))
                    setGeneric(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.HOME))
                } else {
                    val scrollUp = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.UP, scrollAmount = 4, repeatWhileHeld = true)
                    val scrollDown = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 4, repeatWhileHeld = true)
                    val prevPage = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.LEFT, scrollAmount = 5)
                    val nextPage = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.RIGHT, scrollAmount = 5)
                    val bigUp = InputConfig(left = 0.20f, top = 0.15f, width = 0.60f, height = 0.70f, scrollDirection = ScrollDirection.UP, scrollAmount = 8)
                    val bigDown = InputConfig(left = 0.20f, top = 0.15f, width = 0.60f, height = 0.70f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 8)
                    setGeneric(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollUp)
                    setGeneric(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollDown)
                    setGeneric(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevPage)
                    setGeneric(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextPage)
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, bigDown)
                    setGeneric(ClickButton.SHIFT_UP_RIGHT, PressType.LONG_PRESS, MappedTargetAction.SCREEN_SCROLL, bigUp)
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollUp)
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollDown)
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevPage)
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextPage)
                    setGeneric(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, bigUp)
                }
                capabilities.defaultAnkiTarget
            }

            PresetTemplate.ANKI_LOCAL, PresetTemplate.ANKI_WEB -> {
                val includeUndo = template == PresetTemplate.ANKI_LOCAL
                setAction(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_OR_AGAIN)
                setAction(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_OR_HARD)
                setAction(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_OR_GOOD)
                setAction(ClickButton.A, PressType.LONG_PRESS, MappedTargetAction.ANKI_FLIP_OR_EASY)
                setAction(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_OR_GOOD)
                setAction(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_ONLY)
                setAction(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_ONLY)
                setAction(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_ONLY)
                setAction(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_FLIP_ONLY)
                if (includeUndo) {
                    setAction(ClickButton.Y, PressType.LONG_PRESS, MappedTargetAction.ANKI_UNDO)
                    setAction(ClickButton.Z, PressType.LONG_PRESS, MappedTargetAction.ANKI_UNDO)
                    setAction(ClickButton.B, PressType.LONG_PRESS, MappedTargetAction.ANKI_UNDO)
                    setAction(ClickButton.SHIFT_UP_RIGHT, PressType.LONG_PRESS, MappedTargetAction.ANKI_UNDO)
                    setAction(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_UNDO)
                    setAction(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.ANKI_UNDO)
                }
                if (template == PresetTemplate.ANKI_WEB) AnkiTarget.ANKIWEB else capabilities.defaultAnkiTarget
            }

            PresetTemplate.MYWHOOSH_SHIFTING -> {
                setAction(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.SHIFT_UP_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.SHIFT_DOWN_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.MW_STEER_LEFT)
                setAction(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.MW_STEER_RIGHT)
                setAction(ClickButton.Y, PressType.SINGLE_CLICK, MappedTargetAction.MW_CAMERA)
                setAction(ClickButton.Y, PressType.LONG_PRESS, MappedTargetAction.MW_HUD)
                setAction(ClickButton.B, PressType.SINGLE_CLICK, MappedTargetAction.MW_EMOTE)
                capabilities.defaultAnkiTarget
            }

            PresetTemplate.RIDE_WEB_SLIDES -> {
                if (capabilities.platform == PlatformKind.DESKTOP) {
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.UP, scrollAmount = 5, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 5, clickAtPointer = true, repeatWhileHeld = true))
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.LEFT))
                    setGeneric(ClickButton.LEFT, PressType.LONG_PRESS, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_UP))
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.RIGHT))
                    setGeneric(ClickButton.RIGHT, PressType.LONG_PRESS, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN))
                } else {
                    val scrollUp = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.UP, scrollAmount = 4, repeatWhileHeld = true)
                    val scrollDown = InputConfig(left = 0.20f, top = 0.20f, width = 0.60f, height = 0.60f, scrollDirection = ScrollDirection.DOWN, scrollAmount = 4, repeatWhileHeld = true)
                    val prevPage = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.LEFT, scrollAmount = 5)
                    val nextPage = InputConfig(left = 0.15f, top = 0.25f, width = 0.70f, height = 0.50f, scrollDirection = ScrollDirection.RIGHT, scrollAmount = 5)
                    setGeneric(ClickButton.UP, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollUp)
                    setGeneric(ClickButton.DOWN, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, scrollDown)
                    setGeneric(ClickButton.LEFT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, prevPage)
                    setGeneric(ClickButton.RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.SCREEN_SCROLL, nextPage)
                }
                setAction(ClickButton.SHIFT_UP_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.SHIFT_UP_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.A, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_UP)
                setAction(ClickButton.SHIFT_DOWN_LEFT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.SHIFT_DOWN_RIGHT, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                setAction(ClickButton.Z, PressType.SINGLE_CLICK, MappedTargetAction.MW_SHIFT_DOWN)
                capabilities.defaultAnkiTarget
            }

            PresetTemplate.RIDE_AND_ANKI -> {
                bindings.putAll(standardBindings())
                capabilities.defaultAnkiTarget
            }

            PresetTemplate.BLANK -> capabilities.defaultAnkiTarget
        }

        return blank.copy(ankiTarget = capabilities.normalizeAnkiTarget(target), bindings = bindings, inputConfigs = inputConfigs)
    }

    fun defaultStarterPresets(capabilities: PlatformCapabilities): Pair<List<MappingPreset>, String> {
        val list = listOf(
            createFromTemplate(PresetTemplate.ANKI_LOCAL, capabilities),
            createFromTemplate(PresetTemplate.ANKI_WEB, capabilities),
            createFromTemplate(PresetTemplate.PRESENTATION, capabilities),
            createFromTemplate(PresetTemplate.DOCUMENT_SCROLL, capabilities),
            createFromTemplate(PresetTemplate.MYWHOOSH_SHIFTING, capabilities),
            createFromTemplate(PresetTemplate.RIDE_WEB_SLIDES, capabilities),
            createFromTemplate(PresetTemplate.RIDE_AND_ANKI, capabilities)
        )
        return list to list.first().id
    }

    fun newId(): String = UUID.randomUUID().toString()
    fun active(config: AppConfiguration): MappingPreset? = config.presets.firstOrNull { it.id == config.activePresetId } ?: config.presets.firstOrNull()
    fun resolve(config: AppConfiguration, button: ClickButton, press: PressType): MappedTargetAction? =
        active(config)?.bindings?.get(PresetBindingKey(button.canonicalButton, press)) ?: active(config)?.let { MappedTargetAction.NONE }

    fun activePresetUsesMyWhoosh(config: AppConfiguration): Boolean =
        active(config)?.bindings?.values?.any { it.myWhooshAction != null } == true

    fun activePresetUsesAnki(config: AppConfiguration): Boolean =
        active(config)?.bindings?.values?.any { it.ankiAction != null } == true

    fun applyStartupDefaultPreset(config: AppConfiguration): AppConfiguration {
        if (config.presets.isEmpty()) return config
        val startupId = config.defaultPresetId?.takeIf { id -> config.presets.any { it.id == id } }
        val resolvedId = startupId
            ?: config.activePresetId?.takeIf { id -> config.presets.any { it.id == id } }
            ?: config.presets.first().id
        return if (resolvedId == config.activePresetId) config else config.copy(activePresetId = resolvedId)
    }

    fun snapshot(name: String, target: AnkiTarget, resolve: (ClickButton, PressType) -> MappedTargetAction): MappingPreset =
        MappingPreset(
            newId(),
            name,
            target,
            ClickButton.entries.map { it.canonicalButton }.distinct().flatMap { button ->
                PressType.entries.map { press -> PresetBindingKey(button, press) to resolve(button, press) }
            }.toMap()
        )
}
