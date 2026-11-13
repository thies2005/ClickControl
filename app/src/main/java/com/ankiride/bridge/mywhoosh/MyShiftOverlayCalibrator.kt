package com.ankiride.bridge.mywhoosh

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.OperatingMode

object MyShiftOverlayCalibrator {
    private const val PREFS_CALIBRATION = "myshift_calibration_profiles"
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null

    fun profilePrefix(context: Context, mode: OperatingMode): String {
        val dm = context.resources.displayMetrics
        val orientation = if (dm.widthPixels >= dm.heightPixels) "land" else "port"
        return "calib_${mode.name}_${orientation}_${dm.widthPixels}x${dm.heightPixels}"
    }

    fun loadCalibratedCoordinates(context: Context, mode: OperatingMode): FloatArray {
        val prefs = context.getSharedPreferences(PREFS_CALIBRATION, Context.MODE_PRIVATE)
        val prefix = profilePrefix(context, mode)
        val upX = prefs.getFloat("${prefix}_up_x", 0.92f)
        val upY = prefs.getFloat("${prefix}_up_y", 0.85f)
        val downX = prefs.getFloat("${prefix}_down_x", 0.84f)
        val downY = prefs.getFloat("${prefix}_down_y", 0.85f)
        return floatArrayOf(upX, upY, downX, downY)
    }

    fun saveCalibratedCoordinates(
        context: Context,
        mode: OperatingMode,
        upX: Float,
        upY: Float,
        downX: Float,
        downY: Float
    ) {
        val prefs = context.getSharedPreferences(PREFS_CALIBRATION, Context.MODE_PRIVATE)
        val prefix = profilePrefix(context, mode)
        prefs.edit()
            .putFloat("${prefix}_up_x", upX)
            .putFloat("${prefix}_up_y", upY)
            .putFloat("${prefix}_down_x", downX)
            .putFloat("${prefix}_down_y", downY)
            .apply()
    }

