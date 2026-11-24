package com.ankiride.bridge.mywhoosh

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import com.ankiride.bridge.core.AppConfiguration
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MyWhooshAction

object MyShiftGestureFallback {
    const val MYWHOOSH_PACKAGE = "com.mywhoosh.whooshgame"
    const val TAP_DURATION_MS = 75L

    fun performTouchAction(
        service: AccessibilityService,
        action: MyWhooshAction,
        config: AppConfiguration
    ): Boolean {
        if (action != MyWhooshAction.SHIFT_UP && action != MyWhooshAction.SHIFT_DOWN) {
            return false
        }

        val myWhooshWindow = service.windows?.firstOrNull {
            it.root?.packageName?.toString() == MYWHOOSH_PACKAGE
        } ?: return false

        val bounds = Rect()
        myWhooshWindow.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val profileCoords = MyShiftOverlayCalibrator.loadCalibratedCoordinates(service)
        val (normX, normY) = if (action == MyWhooshAction.SHIFT_UP) {
            profileCoords[0] to profileCoords[1]
        } else {
            profileCoords[2] to profileCoords[3]
        }

        val tapX = bounds.left + (bounds.width() * normX)
        val tapY = bounds.top + (bounds.height() * normY)

        val path = Path().apply {
            moveTo(tapX, tapY)
            lineTo(tapX, tapY)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return try {
            val dispatched = service.dispatchGesture(gesture, null, null)
            DiagnosticLogger.info(
                LogCategory.MYWHOOSH,
                "MyShift Touch Tap for $action at (${tapX.toInt()}, ${tapY.toInt()}) in window=$bounds -> dispatched=$dispatched"
            )
            dispatched
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "MyShift Touch Tap failed: ${e.message}")
            false
        }
    }
}
