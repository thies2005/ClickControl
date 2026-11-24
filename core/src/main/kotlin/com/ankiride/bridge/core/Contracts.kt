package com.ankiride.bridge.core

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlatformKind { DESKTOP, ANDROID }

enum class MyWhooshLocation(val displayName: String) {
    ON_THIS_DEVICE("On this device"),
    ON_ANOTHER_DEVICE("On another device")
}

enum class PodAssignment {
    SINGLE_CONTROLLER,
    RIGHT_CONTROLLER_ONLY,
    BOTH_CONTROLLERS;

    val isSingleControllerMode: Boolean
        get() = this == SINGLE_CONTROLLER || this == RIGHT_CONTROLLER_ONLY

    companion object {
        fun fromStoredName(raw: String?): PodAssignment? = when (raw?.trim()) {
            "SINGLE_CONTROLLER", "LEGACY_SHARED" -> SINGLE_CONTROLLER
            "RIGHT_CONTROLLER_ONLY", "RIGHT_ANKI_ONLY" -> RIGHT_CONTROLLER_ONLY
            "BOTH_CONTROLLERS", "RIGHT_ANKI_LEFT_BIKE" -> BOTH_CONTROLLERS
            else -> null
        }
    }
}

enum class BleConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    HANDSHAKING,
    CONNECTED_RIGHT_ONLY,
    CONNECTED_LEFT_ONLY,
    CONNECTED_BOTH_PODS,
    ERROR
}

enum class ClickButton(
    val bitmask: Int,
    val displayName: String,
    val shortBadge: String,
    val badgeColorHex: Long,
    val isRightPod: Boolean,
    val isPrimaryConfigurable: Boolean = true,
    val subBadgeGlyph: String = ""
) {
    Y(0x0040, "Right 'Y' (Top · Cyan)", "Y", 0xFF00AEEF, true, true, "≡"),
    Z(0x0080, "Right 'Z' (Left · Orange)", "Z", 0xFFFF6B35, true, true, "⏸"),
    A(0x0010, "Right 'A' (Right · Green)", "A", 0xFF8CC63F, true, true, "★"),
    B(0x0020, "Right 'B' (Bottom · Pink)", "B", 0xFFFF3385, true, true, "←"),
    SHIFT_UP_RIGHT(0x1000, "Right Bottom '+' (Shift Up)", "+", 0xFF0F172A, true, true, "⚙+"),
    SHIFT_DOWN_LEFT(0x0200, "Left Bottom '–' (Shift Down)", "–", 0xFF0F172A, false, true, "⚙-"),
    UP(0x0002, "Left D-Pad ^ (Up)", "^", 0xFF0F172A, false, true, "↑"),
    LEFT(0x0001, "Left D-Pad < (Left)", "<", 0xFF0F172A, false, true, "«"),
    RIGHT(0x0004, "Left D-Pad > (Right)", ">", 0xFF0F172A, false, true, "»"),
    DOWN(0x0008, "Left D-Pad v (Down)", "v", 0xFF0F172A, false, true, "↓"),
    // Secondary bit aliases for hardware firmware variants
    SHIFT_UP_LEFT(0x0100, "Left Shift Up (Bit 8)", "+L", 0xFF0F172A, false, false, "+"),
    SHIFT_DOWN_LEFT_ALT(0x0400, "Left '–' Button (Bit 10)", "–", 0xFF0F172A, false, false, "-"),
    POWERUP_LEFT(0x0800, "Left PowerUp (Bit 11)", "P", 0xFF0F172A, false, false, "P"),
    SHIFT_UP_RIGHT_ALT(0x2000, "Right '+' Button (Bit 13)", "+", 0xFF0F172A, true, false, "+"),
    SHIFT_DOWN_RIGHT(0x4000, "Right Aux Shift (Bit 14)", "–R", 0xFF0F172A, true, false, "-"),
    POWERUP_RIGHT(0x8000, "Right PowerUp (Bit 15)", "P", 0xFF0F172A, true, false, "P"),
    ONOFF_LEFT(0x10000, "Left On/Off (Bit 16)", "O", 0xFF0F172A, false, false, "O"),
    ONOFF_RIGHT(0x20000, "Right On/Off (Bit 17)", "O", 0xFF0F172A, true, false, "O");

    val canonicalButton: ClickButton
        get() = when (this) {
            SHIFT_UP_RIGHT_ALT, POWERUP_RIGHT, ONOFF_RIGHT -> SHIFT_UP_RIGHT
            SHIFT_DOWN_LEFT_ALT, POWERUP_LEFT, ONOFF_LEFT -> SHIFT_DOWN_LEFT
            else -> this
        }

    val isShiftUpButton: Boolean
        get() = canonicalButton == SHIFT_UP_RIGHT

    val isShiftDownButton: Boolean
        get() = canonicalButton == SHIFT_DOWN_LEFT
}

