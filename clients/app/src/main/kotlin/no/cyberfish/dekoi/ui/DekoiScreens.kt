package no.cyberfish.dekoi.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.hourlyUptime
import no.cyberfish.dekoi.isOnline
import no.cyberfish.dekoi.isSettingUp
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.UptimeStrip
import java.time.Instant

private const val MAX_NAME = 40
private const val UPTIME_HOURS = 7 * 24
private val STEP_DOT = 10.dp

/** Online, offline or setting up, in words. */
@StringRes
fun statusWord(
    device: Device,
    now: Instant,
): Int =
    when {
        device.isSettingUp -> R.string.device_setting_up
        device.isOnline(now) -> R.string.device_online
        else -> R.string.device_offline
    }

@Composable
fun DekoiScreen(
    data: OwnerData,
    now: Instant,
    deviceId: String,
    onRename: (Device, String) -> Unit,
    onRemove: (Device) -> Unit,
    onBack: () -> Unit,
) {
    val device = data.devices.firstOrNull { it.id == deviceId }
    if (device == null) {
        MissingScreen(R.string.home_your_dekoi, data.loaded, R.drawable.ic_device_unknown, R.string.dekoi_missing, onBack)
        return
    }
    var confirming by remember { mutableStateOf(false) }
    Screen(device.name, onBack = onBack) {
        item { Hero(device, now) }
        if (device.isSettingUp) item { SetupSteps(device, now) }
        item { UptimeSection(device, data, now) }
        if (device.services.isNotEmpty()) {
            item {
                InsetSection(title = stringResource(R.string.device_decoys)) {
                    device.services.forEach { InsetRow(stringResource(serviceName(it)).replaceFirstChar(Char::titlecase)) }
                }
            }
        }
        item {
            InsetSection(title = stringResource(R.string.dekoi_details)) {
                device.agentVersion?.let { KeyValueRow(stringResource(R.string.device_version), it) }
                device.localIp?.let { KeyValueRow(stringResource(R.string.device_address), it) }
                KeyValueRow(stringResource(R.string.dekoi_linked_since), absoluteTime(device.createdAt))
            }
        }
        item { Rename(device, onRename) }
        item {
            InsetSection {
                InsetRow(
                    stringResource(R.string.device_remove),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirming = true },
                    chevron = false,
                )
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = R.string.device_remove_confirm_title,
            body = R.string.device_remove_confirm_body,
            confirm = R.string.action_remove,
            onConfirm = {
                confirming = false
                onRemove(device)
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun Hero(
    device: Device,
    now: Instant,
) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        BrandTile(hero = true)
        Text(stringResource(statusWord(device, now)), style = MaterialTheme.typography.titleMedium)
        Text(
            device.lastSeenAt?.let { stringResource(R.string.device_checked_in, relativeTime(it, now)) }
                ?: stringResource(R.string.device_never_checked_in),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun UptimeSection(
    device: Device,
    data: OwnerData,
    now: Instant,
) {
    val hours = hourlyUptime(device, data.events, now, UPTIME_HOURS)
    val known = hours.count { it != Uptime.UNKNOWN }
    val summary =
        if (known == 0) {
            stringResource(R.string.dekoi_uptime_none)
        } else {
            pluralStringResource(R.plurals.dekoi_uptime_summary, known, hours.count { it == Uptime.ONLINE }, known)
        }
    InsetSection(title = stringResource(R.string.last_seven_days), footer = summary) {
        Box(Modifier.padding(Dimens.spaceL)) { UptimeStrip(hours, summary) }
    }
}

@Composable
private fun SetupSteps(
    device: Device,
    now: Instant,
) {
    val steps =
        listOf(
            R.string.setup_linked to true,
            R.string.setup_first_start to (device.registeredAt != null),
            R.string.setup_online to (device.lastSeenAt != null),
            R.string.setup_protecting to device.services.isNotEmpty(),
        )
    val done = LocalSeverityColors.current.safe.color
    val pending = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.section)) {
        InsetSection(title = stringResource(R.string.dekoi_setup)) {
            Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                steps.forEach { (label, finished) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                        Box(Modifier.size(STEP_DOT).background(if (finished) done else pending, CircleShape))
                        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (device.needsSetupHelp(now)) {
            InsetSection(title = stringResource(R.string.setup_help_title)) {
                Text(
                    stringResource(R.string.setup_help_body),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Dimens.spaceL),
                )
            }
        }
    }
}

@Composable
private fun Rename(
    device: Device,
    onRename: (Device, String) -> Unit,
) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    InsetSection {
        Column(Modifier.padding(Dimens.spaceL)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_NAME) },
                label = { Text(stringResource(R.string.device_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (name.isNotBlank() && name != device.name) {
                TextButton(onClick = { onRename(device, name.trim()) }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}
