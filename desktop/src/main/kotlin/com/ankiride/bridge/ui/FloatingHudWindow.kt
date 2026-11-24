package com.ankiride.bridge.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.ankiride.bridge.core.BleConnectionState

/**
 * Cross-platform Floating Mini HUD Pill for Compose Desktop (Ubuntu, macOS, Windows).
 * Renders an always-on-top, undecorated, non-focusable window showing active preset & last action.
 */
@Composable
fun FloatingHudDesktopWindow(
    enabled: Boolean,
    bleState: BleConnectionState,
    presetName: String,
    lastActionText: String,
    onClose: () -> Unit
) {
    if (!enabled) return

    val windowState = rememberWindowState(size = DpSize(340.dp, 48.dp))
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "ClickControl Status",
        undecorated = true,
        transparent = true,
        alwaysOnTop = true,
        focusable = false,
        resizable = false
    ) {
        val dotColor = when (bleState) {
            BleConnectionState.CONNECTED_RIGHT_ONLY,
            BleConnectionState.CONNECTED_LEFT_ONLY,
            BleConnectionState.CONNECTED_BOTH_PODS -> UbuntuColors.Success
            BleConnectionState.SCANNING,
            BleConnectionState.CONNECTING,
            BleConnectionState.HANDSHAKING -> UbuntuColors.Warning
            else -> UbuntuColors.Error
        }
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xEE242424),
            border = BorderStroke(1.dp, UbuntuColors.Border)
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(dotColor))
                Text(
                    text = presetName.ifBlank { "Default" },
                    color = UbuntuColors.TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = lastActionText.ifBlank { "Ready" },
                    color = UbuntuColors.YaruOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
