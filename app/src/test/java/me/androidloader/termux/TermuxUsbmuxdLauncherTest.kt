package me.androidloader.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the command the launcher hands to Termux:API.
 *
 * The command is the whole contract with Termux:API, so its exact shape matters
 * more than it looks: get the quoting or the socket address wrong and the daemon
 * starts somewhere the app cannot reach, which is indistinguishable from a
 * permissions problem in the logs the user sees.
 */
class TermuxUsbmuxdLauncherTest {

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
        // Termux:API hands everything after -e to the shell verbatim, so the
        // inner command has to stay a single quoted argument.
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
        assertTrue(notInstalled, notInstalled.contains("Termux:API"))

        val denied = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.PermissionDenied,
        )
        assertTrue(denied, denied.contains("permission"))

        val failed = TermuxUsbmuxdLauncher.explain(
            TermuxUsbmuxdLauncher.LaunchResult.Failed("boom"),
        )
        assertTrue(failed, failed.contains("boom"))

        assertEquals(
            "",
            TermuxUsbmuxdLauncher.explain(TermuxUsbmuxdLauncher.LaunchResult.Started),
        )
    }
}
