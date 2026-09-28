package me.androidloader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.androidloader.termux.TermuxUsbmuxdLauncher
import me.androidloader.usbmux.UsbmuxProtocol

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Installed before any of our own code runs, so a crash on the first
        // frame is still recorded.
        CrashLog.install(this)

        val crash = CrashLog.consume(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SideloadScreen(lastCrash = crash)
                }
            }
        }
    }
}

/**
 * The single screen.
 *
 * One primary action, because that is what this build can actually do: find the
 * iPhone. Starting usbmuxd is offered alongside it but never stands in the way,
 * since the app works against a daemon the user started themselves.
 */
@Composable
fun SideloadScreen(
    viewModel: SideloadViewModel = viewModel(),
    lastCrash: String? = null,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "androidloader",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )

        StatusCard(state)

        if (state.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(state.progressMessage, style = MaterialTheme.typography.bodySmall)
        }

        // A crash from the previous run is shown above everything else, because it
        // is the only information available about a failure on a device with no
        // debugger attached.
        lastCrash?.let { trace ->
            MessageCard(
                title = "The app closed unexpectedly last time",
                body = trace,
                actionLabel = "Copy the details",
                onAction = { copyToClipboard(context, trace) },
            )
        }

        // The one thing to do next. Always enabled unless something is running,
        // so it doubles as "retry" after a failure.
        Button(
            onClick = viewModel::lookForDevices,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Look for iPhone")
        }

        OutlinedButton(
            onClick = viewModel::startUsbmuxd,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Start usbmuxd in Termux")
        }

        if (!state.busy && !state.usbmuxdRunning) {
            MessageCard(
                title = "No usbmuxd yet",
                body = state.detail,
                actionLabel = "Copy Termux command",
                onAction = { copyToClipboard(context, TermuxUsbmuxdLauncher.command()) },
            )
        }

        state.error?.let { message ->
            MessageCard(
                title = "Problem",
                body = message,
                actionLabel = "Dismiss",
                onAction = viewModel::dismissMessages,
            )
        }

        state.hint?.let { hint ->
            MessageCard(title = "Next", body = hint)
        }

        if (state.devices.isNotEmpty()) {
            Text("Devices", style = MaterialTheme.typography.titleMedium)
            state.devices.forEach { device ->
                DeviceCard(
                    device = device,
                    busy = state.busy,
                    onPair = { viewModel.pair(device.udid) },
                )
            }
        }

        if (state.log.isNotEmpty()) {
            LogView(lines = state.log)
        }
    }
}

@Composable
private fun StatusCard(state: SideloadUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(state.headline, style = MaterialTheme.typography.titleMedium)
            if (!state.busy) {
                Text(state.detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: UsbmuxProtocol.Device,
    busy: Boolean,
    onPair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = device.udid,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = when (val c = device.connection) {
                    is UsbmuxProtocol.ConnectionType.Usb -> "USB"
                    is UsbmuxProtocol.ConnectionType.Network -> "Wi-Fi ${c.address}"
                    is UsbmuxProtocol.ConnectionType.Unknown -> c.description
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Button(
                onClick = onPair,
                enabled = !busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text("Pair with this iPhone")
            }
        }
    }
}

/**
 * The activity log.
 *
 * A plain Column, not a LazyColumn: this is already inside a Column with
 * verticalScroll, and nesting a lazy scroller in the same direction throws at
 * layout time. It only surfaced once the log became non-empty, which is to say
 * on the first button press, so the app looked like it launched fine and then
 * died on any interaction.
 */
@Composable
internal fun LogView(lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (line in lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Termux command", text))
    Toast.makeText(context, "Copied. Paste it in Termux.", Toast.LENGTH_SHORT).show()
}
