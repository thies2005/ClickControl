package com.ankiride.bridge.mywhoosh

import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.InputConfig
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MappedTargetAction
import com.ankiride.bridge.core.MyWhooshAction
import com.ankiride.bridge.core.PortableKey
import com.ankiride.bridge.service.DesktopGenericInputDispatcher
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

object DesktopMyWhooshRobot {

    private val isWayland: Boolean
        get() = System.getenv("XDG_SESSION_TYPE")?.equals("wayland", true) == true

    private val robot: Robot? = try {
        if (GraphicsEnvironment.isHeadless() || isWayland) null else Robot()
    } catch (_: Exception) {
        null
    }

    fun isAvailable(): Boolean = isWayland || robot != null

    fun performKeyboardShiftOrAction(action: MyWhooshAction): Boolean {
        val portableKey = when (action) {
            MyWhooshAction.SHIFT_UP -> PortableKey.I
            MyWhooshAction.SHIFT_DOWN -> PortableKey.K
            MyWhooshAction.STEER_LEFT -> PortableKey.A
            MyWhooshAction.STEER_RIGHT -> PortableKey.D
            MyWhooshAction.HUD_TOGGLE -> PortableKey.H
            MyWhooshAction.TOGGLE_CAMERA -> PortableKey.DIGIT_1
            MyWhooshAction.EMOTE -> PortableKey.E
            MyWhooshAction.UI_CONFIRM -> PortableKey.ENTER
            MyWhooshAction.UI_BACK -> PortableKey.ESCAPE
            else -> return false
        }
        if (isWayland) {
            return DesktopGenericInputDispatcher.dispatch(
                MappedTargetAction.KEYBOARD_INPUT,
                InputConfig(key = portableKey)
            )
        }
        val r = robot ?: return false
        val keyCode = when (action) {
            MyWhooshAction.SHIFT_UP -> KeyEvent.VK_I
            MyWhooshAction.SHIFT_DOWN -> KeyEvent.VK_K
            MyWhooshAction.STEER_LEFT -> KeyEvent.VK_A
            MyWhooshAction.STEER_RIGHT -> KeyEvent.VK_D
            MyWhooshAction.HUD_TOGGLE -> KeyEvent.VK_H
            MyWhooshAction.TOGGLE_CAMERA -> KeyEvent.VK_1
            MyWhooshAction.EMOTE -> KeyEvent.VK_E
            MyWhooshAction.UI_CONFIRM -> KeyEvent.VK_ENTER
            MyWhooshAction.UI_BACK -> KeyEvent.VK_ESCAPE
            else -> return false
        }
        return try {
            r.keyPress(keyCode)
            r.delay(45)
            r.keyRelease(keyCode)
            DiagnosticLogger.info(
                LogCategory.MYWHOOSH,
                "Desktop Robot sent key '${KeyEvent.getKeyText(keyCode)}' for $action"
            )
            true
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "Desktop Robot key injection failed: ${e.message}")
            false
        }
    }

    fun performScreenClickFallback(action: MyWhooshAction, config: AppConfiguration): Boolean {
        if (action != MyWhooshAction.SHIFT_UP && action != MyWhooshAction.SHIFT_DOWN) return false
        val (normX, normY) = if (action == MyWhooshAction.SHIFT_UP) {
            config.myShiftUpNormalizedX to config.myShiftUpNormalizedY
        } else {
            config.myShiftDownNormalizedX to config.myShiftDownNormalizedY
        }
        if (isWayland) {
            return DesktopGenericInputDispatcher.dispatch(
                MappedTargetAction.SCREEN_CLICK,
                InputConfig(left = (normX - 0.01f).coerceIn(0f, 0.98f), top = (normY - 0.01f).coerceIn(0f, 0.98f), width = 0.02f, height = 0.02f)
            )
        }
        val r = robot ?: return false
        return try {
            val screenSize = Toolkit.getDefaultToolkit().screenSize

            val targetX = (screenSize.width * normX).toInt()
            val targetY = (screenSize.height * normY).toInt()
            val previousMouse = MouseInfo.getPointerInfo()?.location

            r.mouseMove(targetX, targetY)
            r.mousePress(InputEvent.BUTTON1_DOWN_MASK)
            r.delay(50)
            r.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
            // Immediately restore user's cursor position
            if (previousMouse != null) {
                r.mouseMove(previousMouse.x, previousMouse.y)
            }
            DiagnosticLogger.info(LogCategory.MYWHOOSH, "Desktop Robot clicked ($targetX, $targetY) for $action")
            true
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "Desktop Robot click injection failed: ${e.message}")
            false
        }
    }

    fun inspectFocusedWindow(): String {
        return try {
            val os = System.getProperty("os.name", "").lowercase()
            if (isWayland) {
                "Window inspection unavailable on Wayland (xdotool is X11-only)"
            } else if (os.contains("linux")) {
                val proc = ProcessBuilder("xdotool", "getactivewindow", "getwindowname").start()
                val title = proc.inputStream.bufferedReader().readText().trim()
                if (proc.waitFor() == 0 && title.isNotEmpty()) {
                    title
                } else {
                    "Unknown Linux Window"
                }
            } else {
                "Platform window inspection not available on $os"
            }
        } catch (_: Exception) {
            "Unable to inspect focused window"
        }
    }
}
