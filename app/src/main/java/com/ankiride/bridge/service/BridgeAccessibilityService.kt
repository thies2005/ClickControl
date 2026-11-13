package com.ankiride.bridge.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.ankiride.bridge.core.ButtonMappingStore
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.ui.FloatingHudPill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BridgeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BridgeA11yService"
        var activeInstance: BridgeAccessibilityService? = null
            private set

        private val _isConnectedFlow = MutableStateFlow(false)
        val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "BridgeAccessibilityService connected")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            serviceInfo = serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_INPUT_METHOD_EDITOR }
        }
        activeInstance = this
        _isConnectedFlow.value = true
        val cfg = ButtonMappingStore.loadFullConfiguration(this)
        FloatingHudPill.syncVisibility(this, cfg.floatingHudPillEnabled, BridgeForegroundService.connectionStateFlow.value, cfg.mode)
        DiagnosticLogger.info(LogCategory.PERMS, "BridgeAccessibilityService connected and active")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Window events handled directly on demand via service.windows inspection
    }

    override fun onInterrupt() {
        Log.w(TAG, "BridgeAccessibilityService interrupted")
        DiagnosticLogger.warn(LogCategory.PERMS, "BridgeAccessibilityService interrupted")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Security Gate SG-2 check: reject all external onStartCommand triggers
        Log.w(TAG, "Rejected external onStartCommand trigger")
        DiagnosticLogger.warn(LogCategory.PERMS, "Rejected external onStartCommand trigger")
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        com.ankiride.bridge.ui.FloatingHudPill.hide(this)
        com.ankiride.bridge.mywhoosh.MyShiftOverlayCalibrator.hide(this)
        if (activeInstance == this) {
            activeInstance = null
            _isConnectedFlow.value = false
            DiagnosticLogger.warn(LogCategory.PERMS, "BridgeAccessibilityService unbound")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        com.ankiride.bridge.ui.FloatingHudPill.hide(this)
        com.ankiride.bridge.mywhoosh.MyShiftOverlayCalibrator.hide(this)
        if (activeInstance == this) {
            activeInstance = null
            _isConnectedFlow.value = false
        }
        super.onDestroy()
    }
}
