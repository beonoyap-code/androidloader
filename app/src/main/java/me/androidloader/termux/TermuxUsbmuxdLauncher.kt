package me.androidloader.termux

import android.content.Intent
import android.net.Uri
import android.os.Build
import me.androidloader.usbmux.UsbmuxClient
import me.androidloader.usbmux.UsbmuxEndpoint

/**
 * How to start usbmuxd on an unrooted phone.
 *
 * The daemon itself cannot open the `usbfs` device nodes from inside Termux, because
 * Android's USB nodes are not accessible to an app UID. The way around it is
 * Termux:API's `termux-usb`, which claims the device through Android's USB API
 * and, with `-E`, executes a child process holding that file descriptor:
 *
 * ```
 * termux-usb -r -E -e "usbmuxd --socket 127.0.0.1:27015 --pidfile NONE -f" /dev/bus/usb/001/002
 * ```
 *
 * TCP is chosen over the daemon's default Unix socket because that socket lives
 * in Termux's private data directory, which this app cannot open. Loopback is
 * shared between apps, so TCP is the only transport available to an APK.
 */
object TermuxUsbmuxdLauncher {

    /** The Termux:API package that provides `termux-usb`. */
    const val TERMUX_API_PACKAGE = "com.termux.api"

    /** The Termux package, used to verify it is installed. */
    const val TERMUX_PACKAGE = "com.termux"

    /**
     * The command to run inside Termux.
     *
     * @param device the USB path from `termux-usb -l`, or null to let
     *   `termux-usb` pick the only attached device.
     * @param foreground kept in the foreground; the daemon must outlive the call,
     *   so it is launched with `-f` and detached by Termux itself.
     */
    fun command(
        device: String? = null,
        endpoint: UsbmuxEndpoint = UsbmuxEndpoint.DEFAULT,
    ): String {
        val args = buildList {
            add("usbmuxd")
            add("--socket")
            add("${endpoint.host}:${endpoint.port}")
            // No pidfile: Termux runs the process directly and does not need one,
            // and writing outside its own tree is not permitted.
            add("--pidfile")
            add("NONE")
            add("-f")
        }.joinToString(" ")
        val target = device?.let { " \"$it\"" } ?: ""
        return "termux-usb -r -E -e \"$args\"$target"
    }

    /**
     * The Termux:API intent that runs [command] in a Termux session.
     *
     * Returns null when Termux:API is not installed, which is the common case on a
     * fresh phone and should be presented as setup guidance rather than an error.
     */
    fun runCommandIntent(command: String, session: String = "androidloader"): Intent? = try {
        Intent().apply {
            setClassName(TERMUX_API_PACKAGE, "com.termux.api.RunCommandService")
            action = "com.termux.RUN_COMMAND"
            // Required because this is launched from an application context
            // rather than an activity, and it is a hard failure without it.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/$TERMUX_PACKAGE/files/usr/bin/bash")
            // An ArrayList is required here; Termux:API reads this extra as an
            // ArrayList<String> and rejects a plain list.
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayListOf("-lc", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/$TERMUX_PACKAGE/files/usr")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION", session)
            putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", false)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * The outcome of asking Termux:API to start the daemon.
     *
     * A sealed result rather than a boolean because each failure has a different
     * remedy, and the user cannot see the Termux side of any of them.
     */
    sealed interface LaunchResult {
        /** Termux:API accepted the request. The daemon is not up yet. */
        data object Started : LaunchResult

        /** Termux:API is not installed, or its service could not be resolved. */
        data object NotInstalled : LaunchResult

        /** Termux:API refused because this app lacks the RUN_COMMAND permission. */
        data object PermissionDenied : LaunchResult

        /** Anything else, with the reason to show. */
        data class Failed(val reason: String) : LaunchResult
    }

    /**
     * Asks Termux:API to run the daemon.
     *
     * [context] may be an application context, which is why the intent carries
     * [android.content.Intent.FLAG_ACTIVITY_NEW_TASK]: `startActivity` from a
     * non-activity context throws otherwise, and that failure is an
     * `AndroidRuntimeException` rather than a `SecurityException`, so a narrower
     * catch would let it crash the app.
     *
     * The daemon is not reachable when this returns; callers should poll
     * [UsbmuxClient.connect] with a delay.
     */
    fun launch(
        context: android.content.Context,
        device: String? = null,
        endpoint: UsbmuxEndpoint = UsbmuxEndpoint.DEFAULT,
    ): LaunchResult {
        val intent = runCommandIntent(command(device, endpoint))
            ?: return LaunchResult.NotInstalled
        return try {
            context.startActivity(intent)
            LaunchResult.Started
        } catch (e: SecurityException) {
            LaunchResult.PermissionDenied
        } catch (e: android.content.ActivityNotFoundException) {
            LaunchResult.NotInstalled
        } catch (e: RuntimeException) {
            // Includes the missing-NEW_TASK failure above, which must not take the
            // app down with it.
            LaunchResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Guidance for a failed launch, phrased for someone who cannot see Termux.
     */
    fun explain(result: LaunchResult): String = when (result) {
        LaunchResult.Started -> ""
        LaunchResult.NotInstalled ->
            "Termux:API is not installed. Install Termux and Termux:API from F-Droid, " +
                "then run in Termux:\n\npkg install usbmuxd libimobiledevice termux-api"
        LaunchResult.PermissionDenied ->
            "Termux:API refused the request. Open Settings, then Apps, Termux:API, " +
                "Permissions, and allow \"Run commands\". The permission is " +
                "${requiredPermission()}."
        is LaunchResult.Failed ->
            "Termux:API could not be started: ${result.reason}"
    }

    /** The `RUN_COMMAND` permission Termux:API checks. */
    fun requiredPermission(): String = "$TERMUX_API_PACKAGE.permission.RUN_COMMAND"
}
