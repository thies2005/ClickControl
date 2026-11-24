package com.ankiride.bridge.ui

import com.ankiride.bridge.core.BleConnectionState
import com.ankiride.bridge.core.DiagnosticLogger
import com.ankiride.bridge.core.LogCategory
import com.ankiride.bridge.core.MappingPreset
import java.awt.BasicStroke
import java.awt.Color
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

internal data class TraySnapshot(
    val running: Boolean,
    val bleState: BleConnectionState,
    val activePresetId: String,
    val hudEnabled: Boolean,
    val presets: List<Pair<String, String>>
) {
    val isConnected: Boolean
        get() = bleState == BleConnectionState.CONNECTED_RIGHT_ONLY ||
            bleState == BleConnectionState.CONNECTED_LEFT_ONLY ||
            bleState == BleConnectionState.CONNECTED_BOTH_PODS

    val bleLabel: String
        get() = when {
            !running -> "Stopped"
            bleState == BleConnectionState.CONNECTED_BOTH_PODS -> "Both connected"
            bleState == BleConnectionState.CONNECTED_RIGHT_ONLY -> "Right connected"
            bleState == BleConnectionState.CONNECTED_LEFT_ONLY -> "Left connected"
            bleState == BleConnectionState.HANDSHAKING || bleState == BleConnectionState.CONNECTING -> "Connecting"
            bleState == BleConnectionState.ERROR -> "Error"
            else -> "Scanning"
        }

    fun toJson(): String {
        val presetsJson = presets.joinToString(",") { (id, name) ->
            "{\"id\":\"${escapeJson(id)}\",\"name\":\"${escapeJson(name)}\"}"
        }
        return "{\"running\":$running,\"connected\":$isConnected,\"bleLabel\":\"${escapeJson(bleLabel)}\"," +
            "\"activePresetId\":\"${escapeJson(activePresetId)}\",\"hudEnabled\":$hudEnabled,\"presets\":[$presetsJson]}"
    }

    private fun escapeJson(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
}

internal class DesktopTrayController(
    private val onShowWindow: () -> Unit,
    private val onToggleBridge: () -> Unit,
    private val onSelectPreset: (String) -> Unit,
    private val onToggleHud: () -> Unit,
    private val onQuit: () -> Unit
) : AutoCloseable {

    private var sniProcess: Process? = null
    private var sniWriter: BufferedWriter? = null
    private var sniScriptFile: File? = null
    private var awtTrayIcon: TrayIcon? = null
    private var lastSnapshot: TraySnapshot? = null
    @Volatile var isActive: Boolean = false
        private set

    fun start(initial: TraySnapshot) {
        lastSnapshot = initial
        if (tryStartLinuxSni(initial)) {
            isActive = true
            return
        }
        if (tryStartAwtTray(initial)) {
            isActive = true
        }
    }

    fun update(snapshot: TraySnapshot) {
        if (snapshot == lastSnapshot) return
        lastSnapshot = snapshot
        sniWriter?.let { writer ->
            try {
                writer.write(snapshot.toJson())
                writer.newLine()
                writer.flush()
            } catch (_: Exception) {
            }
        }
        awtTrayIcon?.let { icon ->
            SwingUtilities.invokeLater {
                applyAwtSnapshot(icon, snapshot)
            }
        }
    }

    private fun tryStartLinuxSni(initial: TraySnapshot): Boolean {
        val osName = System.getProperty("os.name", "").lowercase()
        if (!osName.contains("linux")) return false
        val python = listOf("/usr/bin/python3", "python3").firstOrNull { candidate ->
            candidate.startsWith("/") && File(candidate).canExecute() || !candidate.startsWith("/")
        } ?: return false
        val stream = javaClass.getResourceAsStream("/tray/linux_sni_tray.py") ?: return false

        return try {
            val tempScript = Files.createTempFile("clickcontrol-tray-", ".py").toFile().apply {
                deleteOnExit()
                outputStream().use { out -> stream.copyTo(out) }
            }
            sniScriptFile = tempScript
            val proc = ProcessBuilder(python, tempScript.absolutePath)
                .redirectErrorStream(false)
                .start()
            val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
            val readyLine = reader.readLine()
            if (readyLine != "READY") {
                proc.destroyForcibly()
                tempScript.delete()
                return false
            }
            sniProcess = proc
            sniWriter = writer
            writer.write(initial.toJson())
            writer.newLine()
            writer.flush()

            Thread {
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val cmd = line?.trim().orEmpty()
                        if (cmd.startsWith("ACTION ")) {
                            handleAction(cmd.removePrefix("ACTION ").trim())
                        }
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true; name = "ClickControl-SNI-Tray"; start() }
            true
        } catch (e: Exception) {
            DiagnosticLogger.warn(LogCategory.SYSTEM, "SNI tray unavailable, falling back: ${e.message}")
            false
        }
    }

    private fun tryStartAwtTray(initial: TraySnapshot): Boolean {
        if (!SystemTray.isSupported()) return false
        return try {
            val tray = SystemTray.getSystemTray()
            val iconSize = tray.trayIconSize
            val dim = minOf(iconSize.width, iconSize.height).coerceIn(16, 128)
            val icon = TrayIcon(renderCrispTrayImage(dim, initial), "ClickControl", PopupMenu()).apply {
                isImageAutoSize = true
                addActionListener { SwingUtilities.invokeLater { onShowWindow() } }
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.button == MouseEvent.BUTTON1) {
                            SwingUtilities.invokeLater { onShowWindow() }
                        }
                    }
                })
            }
            applyAwtSnapshot(icon, initial)
            tray.add(icon)
            awtTrayIcon = icon
            true
        } catch (e: Exception) {
            DiagnosticLogger.warn(LogCategory.SYSTEM, "AWT system tray unavailable: ${e.message}")
            false
        }
    }

    private fun applyAwtSnapshot(icon: TrayIcon, snapshot: TraySnapshot) {
        val iconSize = SystemTray.getSystemTray().trayIconSize
        val dim = minOf(iconSize.width, iconSize.height).coerceIn(16, 128)
        icon.image = renderCrispTrayImage(dim, snapshot)
        icon.toolTip = "ClickControl — ${snapshot.bleLabel}"
        val popup = PopupMenu()
        popup.add(MenuItem("Open ClickControl").apply { addActionListener { SwingUtilities.invokeLater { onShowWindow() } } })
        popup.add(MenuItem(if (snapshot.running) "Stop Bridge (${snapshot.bleLabel})" else "Start Bridge").apply {
            addActionListener { SwingUtilities.invokeLater { onToggleBridge() } }
        })
        if (snapshot.presets.isNotEmpty()) {
            popup.addSeparator()
            snapshot.presets.forEach { (id, name) ->
                val mark = if (id == snapshot.activePresetId) "● " else "○ "
                popup.add(MenuItem("$mark$name").apply {
                    addActionListener { SwingUtilities.invokeLater { onSelectPreset(id) } }
                })
            }
        }
        popup.addSeparator()
        popup.add(MenuItem(if (snapshot.hudEnabled) "Hide Floating HUD" else "Show Floating HUD").apply {
            addActionListener { SwingUtilities.invokeLater { onToggleHud() } }
        })
        popup.addSeparator()
        popup.add(MenuItem("Quit ClickControl").apply { addActionListener { SwingUtilities.invokeLater { onQuit() } } })
        icon.popupMenu = popup
    }

    private fun handleAction(action: String) {
        when {
            action == "show" -> SwingUtilities.invokeLater { onShowWindow() }
            action == "toggle_bridge" -> SwingUtilities.invokeLater { onToggleBridge() }
            action == "toggle_hud" -> SwingUtilities.invokeLater { onToggleHud() }
            action == "quit" -> SwingUtilities.invokeLater { onQuit() }
            action.startsWith("select_preset ") -> {
                val id = action.removePrefix("select_preset ").trim()
                if (id.isNotEmpty()) SwingUtilities.invokeLater { onSelectPreset(id) }
            }
        }
    }

    override fun close() {
        isActive = false
        try {
            sniWriter?.close()
        } catch (_: Exception) {
        }
        sniProcess?.let { proc ->
            proc.destroy()
            if (!proc.waitFor(1, TimeUnit.SECONDS)) proc.destroyForcibly()
        }
        sniProcess = null
        sniScriptFile?.delete()
        awtTrayIcon?.let { icon ->
            try {
                SystemTray.getSystemTray().remove(icon)
            } catch (_: Exception) {
            }
        }
        awtTrayIcon = null
    }

    companion object {
        fun renderCrispTrayImage(size: Int, snapshot: TraySnapshot): BufferedImage {
            val s = size.coerceAtLeast(16)
            val img = BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g.scale(s / 24.0, s / 24.0)
                val pad = Path2D.Double().apply {
                    moveTo(9.0, 3.0)
                    lineTo(15.0, 3.0)
                    lineTo(15.0, 9.0)
                    lineTo(21.0, 9.0)
                    lineTo(21.0, 15.0)
                    lineTo(15.0, 15.0)
                    lineTo(15.0, 21.0)
                    lineTo(9.0, 21.0)
                    lineTo(9.0, 15.0)
                    lineTo(3.0, 15.0)
                    lineTo(3.0, 9.0)
                    lineTo(9.0, 9.0)
                    closePath()
                }
                val outline = Color(0x27, 0x2C, 0x33)
                g.color = when {
                    !snapshot.running -> Color(0xCF, 0xD5, 0xDE)
                    snapshot.isConnected -> Color(0xF6, 0xF8, 0xFC)
                    else -> Color(0xFF, 0xC4, 0x76)
                }
                g.fill(pad)
                g.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = outline
                g.draw(pad)
                g.fill(Ellipse2D.Double(10.4, 10.4, 3.2, 3.2))

                if (snapshot.running && snapshot.isConnected) {
                    val badge = Ellipse2D.Double(17.0, 17.0, 5.0, 5.0)
                    g.color = Color(0x2E, 0xC2, 0x7E)
                    g.fill(badge)
                    g.color = outline
                    g.draw(badge)
                }
            } finally {
                g.dispose()
            }
            return img
        }
    }
}
