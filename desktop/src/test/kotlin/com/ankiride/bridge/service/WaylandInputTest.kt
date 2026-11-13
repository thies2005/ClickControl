package com.ankiride.bridge.service

import com.ankiride.bridge.core.*
import org.junit.Assert.*
import org.junit.Test
import java.awt.Rectangle

class WaylandInputTest {
    private class Runner(var fail: Boolean = false) : InputCommandRunner {
        val calls = mutableListOf<List<String>>()
        var starts = 0
        override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long): String {
            calls.add(args)
            if (fail) error("unavailable")
            return ""
        }
        override fun start(args: List<String>): Process { starts++; error("unexpected daemon") }
    }
    @Test fun everyPortableKeyAndModifierHasEvdevMapping() {
        val runner = Runner()
        val backend = WaylandInputBackend(runner, mapOf("YDOTOOL_SOCKET" to "/external")) { false }
        for (key in PortableKey.entries) performInput(backend, MappedTargetAction.KEYBOARD_INPUT,
            InputConfig(key = key, ctrl = true, alt = true, shift = true, meta = true), Rectangle(1, 1))
        backend.close()
        assertEquals(0, runner.starts)
        assertTrue(runner.calls.any { it.contains("30:1") })
        assertTrue(runner.calls.any { it.contains("125:0") })
    }
    @Test fun unavailableConfiguredSocketFailsWithoutStartingDaemon() {
        val runner = Runner(true)
        val backend = WaylandInputBackend(runner, mapOf("YDOTOOL_SOCKET" to "/missing")) { false }
        try { backend.initialize(); fail("expected failure") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("YDOTOOL_SOCKET"))
        }
        assertEquals(0, runner.starts)
    }
    @Test fun missingDeviceFailsWithoutStartingDaemon() {
        val runner = Runner()
        val backend = WaylandInputBackend(runner, emptyMap()) { false }
        try { backend.initialize(); fail("expected failure") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("/dev/uinput"))
        }
        assertEquals(0, runner.starts)
    }
    @Test fun commandTimeoutIsBounded() {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val command = if (isWindows) {
            listOf("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 5")
        } else if (java.io.File("/bin/sleep").exists()) {
            listOf("/bin/sleep", "5")
        } else {
            listOf("sleep", "5")
        }
        val start = System.nanoTime()
        try { SystemInputCommandRunner.run(command, timeoutMs = 50); fail("expected timeout") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("timed out")) }
        assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 4)
    }
    @Test fun unknownDesktopRejectsFixedAreaBeforeGeometry() {
        val runner = Runner()
        for (desktop in listOf(null, "KDE", "")) {
            try { waylandBounds(runner, desktop); fail("expected desktop rejection") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("GNOME")) }
        }
        assertTrue(runner.calls.isEmpty())
    }
    @Test fun gnomeNonFlatProfileRejectsClicks() {
        val runner = object : InputCommandRunner {
            override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long) = "'adaptive'"
            override fun start(args: List<String>): Process = error("unexpected")
        }
        try { waylandBounds(runner, "GNOME"); fail("expected profile rejection") }
        catch (e: IllegalStateException) { assertTrue(e.message!!.contains("flat")) }
    }
    @Test fun gnomeFlatSpeedCompensatesMousemoveCommand() {
        for ((speed, expected) in listOf("-0.25" to listOf("400", "200"), "0.0" to listOf("300", "150"), "0.5" to listOf("200", "100"))) {
            val calls = mutableListOf<List<String>>()
            val runner = object : InputCommandRunner {
                override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long): String {
                    calls.add(args)
                    return if (args.first() == "gsettings") speed else ""
                }
                override fun start(args: List<String>): Process = error("unexpected")
            }
            val backend = WaylandInputBackend(runner, mapOf("YDOTOOL_SOCKET" to "/external", "XDG_CURRENT_DESKTOP" to "GNOME")) { false }
            backend.move(300, 150)
            assertEquals(expected, calls.last().takeLast(2))
        }
    }
    @Test fun stopDuringSpeedLookupPreventsMouseCommand() {
        var valid = true
        val calls = mutableListOf<List<String>>()
        val runner = object : InputCommandRunner {
            override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long): String {
                calls.add(args)
                if (args.first() == "gsettings") { valid = false; return "0.0" }
                return ""
            }
            override fun start(args: List<String>): Process = error("unexpected")
        }
        val backend = WaylandInputBackend(runner, mapOf("YDOTOOL_SOCKET" to "/external", "XDG_CURRENT_DESKTOP" to "GNOME")) { false }
        try { backend.moveChecked(300, 150) { valid }; fail("expected cancellation") }
        catch (_: java.util.concurrent.CancellationException) {}
        assertTrue(calls.none { it.contains("mousemove") })
    }
    @Test fun invalidGnomeSpeedFailsBeforeMoving() {
        for (speed in listOf("-1.0", "NaN", "oops")) {
            val runner = object : InputCommandRunner {
                override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long) = speed
                override fun start(args: List<String>): Process = error("unexpected")
            }
            try { waylandPointerGain(runner, "GNOME"); fail("expected failure") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
        }
    }
    @Test fun geometryParsesLogicalCoordinates() {
        val runner = object : InputCommandRunner {
            override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long) = if (args.first() == "gsettings") "'flat'" else "0 0 1536 960"
            override fun start(args: List<String>): Process = error("unexpected")
        }
        assertEquals(Rectangle(0, 0, 1536, 960), waylandBounds(runner, "ubuntu:GNOME"))
    }

    @Test fun mouseWheelEmitsSignedYdotoolStepsAndChecksPageNavigationKeys() {
        val runner = Runner()
        val backend = WaylandInputBackend(runner, mapOf("YDOTOOL_SOCKET" to "/external")) { false }
        performInput(backend, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.DOWN, scrollAmount = 3, clickAtPointer = true), Rectangle(1, 1))
        performInput(backend, MappedTargetAction.SCREEN_SCROLL, InputConfig(scrollDirection = ScrollDirection.UP, scrollAmount = 2, clickAtPointer = true), Rectangle(1, 1))
        assertTrue(runner.calls.any { it == listOf("ydotool", "mousemove", "-w", "--", "0", "3") })
        assertTrue(runner.calls.any { it == listOf("ydotool", "mousemove", "-w", "--", "0", "-2") })
        performInput(backend, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_UP), Rectangle(1, 1))
        performInput(backend, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.PAGE_DOWN), Rectangle(1, 1))
        performInput(backend, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.HOME), Rectangle(1, 1))
        performInput(backend, MappedTargetAction.KEYBOARD_INPUT, InputConfig(key = PortableKey.END), Rectangle(1, 1))
        assertTrue(runner.calls.any { it == listOf("ydotool", "key", "104:1") })
        assertTrue(runner.calls.any { it == listOf("ydotool", "key", "109:1") })
        assertTrue(runner.calls.any { it == listOf("ydotool", "key", "102:1") })
        assertTrue(runner.calls.any { it == listOf("ydotool", "key", "107:1") })
    }

    @Test fun missingYdotoolBinaryReportsClearInstallError() {
        val runner = object : InputCommandRunner {
            override fun run(args: List<String>, environment: Map<String, String>, timeoutMs: Long): String {
                if (args.contains("ydotoold") || args.first() == "ydotool") throw java.io.IOException("No such file")
                return ""
            }
            override fun start(args: List<String>): Process = throw java.io.IOException("Cannot run program \"ydotoold\"")
        }
        val backend = WaylandInputBackend(runner, mapOf("XDG_RUNTIME_DIR" to System.getProperty("java.io.tmpdir"))) { true }
        try {
            backend.initialize()
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ydotool"))
        }
    }
}
