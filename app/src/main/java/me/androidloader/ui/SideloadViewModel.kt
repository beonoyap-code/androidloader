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
    val log: List<String> = emptyList(),
) {
    /** One line summarising where the flow has got to. */
    val headline: String
        get() = when {
            busy -> "Working"
            error != null -> "Needs attention"
            usbmuxdRunning && devices.isEmpty() -> "No iPhone found"
            usbmuxdRunning -> "${devices.size} iPhone connected"
            else -> "Not connected to usbmuxd"
        }

    val detail: String
        get() = when {
            busy -> progressMessage
            !usbmuxdRunning ->
                "Start usbmuxd from Termux, then plug the iPhone into the OTG port."
            devices.isEmpty() ->
                "usbmuxd is running but no device has appeared. Check the cable and that " +
                    "the phone accepts USB-OTG."
            else -> "Unlock the iPhone and accept the trust prompt when it appears."
        }
}

/**
 * Drives the connection flow.
 *
 * The ordering here is the whole story of the app: nothing works until usbmuxd is
 * running, and nothing privileged works until the device is paired. Each step
 * therefore reports its own failure with the remedy, because the user cannot see
 * the Termux side of it.
 */
class SideloadViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SideloadUiState())
    val state: StateFlow<SideloadUiState> = _state.asStateFlow()

    private val usbmux = UsbmuxClient()

    fun startUsbmuxd() {
        viewModelScope.launch {
            when (val result = TermuxUsbmuxdLauncher.launch(getApplication())) {
                is TermuxUsbmuxdLauncher.LaunchResult.Started -> {
                    append("Asked Termux to start usbmuxd")
                    // The daemon needs a moment to bind its socket; poll briefly
                    // rather than reporting failure on the first refused
                    // connection.
                    pollForUsbmuxd()
                }
                else -> {
                    val explanation = TermuxUsbmuxdLauncher.explain(result)
                    append("launch failed: $explanation")
                    fail(
                        explanation + "\n\nIf Termux is set up, run this by hand instead:\n" +
                            TermuxUsbmuxdLauncher.command(),
                    )
                }
            }
        }
    }

    private suspend fun pollForUsbmuxd() {
        repeat(15) {
            delay(400)
            if (probe()) {
                _state.update { it.copy(usbmuxdRunning = true, error = null) }
                append("usbmuxd is up")
                refreshDevices()
                return
            }
        }
        fail(
            "usbmuxd did not start. Check that Termux:API is installed and that " +
                "'pkg install usbmuxd libimobiledevice' succeeded.",
        )
    }

    private suspend fun probe(): Boolean = try {
        usbmux.connect()
        true
    } catch (e: Exception) {
        false
    }

    fun refresh() {
        viewModelScope.launch { refreshDevices() }
    }

    private suspend fun refreshDevices() {
        _state.update { it.copy(busy = true, error = null, progressMessage = "Looking for iPhones") }
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
            if (devices.isEmpty()) append("usbmuxd reported no devices")
            else devices.forEach { append("found ${it.udid}") }
        } catch (e: Exception) {
            fail(describe(e))
        }
    }

    fun pair(udid: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, progressMessage = "Pairing") }
            try {
                // Pairing records hold DER certificates and keys, so reading and
                // writing them is file I/O and must not run on the main thread.
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
                val result = lockdown.openSession(stored)
                withContext(Dispatchers.IO) {
                    PairingStore.save(getApplication(), udid, result)
                }
                append("paired and started a session")
                _state.update { it.copy(busy = false, progressMessage = "") }
                lockdown.close()
            } catch (e: Exception) {
                fail(describe(e))
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is me.androidloader.usbmux.UsbmuxUnavailableException -> e.message
            ?: "usbmuxd is not reachable"
        is me.androidloader.lockdown.LockdownException -> e.message
            ?: "lockdownd refused the request"
        is me.androidloader.afc.AfcException -> e.message ?: "the file transfer failed"
        is me.androidloader.install.InstallException -> e.message ?: "the install failed"
        else -> e.message ?: e.toString()
    }

    private fun fail(message: String) {
        append("error: $message")
        _state.update { it.copy(busy = false, error = message, progressMessage = "") }
    }

    private fun append(line: String) {
        _state.update { it.copy(log = (it.log + line).takeLast(50)) }
    }

    private suspend fun delay(ms: Long) = kotlinx.coroutines.delay(ms)

    override fun onCleared() {
        usbmux.close()
        super.onCleared()
    }
}