    fun showCalibrationOverlay(service: AccessibilityService, mode: OperatingMode) {
        val coords = loadCalibratedCoordinates(service, mode)
        showOverlay(
            service = service,
            initialUpX = coords[0],
            initialUpY = coords[1],
            initialDownX = coords[2],
            initialDownY = coords[3],
            onCoordinatesChanged = { upX, upY, downX, downY ->
                saveCalibratedCoordinates(service, mode, upX, upY, downX, downY)
            }
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay(
        service: AccessibilityService,
        initialUpX: Float = 0.92f,
        initialUpY: Float = 0.85f,
        initialDownX: Float = 0.84f,
        initialDownY: Float = 0.85f,
        onCoordinatesChanged: ((upX: Float, upY: Float, downX: Float, downY: Float) -> Unit)? = null
    ) {
        if (overlayView != null) return

        val wm = service.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        windowManager = wm

        val root = FrameLayout(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val closeBtn = TextView(service).apply {
            text = "✕ Close Calibrator"
            setBackgroundColor(0xDD000000.toInt())
            setTextColor(0xFFFF7B72.toInt())
            setPadding(24, 16, 24, 16)
            textSize = 13f
            setOnClickListener { hideOverlay() }
        }
        val closeParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = (72 * service.resources.displayMetrics.density).toInt()
        }
        root.addView(closeBtn, closeParams)

        // Shift Up Target
        val upTarget = TextView(service).apply {
            text = "Shift Up (+)\n[Drag to calibrate]"
            setBackgroundColor(0xCC1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
            textSize = 11f
        }

        // Shift Down Target
        val downTarget = TextView(service).apply {
            text = "Shift Down (–)\n[Drag to calibrate]"
            setBackgroundColor(0xCCE65100.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
            textSize = 11f
        }

        var curUpX = initialUpX
        var curUpY = initialUpY
        var curDownX = initialDownX
        var curDownY = initialDownY

        fun clampPosition(v: View, rawX: Float, rawY: Float) {
            val maxX = (root.width - v.width).coerceAtLeast(0).toFloat()
            val maxY = (root.height - v.height).coerceAtLeast(0).toFloat()
            v.x = rawX.coerceIn(0f, maxX)
            v.y = rawY.coerceIn(0f, maxY)
        }

        fun positionView(v: View, normX: Float, normY: Float) {
            val widthPx = if (root.width > 0) root.width else service.resources.displayMetrics.widthPixels
            val heightPx = if (root.height > 0) root.height else service.resources.displayMetrics.heightPixels
            val halfW = if (v.width > 0) v.width / 2f else 50f
            val halfH = if (v.height > 0) v.height / 2f else 50f
            clampPosition(v, (widthPx * normX) - halfW, (heightPx * normY) - halfH)
        }

        root.addView(upTarget, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        root.addView(downTarget, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))

        var initialPositioned = false
        var lastLayoutWidth = 0
        var lastLayoutHeight = 0
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (root.width <= 0 || upTarget.width <= 0 || downTarget.width <= 0) return@addOnLayoutChangeListener
            if (initialPositioned && lastLayoutWidth == root.width && lastLayoutHeight == root.height) return@addOnLayoutChangeListener
            initialPositioned = true
            lastLayoutWidth = root.width
            lastLayoutHeight = root.height
            positionView(upTarget, curUpX, curUpY)
            positionView(downTarget, curDownX, curDownY)
            // Keep default adjacent targets legible and non-overlapping in narrow portrait screens.
            val padPx = (12 * service.resources.displayMetrics.density)
            val overlapX = downTarget.x < upTarget.x + upTarget.width && downTarget.x + downTarget.width > upTarget.x
            val overlapY = downTarget.y < upTarget.y + upTarget.height && downTarget.y + downTarget.height > upTarget.y
            if (overlapX && overlapY) {
                val leftOfUp = upTarget.x - downTarget.width - padPx
                if (leftOfUp >= 0f) {
                    clampPosition(downTarget, leftOfUp, upTarget.y)
                } else {
                    clampPosition(downTarget, downTarget.x, upTarget.y - downTarget.height - padPx)
                }
            }
        }

        // Drag listener for Up target
        upTarget.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f
            var dY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        clampPosition(view, event.rawX + dX, event.rawY + dY)
                        val displayMetrics = service.resources.displayMetrics
                        curUpX = ((view.x + (view.width / 2f)) / displayMetrics.widthPixels).coerceIn(0f, 1f)
                        curUpY = ((view.y + (view.height / 2f)) / displayMetrics.heightPixels).coerceIn(0f, 1f)
                        onCoordinatesChanged?.invoke(curUpX, curUpY, curDownX, curDownY)
                    }
                    MotionEvent.ACTION_UP -> {
                        DiagnosticLogger.info(
                            LogCategory.MYWHOOSH,
                            "Calibrated Shift Up Target -> normalized=(${"%.3f".format(curUpX)}, ${"%.3f".format(curUpY)})"
                        )
                    }
                }
                return true
            }
        })

        // Drag listener for Down target
        downTarget.setOnTouchListener(object : View.OnTouchListener {
            var dX = 0f
            var dY = 0f
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = view.x - event.rawX
                        dY = view.y - event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        clampPosition(view, event.rawX + dX, event.rawY + dY)
                        val displayMetrics = service.resources.displayMetrics
                        curDownX = ((view.x + (view.width / 2f)) / displayMetrics.widthPixels).coerceIn(0f, 1f)
                        curDownY = ((view.y + (view.height / 2f)) / displayMetrics.heightPixels).coerceIn(0f, 1f)
                        onCoordinatesChanged?.invoke(curUpX, curUpY, curDownX, curDownY)
                    }
                    MotionEvent.ACTION_UP -> {
                        DiagnosticLogger.info(
                            LogCategory.MYWHOOSH,
                            "Calibrated Shift Down Target -> normalized=(${"%.3f".format(curDownX)}, ${"%.3f".format(curDownY)})"
                        )
                    }
                }
                return true
            }
        })

        try {
            wm.addView(root, params)
            overlayView = root
            DiagnosticLogger.info(LogCategory.MYWHOOSH, "MyShiftOverlayCalibrator overlay shown (TYPE_ACCESSIBILITY_OVERLAY)")
        } catch (e: Exception) {
            DiagnosticLogger.error(LogCategory.MYWHOOSH, "Failed to show calibrator overlay: ${e.message}")
        }
    }

    fun hideOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                DiagnosticLogger.info(LogCategory.MYWHOOSH, "MyShiftOverlayCalibrator overlay hidden")
            } catch (_: Exception) {
            }
        }
        overlayView = null
        windowManager = null
    }

    fun hide(service: AccessibilityService? = null) {
        hideOverlay()
    }

    fun isOverlayShowing(): Boolean = overlayView != null
}