enum class PressType(val displayName: String) {
    SINGLE_CLICK("Single Press"),
    LONG_PRESS("Long Press (450ms)")
}

data class ButtonGestureEvent(
    val button: ClickButton,
    val type: PressType,
    val timestampMs: Long = System.currentTimeMillis(),
    val isRepeat: Boolean = false
)

enum class AnkiAction(val displayName: String) {
    FLIP_OR_AGAIN("Anki: Didn't Know / Again (1)"),
    FLIP_OR_HARD("Anki: Hard (2)"),
    FLIP_OR_GOOD("Anki: Knew It / Good (3)"),
    FLIP_OR_EASY("Anki: Easy (4)"),
    FLIP_CARD("Anki: Flip Card Only"),
    UNDO("Anki: Undo Last Card")
}

enum class MyWhooshAction(val openBikeOpcode: Byte, val displayName: String) {
    SHIFT_UP(0x01, "MyWhoosh: Shift Up (+)"),
    SHIFT_DOWN(0x02, "MyWhoosh: Shift Down (–)"),
    UI_NAV_UP(0x10, "MyWhoosh: UI Up"),
    UI_NAV_DOWN(0x11, "MyWhoosh: UI Down"),
    UI_NAV_LEFT(0x12, "MyWhoosh: UI Left"),
    UI_NAV_RIGHT(0x13, "MyWhoosh: UI Right"),
    UI_CONFIRM(0x14, "MyWhoosh: Confirm / Select"),
    UI_BACK(0x15, "MyWhoosh: Back"),
    PAUSE_MENU(0x16, "MyWhoosh: Pause"),
    STEER_LEFT(0x18, "MyWhoosh: Steer Left"),
    STEER_RIGHT(0x19, "MyWhoosh: Steer Right"),
    EMOTE(0x20, "MyWhoosh: Emote"),
    SCREENSHOT(0x24, "MyWhoosh: Screenshot"),
    TOGGLE_CAMERA(0x40, "MyWhoosh: Camera Angle"),
    HUD_TOGGLE(0x44, "MyWhoosh: Toggle HUD"),
    USE_POWERUP(0x50, "MyWhoosh: Use Power-Up")
}

