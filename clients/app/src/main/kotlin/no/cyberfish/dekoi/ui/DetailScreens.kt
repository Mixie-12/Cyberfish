package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.isIntrusion
import java.time.Duration
import java.time.Instant

private const val MAX_NAME = 40

@Composable
fun EventScreen(
    event: Event,
    host: NetworkHost?,
    onTrust: (NetworkHost, Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val text = eventText(event)
    Screen(title = eventTitle(event), onBack = onBack) {
        item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.event_why), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(text.explanation), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.event_advice), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(text.advice), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        if (event.isIntrusion && host != null) {
            item {
                if (host.trusted) {
                    SecondaryText(stringResource(R.string.event_trusted))
                    AccentButton(R.string.event_untrust) { onTrust(host, false) }
                } else {
                    AccentButton(R.string.event_trust) { onTrust(host, true) }
                }
            }
        }
        item { SectionTitle(R.string.event_details) }
        item {
            Card {
                Column {
                    KeyValue(R.string.event_detail_time, absoluteTime(event.occurredAt))
                    if (event.count > 1) {
                        SecondaryText(pluralStringResource(R.plurals.event_repeated, event.count, event.count))
                    }
                    event.srcIp?.let { KeyValue(R.string.event_detail_source, it) }
                    event.srcMac?.let { KeyValue(R.string.event_detail_mac, it) }
                    host?.vendor?.let { KeyValue(R.string.event_detail_vendor, it) }
                    event.dstPort?.let { KeyValue(R.string.event_detail_port, it.toString()) }
                    event.detail.username?.let { KeyValue(R.string.event_detail_username, it) }
                    event.detail.path?.let { KeyValue(R.string.event_detail_path, it) }
                    (event.detail.clientVersion ?: event.detail.userAgent)?.let { KeyValue(R.string.event_detail_client, it) }
                    event.detail.command?.let { KeyValue(R.string.event_detail_command, it) }
                    event.detail.signature?.let { KeyValue(R.string.event_detail_signature, it) }
                    event.detail.category?.let { KeyValue(R.string.event_detail_category, it) }
                    event.detail.claimedIp?.let { KeyValue(R.string.event_detail_claimed_ip, it) }
                    event.detail.previousMac?.let { KeyValue(R.string.event_detail_previous_mac, it) }
                    event.detail.serverIp?.let { KeyValue(R.string.event_detail_server, it) }
                    event.detail.protocol?.let { KeyValue(R.string.event_detail_protocol, it.uppercase()) }
                    event.detail.targets?.let { KeyValue(R.string.event_detail_targets, it.toString()) }
                    if (event.detail.ports.isNotEmpty()) {
                        KeyValue(R.string.event_detail_ports, event.detail.ports.joinToString(", "))
                    }
                }
            }
        }
    }
}

@Composable
fun NetworkScreen(
    hosts: List<NetworkHost>,
    now: Instant,
) {
    Screen(title = stringResource(R.string.tab_network)) {
        item { SecondaryText(stringResource(R.string.network_body)) }
        if (hosts.isEmpty()) {
            item { Card { SecondaryText(stringResource(R.string.network_empty)) } }
        }
        items(hosts, key = { it.deviceId + it.mac }) { host -> HostCard(host, now) }
    }
}

@Composable
private fun HostCard(
    host: NetworkHost,
    now: Instant,
) {
    val name =
        when {
            host.vendor != null -> host.vendor
            host.isPrivateMac -> stringResource(R.string.network_private)
            else -> stringResource(R.string.network_unknown_maker)
        }
    val isNew = Duration.between(host.firstSeenAt, now) < Duration.ofDays(7)
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                SecondaryText(host.ip + "  " + host.mac)
            }
            if (host.trusted) {
                SecondaryText(stringResource(R.string.network_known))
            } else if (isNew) {
                StatusDot(MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.network_new),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
fun DeviceScreen(
    device: Device,
    onRename: (String) -> Unit,
    onRemove: () -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(device.id) { mutableStateOf(device.name) }
    var confirming by remember { mutableStateOf(false) }
    Screen(title = device.name, onBack = onBack) {
        item {
            Card {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(MAX_NAME) },
                        label = { Text(stringResource(R.string.device_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (name.isNotBlank() && name != device.name) {
                        AccentButton(R.string.action_save) { onRename(name.trim()) }
                    }
                }
            }
        }
        item {
            Card {
                Column {
                    device.agentVersion?.let { KeyValue(R.string.device_version, it) }
                    device.localIp?.let { KeyValue(R.string.device_address, it) }
                    KeyValue(R.string.device_decoys, device.services.size.toString())
                }
            }
        }
        item { AccentButton(R.string.device_remove) { confirming = true } }
    }
    if (confirming) {
        ConfirmDialog(
            title = R.string.device_remove_confirm_title,
            body = R.string.device_remove_confirm_body,
            confirm = R.string.action_remove,
            onConfirm = {
                confirming = false
                onRemove()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
fun ConfirmDialog(
    title: Int,
    body: Int,
    confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
