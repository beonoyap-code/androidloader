package me.androidloader.termux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import me.androidloader.usbmux.UsbmuxEndpoint

/**
 * Starts usbmuxd from Termux.
 *
 * The daemon cannot open the `usbfs` device nodes from inside an app's sandbox:
 * those nodes belong to a system group an app UID is not in. Termux:API's
 * `termux-usb` can, because it claims the device through Android's USB API; with
 * `-E` it executes a child process that inherits the claimed file descriptor. That
 * is what makes this work without root.
 *
 * Three things about the intent are easy to get wrong, and each fails with a
 * message that points somewhere else:
 *
 *  - The service lives in the **Termux app** (`com.termux`), not Termux:API.
 *    Termux:API removed its own `RunCommandService`; the one that exists is
 *    `com.termux.app.RunCommandService`.
 *  - It is a **Service**, so it must be started with [Context.startService]. Using
 *    `startActivity` raises `ActivityNotFoundException`, which reads as though
 *    Termux were not installed.
 *  - Termux refuses to run commands from other apps unless `allow-external-apps`
 *    is set in `~/.termux/termux.properties`. It fails this with a notification
 *    and no return value, so the caller sees success and nothing happens.
 *
 * None of this is on the critical path: the app works with a usbmuxd the user has
 * already started by hand. This is a convenience on top of that.
 */
object TermuxUsbmuxdLauncher {

    /** The Termux package that hosts the run-command service. */
    const val TERMUX_PACKAGE = "com.termux"

    /** The service component, in Termux itself rather than Termux:API. */
    const val SERVICE_CLASS = "com.termux.app.RunCommandService"

    /** The permission that service checks. */
    const val PERMISSION = "$TERMUX_PACKAGE.permission.RUN_COMMAND"

    /**
     * The command to run inside Termux.
     *
     * @param device the USB path from `termux-usb -l`, or null to let
     *   `termux-usb` pick the only attached device.
     */
    fun command(
        device: String? = null,
        endpoint: UsbmuxEndpoint = UsbmuxEndpoint.DEFAULT,
    ): String {
        val args = listOf(
            "usbmuxd",
            "--socket", "${endpoint.host}:${endpoint.port}",
            // No pidfile: Termux runs the process directly and does not need one,
            // and it cannot write outside its own tree.
            "--pidfile", "NONE",
            // Foreground, because the daemon has to outlive the request.
            "-f",
        ).joinToString(" ")
        val target = device?.let { " \"$it\"" } ?: ""
        return "termux-usb -r -E -e \"$args\"$target"
    }

    /** True when the Termux app is installed and visible to this process. */
    fun isTermuxInstalled(context: Context): Boolean =
        context.packageManager.queryIntentActivities(
            Intent().setClassName(TERMUX_PACKAGE, SERVICE_CLASS),
            0,
        ).isNotEmpty()

    /**
     * Whether this app holds the permission the service checks.
     *
     * Android 13+ can revoke a permission the app has not used recently, so this
     * is checked rather than assumed.
     */
    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * The intent that runs [command] in a Termux session.
     *
     * Returns null when Termux is absent, which is a setup state rather than an
     * error: the user can still start usbmuxd by hand.
     */
    fun runCommandIntent(command: String, session: String = "androidloader"): Intent? {
        val intent = Intent().apply {
            component = ComponentName(TERMUX_PACKAGE, SERVICE_CLASS)
            action = "com.termux.RUN_COMMAND"
            putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_PREFIX + "/bin/bash")
            // Termux reads this as a String[]; a plain list is rejected.
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayListOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_PREFIX)
            // The app shell runner, so the daemon is not tied to a terminal session.
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION", session)
        }
        return intent
    }

    private const val TERMUX_PREFIX = "/data/data/$TERMUX_PACKAGE/files/usr"

    /** The outcome of asking Termux to run the daemon. */
    sealed interface LaunchResult {
        /** Termux accepted the request. The daemon is not up yet. */
        data object Started : LaunchResult

        /** Termux is not installed, or is not visible to this app. */
        data object NotInstalled : LaunchResult

        /** This app does not hold the RUN_COMMAND permission. */
        data object PermissionDenied : LaunchResult

        /** Termux refused to start, with the reason to show. */
        data class Failed(val reason: String) : LaunchResult
    }

    /**
     * Asks Termux to start usbmuxd.
     *
     * This is best effort and never fatal: the app talks to a usbmuxd over
     * loopback regardless of who started it, so a failure here is reported and
     * the user can run [command] by hand.
     */
    fun launch(
        context: Context,
        device: String? = null,
        endpoint: UsbmuxEndpoint = UsbmuxEndpoint.DEFAULT,
    ): LaunchResult {
        if (!isTermuxInstalled(context)) return LaunchResult.NotInstalled
        if (!hasPermission(context)) return LaunchResult.PermissionDenied

        val intent = runCommandIntent(command(device, endpoint))
            ?: return LaunchResult.NotInstalled

        return try {
            // A Service, so startService, not startActivity.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            LaunchResult.Started
        } catch (e: SecurityException) {
            LaunchResult.PermissionDenied
        } catch (e: IllegalStateException) {
            // Android 8+ refuses background service starts; the user tapping the
            // button normally keeps us in the foreground, but not always.
            LaunchResult.Failed("Termux could not be started from the background: ${e.message}")
        } catch (e: RuntimeException) {
            LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Guidance for a failed launch.
     *
     * The `allow-external-apps` note is not optional: without it Termux accepts
     * the request and silently does nothing, which is the hardest failure here to
     * diagnose from the outside.
     */
    fun explain(result: LaunchResult): String = when (result) {
        LaunchResult.Started -> ""
        LaunchResult.NotInstalled ->
            "Termux is not visible to this app. Install Termux from F-Droid, or just " +
                "start usbmuxd yourself and tap Look for iPhone."
        LaunchResult.PermissionDenied ->
            "This app is missing the Termux permission. Reinstall the app, then check " +
                "Settings, Apps, this app, Permissions for \"$PERMISSION\". " +
                "Android sometimes revokes permissions an app has not used recently."
        is LaunchResult.Failed ->
            "Termux could not start: ${result.reason}"
    }

    /**
     * The one-time Termux setting this feature needs, phrased as an instruction.
     *
     * Termux checks this before running anything from another app and reports the
     * refusal only in a notification.
     */
    fun allowExternalAppsInstructions(): String =
        "Termux also needs to be told it may run commands from other apps. In Termux:\n\n" +
            "mkdir -p ~/.termux\n" +
            "echo 'allow-external-apps = true' >> ~/.termux/termux.properties\n" +
            "then restart Termux from the app drawer"
}