enum class MappedTargetAction(
    val label: String,
    val ankiAction: AnkiAction? = null,
    val myWhooshAction: MyWhooshAction? = null
) {
    NONE("Disabled (No Action)"),
    SCREEN_CLICK("Screen click"),
    KEYBOARD_INPUT("Keyboard input"),
    SCREEN_SCROLL("Scroll / Swipe"),
    MW_SHIFT_UP("MyWhoosh: Shift Up (+)", myWhooshAction = MyWhooshAction.SHIFT_UP),
    MW_SHIFT_DOWN("MyWhoosh: Shift Down (–)", myWhooshAction = MyWhooshAction.SHIFT_DOWN),
    ANKI_FLIP_OR_AGAIN("Anki: Didn't Know / Again (1)", ankiAction = AnkiAction.FLIP_OR_AGAIN),
    ANKI_FLIP_OR_GOOD("Anki: Knew It / Good (3)", ankiAction = AnkiAction.FLIP_OR_GOOD),
    ANKI_UNDO("Anki: Undo Last Card", ankiAction = AnkiAction.UNDO),
    ANKI_FLIP_OR_HARD("Anki: Hard (2)", ankiAction = AnkiAction.FLIP_OR_HARD),
    ANKI_FLIP_OR_EASY("Anki: Easy (4)", ankiAction = AnkiAction.FLIP_OR_EASY),
    ANKI_FLIP_ONLY("Anki: Flip Card Only", ankiAction = AnkiAction.FLIP_CARD),
    MW_STEER_LEFT("MyWhoosh: Steer Left", myWhooshAction = MyWhooshAction.STEER_LEFT),
    MW_STEER_RIGHT("MyWhoosh: Steer Right", myWhooshAction = MyWhooshAction.STEER_RIGHT),
    MW_CAMERA("MyWhoosh: Toggle Camera", myWhooshAction = MyWhooshAction.TOGGLE_CAMERA),
    MW_EMOTE("MyWhoosh: Emote", myWhooshAction = MyWhooshAction.EMOTE),
    MW_HUD("MyWhoosh: Toggle HUD", myWhooshAction = MyWhooshAction.HUD_TOGGLE),
    MW_CONFIRM("MyWhoosh: UI Select", myWhooshAction = MyWhooshAction.UI_CONFIRM),
    MW_BACK("MyWhoosh: UI Back", myWhooshAction = MyWhooshAction.UI_BACK),
    MW_POWERUP("MyWhoosh: Power-Up", myWhooshAction = MyWhooshAction.USE_POWERUP);

    val isGenericInput: Boolean
        get() = this == SCREEN_CLICK || this == KEYBOARD_INPUT || this == SCREEN_SCROLL

    fun displayLabel(platform: PlatformKind): String = when (platform) {
        PlatformKind.ANDROID -> when (this) {
            SCREEN_CLICK -> "Screen tap"
            SCREEN_SCROLL -> "Touch swipe / scroll"
            KEYBOARD_INPUT -> "Keyboard shortcut (unavailable on Android)"
            else -> label
        }
        PlatformKind.DESKTOP -> when (this) {
            SCREEN_CLICK -> "Screen click"
            SCREEN_SCROLL -> "Mouse wheel scroll"
            KEYBOARD_INPUT -> "Keyboard shortcut"
            else -> label
        }
    }
}

enum class AnkiTarget { ANKIDROID, ANKICONNECT, ANKIWEB }

data class PresetBindingKey(val button: ClickButton, val pressType: PressType)
enum class ScrollDirection { UP, DOWN, LEFT, RIGHT }
enum class PortableKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    SPACE, ENTER, TAB, ESCAPE, UP, DOWN, LEFT, RIGHT, BACKSPACE, DELETE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    PAGE_UP, PAGE_DOWN, HOME, END
}

