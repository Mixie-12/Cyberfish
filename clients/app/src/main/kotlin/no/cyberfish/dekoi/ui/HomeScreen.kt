package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.isIntrusion
import no.cyberfish.dekoi.isOnline
import no.cyberfish.dekoi.isSettingUp
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.protection
import java.time.Duration
import java.time.Instant

private const val LATEST_EVENTS = 5

@Composable
fun HomeScreen(
    data: OwnerData,
    now: Instant,
    onRefresh: () -> Unit,
    onAdd: () -> Unit,
    onDevice: (Device) -> Unit,
    onEvent: (Long) -> Unit,
) {
    val state = protection(data.devices, data.events, data.trustedMacs, now)
    Screen(
        title = stringResource(R.string.app_name),
        action = { AccentButton(R.string.action_refresh, onRefresh) },
    ) {
        item { StatusHero(state) }
        if (state == Protection.NO_DEVICE) {
            item { AccentButton(R.string.action_add_dekoi, onAdd) }
            return@Screen
        }
        item { Stats(data, now) }
        item { SectionTitle(R.string.home_your_dekoi) }
        items(data.devices, key = { it.id }) { device -> DeviceCard(device, now, onClick = { onDevice(device) }) }
        item { AccentButton(R.string.action_add_dekoi, onAdd) }
        item { SectionTitle(R.string.home_latest_activity) }
        val latest = data.events.filter { it.isIntrusion }.take(LATEST_EVENTS)
        item {
            Card {
                if (latest.isEmpty()) {
                    SecondaryText(stringResource(R.string.home_no_activity))
                } else {
                    Column { latest.forEach { EventRow(it, now) { onEvent(it.id) } } }
                }
            }
        }
    }
}

@Composable
private fun StatusHero(state: Protection) {
    val (title, body) =
        when (state) {
            Protection.PROTECTED -> R.string.status_protected_title to R.string.status_protected_body
            Protection.ATTENTION -> R.string.status_attention_title to R.string.status_attention_body
            Protection.OFFLINE -> R.string.status_offline_title to R.string.status_offline_body
            Protection.SETTING_UP -> R.string.status_setting_up_title to R.string.status_setting_up_body
            Protection.NO_DEVICE -> R.string.status_no_device_title to R.string.status_no_device_body
        }
    Card(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(LocalStatusColors.current.of(state), size = 14)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            }
            SecondaryText(stringResource(body))
        }
    }
}

@Composable
private fun Stats(
    data: OwnerData,
    now: Instant,
) {
    val decoys =
        data.devices
            .filter { it.isOnline(now) }
            .flatMap { it.services }
            .size
    val newThisWeek = data.hosts.count { Duration.between(it.firstSeenAt, now) < Duration.ofDays(7) }
    val lastCheckIn = data.devices.mapNotNull { it.lastSeenAt }.maxOrNull()
    Card {
        Column {
            KeyValue(R.string.stat_decoys, decoys.toString())
            Divider()
            KeyValue(R.string.stat_devices_seen, data.hosts.size.toString())
            Divider()
            KeyValue(R.string.stat_new_this_week, newThisWeek.toString())
            Divider()
            KeyValue(R.string.stat_last_check_in, lastCheckIn?.let { relativeTime(it, now) } ?: stringResource(R.string.stat_never))
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    now: Instant,
    onClick: () -> Unit,
) {
    val colors = LocalStatusColors.current
    val (label, color) =
        when {
            device.isSettingUp -> R.string.device_setting_up to colors.settingUp
            device.isOnline(now) -> R.string.device_online to colors.protected
            else -> R.string.device_offline to colors.offline
        }
    Card(onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusDot(color)
                Spacer(Modifier.width(6.dp))
                SecondaryText(stringResource(label))
            }
            if (device.isSettingUp) {
                SetupSteps(device)
                if (device.needsSetupHelp(now)) {
                    Text(stringResource(R.string.setup_help_title), style = MaterialTheme.typography.titleMedium)
                    SecondaryText(stringResource(R.string.setup_help_body))
                }
            }
        }
    }
}

@Composable
private fun SetupSteps(device: Device) {
    val steps =
        listOf(
            R.string.setup_linked to true,
            R.string.setup_first_start to (device.registeredAt != null),
            R.string.setup_online to (device.lastSeenAt != null),
            R.string.setup_protecting to device.services.isNotEmpty(),
        )
    val colors = LocalStatusColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(start = 2.dp)) {
        steps.forEach { (label, done) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (done) colors.protected else colors.divider)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun ActivityScreen(
    data: OwnerData,
    now: Instant,
    onEvent: (Long) -> Unit,
) {
    Screen(title = stringResource(R.string.tab_activity)) {
        item {
            Card {
                if (data.events.isEmpty()) {
                    SecondaryText(stringResource(R.string.home_no_activity))
                } else {
                    Column { data.events.forEach { EventRow(it, now) { onEvent(it.id) } } }
                }
            }
        }
    }
}
