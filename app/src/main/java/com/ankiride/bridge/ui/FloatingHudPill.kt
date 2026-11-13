package com.ankiride.bridge.ui

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ankiride.bridge.anki.AnkiContentProviderHelper
import com.ankiride.bridge.core.BleConnectionState
import com.ankiride.bridge.core.ButtonMappingStore
import com.ankiride.bridge.core.OperatingMode
import com.ankiride.bridge.mywhoosh.MyShiftOverlayCalibrator
import com.ankiride.bridge.service.BridgeForegroundService
import kotlin.math.abs
import kotlin.math.roundToInt

object FloatingHudPill {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pillContainer: View? = null
    private var statusTextView: TextView? = null
    private var lastActionLabel: String = "Ready"

    fun syncVisibility(
        service: AccessibilityService?,
        enabled: Boolean,
        bleState: BleConnectionState,
        mode: OperatingMode
    ) {
        if (service == null || !enabled) {
            if (service != null) hide(service)
            return
        }
        mainHandler.post {
            if (pillContainer == null) {
                show(service, bleState, mode)
            } else {
                updateText(service, bleState, mode, lastActionLabel)
            }
        }
    }

    fun notifyActionDispatched(
        service: AccessibilityService?,
        enabled: Boolean,
        bleState: BleConnectionState,
        mode: OperatingMode,
        actionText: String
    ) {
        lastActionLabel = actionText
        if (service == null || !enabled) return
        mainHandler.post {
            if (pillContainer == null) {
                show(service, bleState, mode)
            } else {
                updateText(service, bleState, mode, actionText)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(
        service: AccessibilityService,
        bleState: BleConnectionState,
        mode: OperatingMode
    ) {
        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        removeExistingOverlay(wm)
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)

        val layout = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (12 * dm.density).roundToInt()
            val padV = (6 * dm.density).roundToInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * dm.density
                setColor(Color.parseColor("#DD182A33"))
                setStroke((1.5f * dm.density).roundToInt(), Color.parseColor("#70D8C8"))
            }
        }

        val infoTv = TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
        }
        statusTextView = infoTv
        layout.addView(infoTv)

        val calibrateTv = TextView(service).apply {
            text = "  🎯"
            textSize = 13f
            setOnClickListener {
                val cfg = ButtonMappingStore.loadFullConfiguration(service)
                MyShiftOverlayCalibrator.showCalibrationOverlay(service, cfg.mode)
            }
        }
        layout.addView(calibrateTv)

        val closeTv = TextView(service).apply {
            text = "  ✕"
            setTextColor(Color.parseColor("#FF7B72"))
            textSize = 13f
            setOnClickListener {
                val cfg = ButtonMappingStore.loadFullConfiguration(service)
                ButtonMappingStore.saveFullConfiguration(service, cfg.copy(floatingHudPillEnabled = false))
                hide(service)
            }
        }
        layout.addView(closeTv)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (dm.widthPixels * 0.38f).roundToInt()
            y = (18 * dm.density).roundToInt()
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragged = false

        layout.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > 8f || abs(dy) > 8f) dragged = true
                    lp.x = (startX + dx).roundToInt()
                    lp.y = (startY + dy).roundToInt()
                    wm.updateViewLayout(v, lp)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        // Tap pill body to cycle OperatingMode on the fly
                        val curCfg = ButtonMappingStore.loadFullConfiguration(service)
                        val modes = OperatingMode.entries
                        val nextMode = modes[(modes.indexOf(curCfg.mode) + 1) % modes.size]
                        val updated = curCfg.copy(mode = nextMode)
                        ButtonMappingStore.saveFullConfiguration(service, updated)
                        BridgeForegroundService.startService(service, updated)
                        updateText(service, bleState, nextMode, "Mode -> ${nextMode.shortTitle}")
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(layout, lp)
            pillContainer = layout
            updateText(service, bleState, mode, lastActionLabel)
        } catch (_: Exception) {
        }
    }

    private fun updateText(
        service: AccessibilityService,
        bleState: BleConnectionState,
        mode: OperatingMode,
        actionLabel: String
    ) {
        val dot = when (bleState) {
            BleConnectionState.CONNECTED_RIGHT_ONLY, BleConnectionState.CONNECTED_BOTH_PODS -> "🟢"
            BleConnectionState.SCANNING, BleConnectionState.CONNECTING, BleConnectionState.HANDSHAKING -> "🟡"
            else -> "🔴"
        }
        val dueTotal = try {
            AnkiContentProviderHelper.queryReviewInfo(service).sumOf { it.totalDue }
        } catch (_: Exception) {
            0
        }
        val dueSuffix = if (dueTotal > 0) " • Due:$dueTotal" else ""
        statusTextView?.text = "$dot ${mode.shortTitle}$dueSuffix • $actionLabel"
    }

    fun hide(service: AccessibilityService) {
        mainHandler.post {
            val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
            removeExistingOverlay(wm)
        }
    }

    private fun removeExistingOverlay(wm: WindowManager) {
        pillContainer?.let { view ->
            try {
                wm.removeView(view)
            } catch (_: Exception) {
            }
        }
        pillContainer = null
        statusTextView = null
    }
}