data class InputConfig(
    val left: Float = 0f, val top: Float = 0f, val width: Float = 0f, val height: Float = 0f,
    val key: PortableKey? = null, val ctrl: Boolean = false, val alt: Boolean = false,
    val shift: Boolean = false, val meta: Boolean = false,
    val clickAtPointer: Boolean = false,
    val scrollDirection: ScrollDirection = ScrollDirection.DOWN,
    val scrollAmount: Int = 3,
    val repeatWhileHeld: Boolean = false
) {
    private fun hasValidArea(): Boolean =
        listOf(left, top, width, height).all { it.isFinite() } &&
            left >= 0f && top >= 0f && width > 0f && height > 0f &&
            left + width <= 1f && top + height <= 1f

    fun validFor(action: MappedTargetAction): Boolean = when (action) {
        MappedTargetAction.SCREEN_CLICK -> clickAtPointer || hasValidArea()
        MappedTargetAction.KEYBOARD_INPUT -> key != null
        MappedTargetAction.SCREEN_SCROLL -> scrollAmount in 1..20 && (clickAtPointer || hasValidArea())
        else -> false
    }

    fun label(action: MappedTargetAction, platform: PlatformKind = PlatformKind.DESKTOP): String {
        val base = when (action) {
            MappedTargetAction.SCREEN_CLICK -> when {
                platform == PlatformKind.ANDROID && clickAtPointer -> "Screen tap (area required)"
                platform == PlatformKind.ANDROID -> "Screen tap (${(left * 100).toInt()}%, ${(top * 100).toInt()}%, ${(width * 100).toInt()}% × ${(height * 100).toInt()}%)"
                clickAtPointer -> "Click at pointer"
                else -> "Screen click (${(left * 100).toInt()}%, ${(top * 100).toInt()}%, ${(width * 100).toInt()}% × ${(height * 100).toInt()}%)"
            }
            MappedTargetAction.KEYBOARD_INPUT -> if (platform == PlatformKind.ANDROID) {
                "Not available on Android"
            } else {
                listOfNotNull(
                    "Ctrl".takeIf { ctrl },
                    "Alt".takeIf { alt },
                    "Shift".takeIf { shift },
                    "Meta".takeIf { meta },
                    key?.name?.removePrefix("DIGIT_")
                ).joinToString(" + ")
            }
            MappedTargetAction.SCREEN_SCROLL -> if (platform == PlatformKind.ANDROID) {
                when (scrollDirection) {
                    ScrollDirection.RIGHT -> "Swipe: Next slide / page ($scrollAmount)"
                    ScrollDirection.LEFT -> "Swipe: Previous slide / page ($scrollAmount)"
                    ScrollDirection.DOWN -> "Swipe: Scroll down ($scrollAmount)"
                    ScrollDirection.UP -> "Swipe: Scroll up ($scrollAmount)"
                }
            } else {
                "Scroll ${scrollDirection.name.lowercase()} ($scrollAmount)"
            }
            else -> action.label
        }
        return if (action.isGenericInput && repeatWhileHeld && !(platform == PlatformKind.ANDROID && action == MappedTargetAction.KEYBOARD_INPUT)) {
            "$base (repeat)"
        } else {
            base
        }
    }
}

/**
 * Authoritative capability matrix separating Desktop and Android features so
 * neither UI nor dispatcher exposes unsupported modes, targets, or input types.
 */
data class PlatformCapabilities(
    val platform: PlatformKind,
    val supportedAnkiTargets: Set<AnkiTarget>,
    val supportedGenericActions: Set<MappedTargetAction>,
    val supportsPointerTargeting: Boolean,
    val supportsHaptics: Boolean,
    val supportsTouchCalibration: Boolean,
    val supportsLinuxAutostart: Boolean
) {
    val defaultAnkiTarget: AnkiTarget
        get() = when (platform) {
            PlatformKind.DESKTOP -> AnkiTarget.ANKICONNECT
            PlatformKind.ANDROID -> AnkiTarget.ANKIDROID
        }

    fun supportsAction(action: MappedTargetAction): Boolean =
        !action.isGenericInput || action in supportedGenericActions

    fun availableActions(): List<MappedTargetAction> =
        MappedTargetAction.entries.filter { supportsAction(it) }

    fun availableActions(ankiTarget: AnkiTarget): List<MappedTargetAction> =
        availableActions().filterNot { ankiTarget == AnkiTarget.ANKIWEB && it == MappedTargetAction.ANKI_UNDO }

    fun supportsAnkiTarget(target: AnkiTarget): Boolean = target in supportedAnkiTargets

    fun normalizeAnkiTarget(target: AnkiTarget): AnkiTarget =
        if (target in supportedAnkiTargets) target else defaultAnkiTarget

    fun isValidInputConfig(action: MappedTargetAction, config: InputConfig): Boolean {
        if (!supportsAction(action) || !config.validFor(action)) return false
        if (!supportsPointerTargeting && config.clickAtPointer &&
            (action == MappedTargetAction.SCREEN_CLICK || action == MappedTargetAction.SCREEN_SCROLL)
        ) {
            return false
        }
        return true
    }

    companion object {
        fun desktop(
            isLinux: Boolean = System.getProperty("os.name", "").lowercase().contains("linux")
        ): PlatformCapabilities = PlatformCapabilities(
            platform = PlatformKind.DESKTOP,
            supportedAnkiTargets = setOf(AnkiTarget.ANKICONNECT, AnkiTarget.ANKIWEB),
            supportedGenericActions = setOf(
                MappedTargetAction.KEYBOARD_INPUT,
                MappedTargetAction.SCREEN_SCROLL,
                MappedTargetAction.SCREEN_CLICK
            ),
            supportsPointerTargeting = true,
            supportsHaptics = false,
            supportsTouchCalibration = false,
            supportsLinuxAutostart = isLinux
        )

        fun android(): PlatformCapabilities = PlatformCapabilities(
            platform = PlatformKind.ANDROID,
            supportedAnkiTargets = setOf(AnkiTarget.ANKIDROID, AnkiTarget.ANKIWEB, AnkiTarget.ANKICONNECT),
            supportedGenericActions = setOf(
                MappedTargetAction.SCREEN_SCROLL,
                MappedTargetAction.SCREEN_CLICK
            ),
            supportsPointerTargeting = false,
            supportsHaptics = true,
            supportsTouchCalibration = true,
            supportsLinuxAutostart = false
        )
    }
}

