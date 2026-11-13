package com.ankiride.bridge.service

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.awt.Rectangle
import java.awt.event.KeyEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit

/** Runs only fixed commands and arguments; never invokes a shell. */
internal interface InputCommandRunner {
    fun run(args: List<String>, environment: Map<String, String> = emptyMap(), timeoutMs: Long = 3000): String
    fun start(args: List<String>): Process
}

internal object SystemInputCommandRunner : InputCommandRunner {
    override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long): String {
        val builder = ProcessBuilder(args).redirectErrorStream(true)
        builder.environment().putAll(environment)
        val process = builder.start()
        val output = java.io.ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val remaining = 4096 - output.size()
                    if (remaining > 0) output.write(buffer, 0, minOf(count, remaining))
                }
            }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS)
            reader.join(1000)
            error("${args.first()} timed out")
        }
        reader.join(1000)
        val result = output.toString(Charsets.UTF_8).trim()
        check(process.exitValue() == 0) { "${args.first()} failed: $result" }
        return result
    }

    override fun start(args: List<String>): Process = ProcessBuilder(args)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
}

// Linux input-event-codes.h; explicit mapping avoids assuming AWT and evdev values coincide.
internal val evdevKeys = mapOf(
    KeyEvent.VK_A to 30, KeyEvent.VK_B to 48, KeyEvent.VK_C to 46, KeyEvent.VK_D to 32,
    KeyEvent.VK_E to 18, KeyEvent.VK_F to 33, KeyEvent.VK_G to 34, KeyEvent.VK_H to 35,
    KeyEvent.VK_I to 23, KeyEvent.VK_J to 36, KeyEvent.VK_K to 37, KeyEvent.VK_L to 38,
    KeyEvent.VK_M to 50, KeyEvent.VK_N to 49, KeyEvent.VK_O to 24, KeyEvent.VK_P to 25,
    KeyEvent.VK_Q to 16, KeyEvent.VK_R to 19, KeyEvent.VK_S to 31, KeyEvent.VK_T to 20,
    KeyEvent.VK_U to 22, KeyEvent.VK_V to 47, KeyEvent.VK_W to 17, KeyEvent.VK_X to 45,
    KeyEvent.VK_Y to 21, KeyEvent.VK_Z to 44, KeyEvent.VK_0 to 11, KeyEvent.VK_1 to 2,
    KeyEvent.VK_2 to 3, KeyEvent.VK_3 to 4, KeyEvent.VK_4 to 5, KeyEvent.VK_5 to 6,
    KeyEvent.VK_6 to 7, KeyEvent.VK_7 to 8, KeyEvent.VK_8 to 9, KeyEvent.VK_9 to 10,
    KeyEvent.VK_SPACE to 57, KeyEvent.VK_ENTER to 28, KeyEvent.VK_TAB to 15,
    KeyEvent.VK_ESCAPE to 1, KeyEvent.VK_UP to 103, KeyEvent.VK_DOWN to 108,
    KeyEvent.VK_LEFT to 105, KeyEvent.VK_RIGHT to 106, KeyEvent.VK_BACK_SPACE to 14,
    KeyEvent.VK_DELETE to 111, KeyEvent.VK_F1 to 59, KeyEvent.VK_F2 to 60,
    KeyEvent.VK_F3 to 61, KeyEvent.VK_F4 to 62, KeyEvent.VK_F5 to 63, KeyEvent.VK_F6 to 64,
    KeyEvent.VK_F7 to 65, KeyEvent.VK_F8 to 66, KeyEvent.VK_F9 to 67, KeyEvent.VK_F10 to 68,
    KeyEvent.VK_F11 to 87, KeyEvent.VK_F12 to 88, KeyEvent.VK_CONTROL to 29,
    KeyEvent.VK_ALT to 56, KeyEvent.VK_SHIFT to 42, KeyEvent.VK_META to 125,
    KeyEvent.VK_PAGE_UP to 104, KeyEvent.VK_PAGE_DOWN to 109,
    KeyEvent.VK_HOME to 102, KeyEvent.VK_END to 107
)

