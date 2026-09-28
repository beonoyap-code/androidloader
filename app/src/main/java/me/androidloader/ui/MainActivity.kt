package me.androidloader.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SideloadScreen()
                }
            }
        }
    }
}

/**
 * The single screen.
 *
 * Deliberately one surface rather than a navigation graph: the flow is linear
 * (start usbmuxd, find a device, pair, install), and a wizard that cannot be
 * navigated out of is easier to reason about than a multi-screen app at this stage.
 */
@Composable
fun SideloadScreen(viewModel: SideloadViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
            Text(
                text = state.progressMessage,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // Errors are shown as the primary content when present: on this path the
        // next action is almost always to fix the error, not to browse on.
        state.error?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Problem",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(text = message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (state.log.isNotEmpty()) {
            LogView(lines = state.log)
        }

        Button(
            onClick = viewModel::startUsbmuxd,
            enabled = !state.busy && !state.usbmuxdRunning,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.usbmuxdRunning) "usbmuxd is running" else "Start usbmuxd in Termux")
        }

        Button(
            onClick = viewModel::refresh,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Look for iPhone")
        }

        state.devices.forEach { device ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = device.udid,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = when (val c = device.connection) {
                            is me.androidloader.usbmux.UsbmuxProtocol.ConnectionType.Usb -> "USB"
                            is me.androidloader.usbmux.UsbmuxProtocol.ConnectionType.Network ->
                                "Wi-Fi ${c.address}"
                            is me.androidloader.usbmux.UsbmuxProtocol.ConnectionType.Unknown ->
                                c.description
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        onClick = { viewModel.pair(device.udid) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Pair with this iPhone")
                    }
                }
            }
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
            Text(
                text = state.detail,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun LogView(lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
