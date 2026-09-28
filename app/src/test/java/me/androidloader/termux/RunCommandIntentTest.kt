package me.androidloader.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests that inspect the built intent.
 *
 * Separate from [TermuxUsbmuxdLauncherTest] because those run on a plain JVM,
 * where `Intent` is a stub that records nothing. Asserting on a real intent needs
 * Robolectric, and keeping the two apart means the fast tests do not pay for it.
 *
 * The component and the extras are the contract with Termux:API, and both were
 * wrong at some point: the component named a service that no longer exists, and
 * `startActivity` on a service component produces an error that reads as though
 * Termux were missing.
 */
@RunWith(RobolectricTestRunner::class)
class RunCommandIntentTest {

    @Test
    fun `addresses the Termux run command service`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        assertEquals(TermuxUsbmuxdLauncher.ACTION, intent.action)
        assertEquals("com.termux", intent.component?.packageName)
        assertEquals(
            "com.termux.app.RunCommandService",
            intent.component?.className,
        )
    }

    @Test
    fun `passes the command as an argument array list`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        // Termux reads this extra as an ArrayList<String>; a bare list is rejected.
        val args = intent.getStringArrayListExtra(TermuxUsbmuxdLauncher.EXTRA_ARGUMENTS)
        assertEquals(listOf("-lc", "echo hi"), args)
    }

    @Test
    fun `the built command survives a round trip through the intent`() {
        // The whole chain, so a device path is still present after it has been
        // quoted, put in an extra, and read back out.
        val device = "/dev/bus/usb/001/002"
        val command = TermuxUsbmuxdLauncher.command(device = device)
        val intent = TermuxUsbmuxdLauncher.runCommandIntent(command)!!
        val args = intent.getStringArrayListExtra(TermuxUsbmuxdLauncher.EXTRA_ARGUMENTS)!!
        assertTrue(args[1].contains(device))
        assertTrue(args[1].contains("termux-usb"))
    }

    @Test
    fun `sets the executable to the Termux bash`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        assertEquals(
            "/data/data/com.termux/files/usr/bin/bash",
            intent.getStringExtra("com.termux.RUN_COMMAND_PATH"),
        )
    }

    @Test
    fun `runs in the background so the daemon is not tied to a terminal session`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi")!!
        assertEquals(true, intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
    }

    @Test
    fun `carries the session name`() {
        val intent = TermuxUsbmuxdLauncher.runCommandIntent("echo hi", session = "probe")!!
        assertEquals("probe", intent.getStringExtra("com.termux.RUN_COMMAND_SESSION"))
    }
}
