package me.androidloader.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.androidloader.lockdown.LockdownClient
import me.androidloader.pairing.PairingStore
import me.androidloader.termux.TermuxUsbmuxdLauncher
import me.androidloader.usbmux.UsbmuxClient
import me.androidloader.usbmux.UsbmuxProtocol
import me.androidloader.usbmux.UsbmuxSocketChannel

/** Everything the screen renders. */
data class SideloadUiState(
    val busy: Boolean = false,
    val usbmuxdRunning: Boolean = false,
    val devices: List<UsbmuxProtocol.Device> = emptyList(),
    val progressMessage: String = "",
    val error: String? = null,
    val hint: String? = null,
    val log: List<String> = emptyList(),
) {
    /** One line summarising where the flow has got to. */
    val headline: String
        get() = when {
            busy -> "Working"
            error != null -> "Needs attention"
            usbmuxdRunning && devices.isEmpty() -> "Connected, no iPhone found"
            usbmuxdRunning -> "iPhone connected"
            else -> "Not connected to usbmuxd"
        }

    /**
     * What to tell the user next.
     *
     * Deliberately leads with the manual route. usbmuxd may be started by
     * Termux, by this app, or by hand, and the app cannot tell which; telling
     * someone with a working daemon that they need to start one is worse than
     * saying nothing.
     */
    val detail: String
        get() = when {
            busy -> progressMessage
            !usbmuxdRunning ->
                "Run usbmuxd in Termux, then tap Look for iPhone:\n\n" +
                    TermuxUsbmuxdLauncher.command()
            devices.isEmpty() ->
                "usbmuxd is running but sees no device. Check the cable carries data, " +
                    "and that the iPhone is unlocked."
            else -> "Unlock the iPhone and accept the trust prompt when it appears."
        }
}

/**
 * Drives the connection flow.
 *
 * The app talks to usbmuxd over loopback regardless of who started it, so
 * [lookForDevices] is the real entry point and starting the daemon is a
 * convenience layered on top. That ordering means a user whose usbmuxd is
 * already running never depends on the Termux permission dance working.
 */
class SideloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SideloadUiState())
    val state: StateFlow<SideloadUiState> = _state.asStateFlow()

    private val usbmux = UsbmuxClient()

    /**
     * Probes usbmuxd and lists devices.
     *
     * This works against a daemon started by anyone: the app, Termux, or a
     * command the user ran themselves.
     */
    fun lookForDevices() {
        viewModelScope.launch {
            _state.update {
                it.copy(busy = true, error = null, progressMessage = "Looking for an iPhone")
            }
            try {
                usbmux.connect()
                val devices = usbmux.listDevices()
                _state.update {
                    it.copy(
                        busy = false,
                        usbmuxdRunning = true,
                        devices = devices,
                        progressMessage = "",
                    )
                }
                if (devices.isEmpty()) {
                    append("usbmuxd is up, but reported no devices")
                    _state.update {
                        it.copy(
                            hint = "Plug the iPhone into the OTG port, unlock it, " +
                                "then tap Look for iPhone again.",
                        )
                    }
                } else {
                    devices.forEach { append("found ${it.udid}") }
                }
            } catch (e: Exception) {
                fail(describe(e))
            }
        }
    }

    /**
     * Asks Termux to start usbmuxd, then waits for it.
     *
     * Best effort: on any failure the app still works, because the user can start
     * the daemon themselves and tap Look for iPhone.
     */
    fun startUsbmuxd() {
        viewModelScope.launch {
            _state.update { it.copy(error = null, hint = null) }
            when (val result = TermuxUsbmuxdLauncher.launch(getApplication())) {
                is TermuxUsbmuxdLauncher.LaunchResult.Started -> {
                    append("asked Termux to start usbmuxd")
                    _state.update {
                        it.copy(
                            hint = "If nothing happens, Termux is not allowed to run " +
                                "commands from other apps yet:\n\n" +
                                TermuxUsbmuxdLauncher.allowExternalAppsInstructions(),
                        )
                    }
                    waitForUsbmuxd()
                }
                else -> {
                    val explanation = TermuxUsbmuxdLauncher.explain(result)
                    append("could not ask Termux to start usbmuxd: $explanation")
                    _state.update {
                        it.copy(
                            hint = "$explanation\n\nYou can start it yourself in Termux:\n\n" +
                                TermuxUsbmuxdLauncher.command() +
                                "\n\nthen tap Look for iPhone.",
                        )
                    }
                }
            }
        }
    }

    /**
     * Polls for the daemon after a launch request.
     *
     * Termux:API returns as soon as the request is accepted, and the daemon takes a
     * moment to bind its socket, so a single immediate probe would report a
     * failure that is not one.
     */
    private suspend fun waitForUsbmuxd() {
        repeat(12) {
            kotlinx.coroutines.delay(500)
            if (runCatching { usbmux.connect() }.isSuccess) {
                append("usbmuxd is up")
                lookForDevices()
                return
            }
        }
        append("usbmuxd did not come up within about six seconds")
        _state.update {
            it.copy(
                hint = "usbmuxd did not start. Run this in Termux to see why:\n\n" +
                    TermuxUsbmuxdLauncher.command(device = null, endpoint = me.androidloader.usbmux.UsbmuxEndpoint.DEFAULT)
                        .replace(" -f", " -f -v"),
            )
        }
    }

    fun pair(udid: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progressMessage = "Pairing") }
            try {
                // Records hold DER certificates and keys, so this is file I/O and
                // must not run on the main thread.
                val stored = withContext(Dispatchers.IO) {
                    PairingStore.load(getApplication(), udid)
                }
                append(
                    if (stored == null) "no saved pairing, pairing now"
                    else "using the saved pairing",
                )
                usbmux.connect()
                val device = usbmux.listDevices().firstOrNull { it.udid == udid }
                    ?: error("the iPhone is no longer connected")
                val socket = usbmux.connectTo(device, UsbmuxClient.PORT_LOCKDOWN)
                val lockdown = LockdownClient.onChannel(device, UsbmuxSocketChannel(socket))
                try {
                    val result = lockdown.openSession(stored)
                    withContext(Dispatchers.IO) {
                        PairingStore.save(getApplication(), udid, result)
                    }
                    append("paired and started a session")
                    _state.update {
                        it.copy(
                            busy = false,
                            progressMessage = "",
                            hint = "Paired with $udid.",
                        )
                    }
                } finally {
                    lockdown.close()
                }
            } catch (e: Exception) {
                fail(describe(e))
            }
        }
    }

    fun dismissMessages() {
        _state.update { it.copy(error = null, hint = null) }
    }

    private fun describe(e: Exception): String = when (e) {
        is me.androidloader.usbmux.UsbmuxUnavailableException ->
            e.message ?: "usbmuxd is not reachable"
        is me.androidloader.lockdown.LockdownException ->
            e.message ?: "lockdownd refused the request"
        is me.androidloader.afc.AfcException -> e.message ?: "the file transfer failed"
        is me.androidloader.install.InstallException -> e.message ?: "the install failed"
        is me.androidloader.usbmux.UsbmuxProtocolException -> e.message ?: "usbmuxd spoke unexpectedly"
        else -> e.message ?: e.toString()
    }

    private fun fail(message: String) {
        append("error: $message")
        _state.update { it.copy(busy = false, error = message, progressMessage = "", hint = null) }
    }

    private fun append(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(60)) }
    }

    override fun onCleared() {
        usbmux.close()
        super.onCleared()
    }
}