internal class WaylandInputBackend(
    private val runner: InputCommandRunner = SystemInputCommandRunner,
    private val environment: Map<String, String> = System.getenv(),
    private val deviceWritable: () -> Boolean = { Files.isWritable(Path.of("/dev/uinput")) }
) : InputDriver, AutoCloseable {
    private var child: Process? = null
    private var directory: Path? = null
    private var socket: String? = null
    var status: String by mutableStateOf("Wayland input has not been initialized")
        private set

    fun reportFailure(e: Exception) { status = "Wayland input unavailable: ${e.message}" }
    @Synchronized fun initialize() {
        if (socket != null && (child == null || child!!.isAlive)) return
        close()
        val configured = environment["YDOTOOL_SOCKET"]?.takeIf { it.isNotBlank() }
        if (configured != null) {
            check(probe(configured)) { "Configured YDOTOOL_SOCKET is unavailable; start its daemon or clear YDOTOOL_SOCKET" }
            socket = configured; status = "Wayland input ready (configured ydotool socket)"; return
        }
        check(deviceWritable()) { "Wayland input requires ydotoold and write access to /dev/uinput" }
        val runtime = environment["XDG_RUNTIME_DIR"] ?: error("XDG_RUNTIME_DIR is not set")
        val dir = Files.createTempDirectory(Path.of(runtime), "clickcontrol-input-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        directory = dir
        val path = dir.resolve("socket").toString()
        try {
            child = try {
                runner.start(listOf("ydotoold", "--socket-path", path, "--socket-perm", "0600"))
            } catch (e: java.io.IOException) {
                throw IllegalStateException("Wayland input requires ydotool/ydotoold installed on PATH (${e.message})", e)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < deadline && child!!.isAlive && !probe(path)) Thread.sleep(50)
            check(child!!.isAlive && probe(path)) { "ydotoold did not become ready" }
            // The compositor discovers the uinput device asynchronously after the socket is ready.
            Thread.sleep(800)
            socket = path
            status = "Wayland input ready (private ydotoold)"
        } catch (e: Exception) { close(); throw e }
    }
    private fun probe(path: String): Boolean = try {
        runner.run(listOf("ydotool", "key"), mapOf("YDOTOOL_SOCKET" to path), 500); true
    } catch (_: Exception) { false }
    private fun command(vararg args: String, valid: () -> Boolean = { true }) {
        try { initialize() } catch (e: Exception) { status = "Wayland input unavailable: ${e.message}"; throw e }
        if (!valid()) throw java.util.concurrent.CancellationException("Input cancelled")
        try { runner.run(listOf("ydotool") + args, mapOf("YDOTOOL_SOCKET" to socket!!)) }
        catch (e: Exception) { status = "Wayland input failed: ${e.message}"; throw e }
    }
    override fun move(x: Int, y: Int) = moveChecked(x, y) { true }
    fun moveChecked(x: Int, y: Int, valid: () -> Boolean) {
        val gain = waylandPointerGain(runner, environment["XDG_CURRENT_DESKTOP"])
        val scaledX = kotlin.math.round(x / gain).toLong()
        val scaledY = kotlin.math.round(y / gain).toLong()
        require(scaledX in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() && scaledY in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Pointer coordinates exceed the supported range" }
        command("mousemove", "--absolute", scaledX.toString(), scaledY.toString(), valid = valid)
    }
    override fun mousePress() = mousePressChecked { true }
    fun mousePressChecked(valid: () -> Boolean) = command("click", "0x40", valid = valid)
    override fun mouseRelease() = command("click", "0x80")
    override fun mouseWheel(horizontal: Int, vertical: Int) = mouseWheelChecked(horizontal, vertical) { true }
    fun mouseWheelChecked(horizontal: Int, vertical: Int, valid: () -> Boolean) =
        command("mousemove", "-w", "--", horizontal.toString(), vertical.toString(), valid = valid)
    override fun keyPress(code: Int) = keyPressChecked(code) { true }
    fun keyPressChecked(code: Int, valid: () -> Boolean) = command("key", "${evdevKeys[code] ?: error("Unsupported key $code")}:1", valid = valid)
    override fun keyRelease(code: Int) = command("key", "${evdevKeys[code] ?: error("Unsupported key $code")}:0")
    @Synchronized override fun close() {
        child?.let { it.destroy(); if (!it.waitFor(1, TimeUnit.SECONDS)) it.destroyForcibly() }
        child = null; socket = null
        directory?.let { Files.deleteIfExists(it.resolve("socket")); Files.deleteIfExists(it) }
        directory = null
    }
}

/** GDK reports Wayland logical coordinates, unlike XWayland's AWT pixel dimensions. */
internal fun waylandBounds(runner: InputCommandRunner = SystemInputCommandRunner, desktop: String? = System.getenv("XDG_CURRENT_DESKTOP")): Rectangle {
    check(desktop?.contains("GNOME", ignoreCase = true) == true) { "Wayland fixed-area clicks currently require GNOME" }
    run {
        val profile = runner.run(listOf("gsettings", "get", "org.gnome.desktop.peripherals.mouse", "accel-profile")).trim().trim('\'')
        check(profile == "flat") { "Wayland screen clicks require the GNOME mouse acceleration profile set to flat (current: $profile)" }
    }
    val script = """import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, Gdk
Gtk.init()
d = Gdk.Display.get_default()
if d is None: raise RuntimeError('No Wayland display')
m = d.get_monitors()
if m.get_n_items() != 1: raise RuntimeError('Wayland screen clicks currently require one monitor')
r = m.get_item(0).get_geometry()
print(r.x, r.y, r.width, r.height)
"""
    val values = runner.run(listOf("/usr/bin/python3", "-c", script), mapOf("GDK_BACKEND" to "wayland")).split(Regex("\\s+"))
    require(values.size == 4) { "Cannot determine Wayland display geometry" }
    return Rectangle(values[0].toInt(), values[1].toInt(), values[2].toInt(), values[3].toInt())
}

/** GNOME's flat libinput profile multiplies relative motion by (1 + speed). */
internal fun waylandPointerGain(runner: InputCommandRunner, desktop: String?): Double {
    if (desktop?.contains("GNOME", ignoreCase = true) != true) return 1.0
    val raw = runner.run(listOf("gsettings", "get", "org.gnome.desktop.peripherals.mouse", "speed")).trim()
    val speed = raw.toDoubleOrNull() ?: error("Cannot read GNOME mouse speed: $raw")
    require(speed.isFinite() && speed in -1.0..1.0 && 1.0 + speed >= 0.01) { "GNOME mouse speed is invalid or too slow for screen clicks: $raw" }
    return 1.0 + speed
}