interface GenericInputDispatcher {
    fun dispatch(action: MappedTargetAction, config: InputConfig): Boolean
    fun dispatch(action: MappedTargetAction, config: InputConfig, isRepeat: Boolean): Boolean =
        dispatch(action, config)
    fun dispatch(
        action: MappedTargetAction,
        config: InputConfig,
        isRepeat: Boolean,
        sourceButton: ClickButton?
    ): Boolean = dispatch(action, config, isRepeat)
}

data class MappingPreset(
    val id: String,
    val name: String,
    val ankiTarget: AnkiTarget,
    val bindings: Map<PresetBindingKey, MappedTargetAction>,
    val inputConfigs: Map<PresetBindingKey, InputConfig> = emptyMap()
)

data class AppConfiguration(
    val presets: List<MappingPreset> = emptyList(),
    val activePresetId: String? = null,
    val defaultPresetId: String? = null,
    val myWhooshLocation: MyWhooshLocation = MyWhooshLocation.ON_THIS_DEVICE,
    val podAssignment: PodAssignment = PodAssignment.SINGLE_CONTROLLER,
    val autoStartOnControllerWake: Boolean = true,
    val startMinimizedToTray: Boolean = false,
    // Optional Floating Mini HUD Pill (strictly OFF by default)
    val floatingHudPillEnabled: Boolean = false,
    // Optional Haptic & Audio Cues (strictly OFF by default)
    val hapticFeedbackEnabled: Boolean = false,
    val audioFeedbackEnabled: Boolean = false,
    // Desktop / LAN AnkiConnect target
    val ubuntuAnkiConnectHost: String = "127.0.0.1",
    val ubuntuAnkiConnectPort: Int = 8765,
    val leftPodAutoReconnectSec: Int = 55,
    val openBikeServerPort: Int = 21587,
    val useMyShiftTouchFallbackWhenNoClient: Boolean = false,
    val pinnedRightPodMac: String? = null,
    val pinnedLeftPodMac: String? = null
)

interface ClickBleController {
    val connectionState: StateFlow<BleConnectionState>
    val gestureEvents: SharedFlow<ButtonGestureEvent>
    fun startScanAndConnect(config: AppConfiguration)
    fun disconnect()
}

interface AnkiActionDispatcher {
    fun dispatch(action: AnkiAction): Boolean
}

interface MyWhooshActionDispatcher {
    suspend fun dispatch(action: MyWhooshAction, config: AppConfiguration): Boolean
}
