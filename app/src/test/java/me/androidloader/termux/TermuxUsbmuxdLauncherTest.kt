package me.androidloader.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the Termux run-command contract.
 *
 * The command string and the intent component are the whole interface with
 * Termux:API. Getting either wrong fails in a way that looks like "Termux is not
 * installed", which is what made the earlier version so confusing, so both are
 * pinned here.
 */
class TermuxUsbmuxdLauncherTest {

    @Test
    fun `targets the Termux app, not Termux API`() {
        // Termux:API removed its RunCommandService. The one that exists is in the
        // Termux package; aiming at the old one raises ActivityNotFoundException
        // and reads as though Termux were absent.
        assertEquals("com.termux", TermuxUsbmuxdLauncher.TERMUX_PACKAGE)
        assertEquals("com.termux.app.RunCommandService", TermuxUsbmuxdLauncher.SERVICE_CLASS)
        assertEquals("com.termux.permission.RUN_COMMAND", TermuxUsbmuxdLauncher.PERMISSION)
    }

    @Test
    fun `sends the run command action`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        assertEquals("com.termux.RUN_COMMAND", intent.action)
        assertEquals(
            "com.termux",
            intent.component?.packageName,
        )
        assertEquals(
            TermuxUsbmuxdLauncher.SERVICE_CLASS,
            intent.component?.className,
        )
    }

    @Test
    fun `passes arguments as an array list`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        val args = intent.getStringArrayListExtra("com.termux.RUN_COMMAND_ARGUMENTS")
        assertEquals(listOf("-lc", "echo hi"), args)
    }

    @Test
    fun `builds the documented usbmuxd command`() {
        val command = TermuxUsbmuxdLauncher.command(device = "/dev/bus/usb/001/002")
        assertTrue(command, command.startsWith("termux-usb "))
        // -r refreshes the device list, -E hands the claimed descriptor to the child.
        assertTrue(command, command.contains(" -r "))
        assertTrue(command, command.contains(" -E "))
        assertTrue(command, command.contains("usbmuxd"))
        assertTrue(command, command.contains("/dev/bus/usb/001/002"))
    }

    @Test
    fun `uses loopback tcp because the default unix socket is unreachable`() {
        val command = TermuxUsbmuxdLauncher.command(device = "/dev/bus/usb/001/002")
        // The daemon's default is a Unix socket inside Termux's private data
        // directory, which the app cannot open. Loopback is shared between apps.
        assertTrue(command, command.contains("127.0.0.1:27015"))
        assertTrue(command, command.contains("--socket"))
    }

    @Test
    fun `disables the pidfile because writing outside Termux is not permitted`() {
        val command = TermuxUsbmuxdLauncher.command(device = "/dev/bus/usb/001/002")
        assertTrue(command, command.contains("--pidfile NONE"))
    }

    @Test
    fun `omits the device when none is given`() {
        val command = TermuxUsbmuxdLauncher.command(device = null)
        assertTrue(command, command.contains("termux-usb"))
        assertTrue("should not name a device: $command", !command.contains("/dev/bus/usb"))
    }

    @Test
    fun `honours a custom endpoint`() {
        val command = TermuxUsbmuxdLauncher.command(
            device = "/dev/bus/usb/001/002",
            endpoint = me.androidloader.usbmux.UsbmuxEndpoint("127.0.0.1", 27016),
        )
        assertTrue(command, command.contains("127.0.0.1:27016"))
    }

    @Test
    fun `quotes the command passed to termux-usb -e`() {
        // Termux:API hands everything after -e to the shell verbatim, so the inner
        // command has to stay a single quoted argument.
        val command = TermuxUsbmuxdLauncher.command(device = "/dev/bus/usb/001/002")
        val quoted = Regex("-e \"([^\"]*)\"").find(command)
        assertTrue("expected a quoted -e argument in: $command", quoted != null)
        assertTrue(quoted!!.groupValues[1].contains("usbmuxd"))
    }

    @Test
    fun `each failure explains its own remedy`() {
        val notInstalled = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.NotInstalled,
        )
        // The message must not claim Termux is absent: the app cannot tell, and
        // saying so when it is installed is what caused the earlier confusion.
        assertFalse(notInstalled, notInstalled.contains("is not installed"))
        assertTrue(notInstalled, notInstalled.contains("Look for iPhone"))

        val denied = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.PermissionDenied,
        )
        assertTrue(denied, denied.contains(TermuxUsbmuxdLauncher.PERMISSION))

        val failed = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.Failed("boom"),
        )
        assertTrue(failed, failed.contains("boom"))

        assertEquals(
            "",
            TermuxUsbmuxdLauncher.explain(TermuxUsbmuxdLauncher.LaunchResult.Started),
        )
    }

    @Test
    fun `documents the external apps setting Termux silently requires`() {
        val instructions = TermuxUsbmuxdLauncher.allowExternalAppsInstructions()
        assertTrue(instructions, instructions.contains("allow-external-apps"))
        assertTrue(instructions, instructions.contains("termux.properties"))
    }
}
