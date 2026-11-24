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
import com.ankiride.bridge.core.AnkiTarget
import com.ankiride.bridge.core.BleConnectionState
import com.ankiride.bridge.core.ButtonMappingStore
import com.ankiride.bridge.core.MyWhooshLocation
import com.ankiride.bridge.core.PresetStore
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
        presetName: String,
        ankiTarget: AnkiTarget
    ) {
        if (service == null || !enabled) {
            if (service != null) hide(service)
            return
        }
        mainHandler.post {
            if (pillContainer == null) {
                show(service, bleState, presetName, ankiTarget)
            } else {
                updateText(service, bleState, presetName, ankiTarget, lastActionLabel)
            }
        }
    }

    fun notifyActionDispatched(
        service: AccessibilityService?,
        enabled: Boolean,
        bleState: BleConnectionState,
        presetName: String,
        ankiTarget: AnkiTarget,
        actionText: String
    ) {
        lastActionLabel = actionText
        if (service == null || !enabled) return
        mainHandler.post {
            if (pillContainer == null) {
                show(service, bleState, presetName, ankiTarget)
            } else {
                updateText(service, bleState, presetName, ankiTarget, actionText)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show(
        service: AccessibilityService,
        bleState: BleConnectionState,
        presetName: String,
        ankiTarget: AnkiTarget
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

        val initialCfg = ButtonMappingStore.loadFullConfiguration(service)
        if (initialCfg.myWhooshLocation == MyWhooshLocation.ON_THIS_DEVICE && initialCfg.useMyShiftTouchFallbackWhenNoClient) {
            val calibrateTv = TextView(service).apply {
                text = "  🎯"
                textSize = 13f
                setOnClickListener {
                    MyShiftOverlayCalibrator.showCalibrationOverlay(service)
                }
            }
            layout.addView(calibrateTv)
        }

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
            x = (dm.widthPixels * 0.35f).roundToInt()
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
                        val curCfg = ButtonMappingStore.loadFullConfiguration(service)
                        val presets = curCfg.presets
                        if (presets.size > 1) {
                            val currentIdx = presets.indexOfFirst { it.id == curCfg.activePresetId }.coerceAtLeast(0)
                            val nextPreset = presets[(currentIdx + 1) % presets.size]
                            val updated = curCfg.copy(activePresetId = nextPreset.id)
                            ButtonMappingStore.saveFullConfiguration(service, updated)
                            BridgeForegroundService.startService(service, updated)
                            updateText(service, bleState, nextPreset.name, nextPreset.ankiTarget, "Preset -> ${nextPreset.name}")
                        }
                    }
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(layout, lp)
            pillContainer = layout
            updateText(service, bleState, presetName, ankiTarget, lastActionLabel)
        } catch (_: Exception) {
        }
    }

    private fun updateText(
        service: AccessibilityService,
        bleState: BleConnectionState,
        presetName: String,
        ankiTarget: AnkiTarget,
        actionLabel: String
    ) {
        val dot = when (bleState) {
            BleConnectionState.CONNECTED_RIGHT_ONLY,
            BleConnectionState.CONNECTED_LEFT_ONLY,
            BleConnectionState.CONNECTED_BOTH_PODS -> "🟢"
            BleConnectionState.SCANNING,
            BleConnectionState.CONNECTING,
            BleConnectionState.HANDSHAKING -> "🟡"
            else -> "🔴"
        }
        val dueTotal = if (ankiTarget == AnkiTarget.ANKIDROID) {
            try {
                AnkiContentProviderHelper.queryReviewInfo(service).sumOf { it.totalDue }
            } catch (_: Exception) {
                0
            }
        } else {
            0
        }
        val dueSuffix = if (dueTotal > 0) " • Due:$dueTotal" else ""
        statusTextView?.text = "$dot ${presetName.ifBlank { "Default" }}$dueSuffix • $actionLabel"
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
