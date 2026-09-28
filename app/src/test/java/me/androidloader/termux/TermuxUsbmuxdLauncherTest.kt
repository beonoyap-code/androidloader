package me.androidloader.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the exact command handed to Termux.
 *
 * Every one of these assertions is here because the corresponding thing was wrong
 * at some point, and each failure mode pointed somewhere other than its cause:
 * a missing device path looks like a broken daemon, and a wrong package looks
 * like a phone without Termux on it.
 */
class TermuxUsbmuxdLauncherTest {

    private val path = "/dev/bus/usb/001/002"

    @Test
    fun `includes the device path termux-usb requires`() {
        // termux-usb checks $# before anything else and exits with
        // "missing -l or device path" when the argument is absent.
        val command = TermuxUsbmuxdLauncher.command(device = path)
        assertTrue(command, command.endsWith("\"$path\""))
        assertTrue(command, command.contains(" $path"))
    }

    @Test
    fun `refuses to build a command with no device`() {
        try {
            TermuxUsbmuxdLauncher.command(device = "   ")
            throw AssertionError("a blank device path should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("device path"))
        }
    }

    @Test
    fun `uses the flags termux-usb documents`() {
        val command = TermuxUsbmuxdLauncher.command(device = path)
        // -r requests permission when Termux:API does not already hold it.
        assertTrue(command, command.contains(" -r "))
        // -E passes the descriptor in TERMUX_USB_FD rather than as argv, which is
        // what Termux's patched libusb reads.
        assertTrue(command, command.contains(" -E "))
        assertTrue(command, command.contains(" -e "))
    }

    @Test
    fun `runs usbmuxd with the loopback socket`() {
        val command = TermuxUsbmuxdLauncher.command(device = path)
        // The daemon's default is a Unix socket inside Termux's private data
        // directory, which an APK cannot open. Loopback is shared between apps.
        assertTrue(command, command.contains("--socket 127.0.0.1:27015"))
        assertTrue(command, command.contains("--pidfile NONE"))
        assertTrue(command, command.contains("usbmuxd"))
    }

    @Test
    fun `honours a custom endpoint`() {
        val command = TermuxUsbmuxdLauncher.command(
            device = path,
            endpoint = me.androidloader.usbmux.UsbmuxEndpoint("127.0.0.1", 27016),
        )
        assertTrue(command, command.contains("--socket 127.0.0.1:27016"))
    }

    @Test
    fun `quotes the command and the device so the shell keeps them intact`() {
        val command = TermuxUsbmuxdLauncher.command(device = path)
        val quoted = Regex("-e \"([^\"]*)\"").find(command)
        assertTrue("expected a quoted -e argument in: $command", quoted != null)
        assertTrue(quoted!!.groupValues[1].contains("usbmuxd"))
    }

    @Test
    fun `addresses the Termux app, not Termux API`() {
        // Termux:API removed its RunCommandService. The one that exists is
        // com.termux.app.RunCommandService in the Termux package, exported and
        // guarded by com.termux.permission.RUN_COMMAND.
        assertEquals("com.termux", TermuxUsbmuxdLauncher.TERMUX_PACKAGE)
        assertEquals("com.termux.app.RunCommandService", TermuxUsbmuxdLauncher.SERVICE_CLASS)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxUsbmuxdLauncher.PERMISSION)
        assertEquals("com.termux.RUN_COMMAND", TermuxUsbmuxdLauncher.ACTION)
    }

    @Test
    fun `explains each failure without claiming Termux is missing`() {
        // The previous wording said "Termux is not installed" on a phone that had
        // Termux on it, because a service component was looked up with
        // queryIntentActivities, which only ever returns activities.
        val notInstalled = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.NotInstalled,
        )
        assertFalse(notInstalled, notInstalled.contains("is not installed"))
        assertTrue(notInstalled, notInstalled.contains("Look for iPhone"))

        val denied = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.PermissionDenied,
        )
        assertTrue(denied, denied.contains(TermuxUsbmuxdLauncher.PERMISSION))

        assertTrue(
            TermuxUsbmuxdLauncher.explain(
                TermuxUsbmuxdLauncher.LaunchResult.Failed("boom"),
            ).contains("boom"),
        )
        assertEquals(
            "",
            TermuxUsbmuxdLauncher.explain(TermuxUsbmuxdLauncher.LaunchResult.Started),
        )
    }

    @Test
    fun `documents the setting Termux silently requires`() {
        val instructions = TermuxUsbmuxdLauncher.allowExternalAppsInstructions()
        assertTrue(instructions, instructions.contains("allow-external-apps"))
        assertTrue(instructions, instructions.contains("termux.properties"))
    }
}
