package no.cyberfish.dekoi.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.WifiName
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.wifiWatchText
import java.time.Instant

/**
 * Wi-Fi Guard in Settings (ADR 0019): one row per DeKoi with the home
 * Wi-Fi's name and what Wi-Fi Guard is doing; a tap edits the name.
 */
@Composable
fun WifiNameSection(
    devices: List<Device>,
    now: Instant,
    onSave: (Device, String?) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    InsetSection(title = stringResource(R.string.wifi_guard), footer = stringResource(R.string.wifi_name_footer)) {
        devices.forEach { device ->
            val status = stringResource(wifiWatchText(device, now))
            InsetRow(
                device.wifiName ?: stringResource(R.string.wifi_name_not_set),
                subtitle = if (devices.size > 1) stringResource(R.string.wifi_name_for_device, device.name, status) else status,
                onClick = { editing = device.id },
            )
        }
    }
    devices.firstOrNull { it.id == editing }?.let { device ->
        WifiNameDialog(
            device,
            onSave = { name ->
                editing = null
                onSave(device, name)
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun WifiNameDialog(
    device: Device,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(device.id) { mutableStateOf(device.wifiName.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wifi_name_title)) },
        text = {
            OutlinedTextField(
                value = text,
                // Keeps the text to what the backend accepts: 32 bytes, no control characters.
                onValueChange = { if (WifiName.fits(it)) text = it },
                label = { Text(stringResource(R.string.wifi_name_label)) },
                supportingText = { Text(stringResource(R.string.wifi_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(WifiName.of(text)) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
