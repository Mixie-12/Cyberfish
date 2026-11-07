package no.cyberfish.dekoi.ui.network

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostOs
import no.cyberfish.dekoi.LABEL_MAX
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ROOM_MAX
import no.cyberfish.dekoi.contains
import no.cyberfish.dekoi.eventsPerDay
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.isNew
import no.cyberfish.dekoi.isOnline
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.ownerText
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.savedOwnerText
import no.cyberfish.dekoi.suggestedRooms
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.absoluteTime
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.relativeTime
import java.time.Instant
import java.time.ZoneId

private const val HISTORY_DAYS = 14
private val SPARKLINE = 32.dp

/** One host on the network (docs/design.md, "Device detail"). Its names, model and services are attacker text: plain text only. */
@Composable
fun HostDetailScreen(
    data: OwnerData,
    now: Instant,
    deviceId: String,
    mac: String,
    onTrust: (NetworkHost, Boolean) -> Unit,
    onNameAndRoom: (NetworkHost, String?, String?) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val host = data.hosts.firstOrNull { it.deviceId == deviceId && it.mac == mac }
    if (host == null) {
        MissingScreen(R.string.tab_network, data.loaded, R.drawable.ic_device_unknown, R.string.host_missing, onBack)
        return
    }
    val resources = LocalResources.current
    // The most serious recent alert it caused; Severity lists the most serious first.
    val alert = recentIntrusions(data.events, data.trustedMacs, now).filter { it.srcMac == host.mac }.minByOrNull { it.severity }
    var editing by rememberSaveable { mutableStateOf(false) }
    Screen(stringResource(R.string.host_title), onBack = onBack) {
        item { Hero(host, hostDisplayName(host, resources::getString), now) }
        alert?.let { recent -> item { Attention(recent) { onNavigate(Route.AlertDetail(recent.id)) } } }
        item { History(host, data.events, now) { onNavigate(Route.FilteredAlerts(mac = host.mac)) } }
        item { About(host) }
        item { Connection(host, data.networks) }
        item {
            InsetSection {
                InsetRow(
                    stringResource(R.string.host_known),
                    modifier = Modifier.toggleable(host.trusted, role = Role.Switch) { onTrust(host, it) },
                    trailing = { Switch(checked = host.trusted, onCheckedChange = null) },
                )
                InsetRow(
                    stringResource(R.string.host_edit),
                    subtitle = listOfNotNull(host.label, host.room).joinToString(" · ").ifEmpty { stringResource(R.string.host_edit_hint) },
                    onClick = { editing = true },
                )
            }
        }
    }
    if (editing) {
        EditSheet(
            host,
            unnamed = hostDisplayName(host.copy(label = null), resources::getString),
            rooms = suggestedRooms(data.hosts.mapNotNull { it.room }, stringArrayResource(R.array.host_rooms).toList()),
            onSave = { label, room -> onNameAndRoom(host, label, room) },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun Hero(
    host: NetworkHost,
    name: String,
    now: Instant,
) {
    val online = host.isOnline(now)
    HudFrame(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            KindTile(host.kind, hero = true)
            Text(name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                stringResource(looksLike(host.kind)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            host.room?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                StatusDot(online)
                Text(
                    if (online) {
                        stringResource(R.string.host_online_now)
                    } else {
                        stringResource(R.string.host_last_seen_ago, relativeTime(host.lastSeenAt, now))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                when {
                    host.trusted -> Tag(stringResource(R.string.network_known))
                    host.isNew(now) -> Tag(stringResource(R.string.network_new), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun Attention(
    alert: Event,
    onOpen: () -> Unit,
) {
    InsetSection(title = stringResource(R.string.network_attention)) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            SeverityChip(alert.severity)
            Text(stringResource(R.string.host_attention_body), style = MaterialTheme.typography.bodyLarge)
        }
        InsetRow(stringResource(R.string.host_what_to_do), onClick = onOpen)
    }
}

/** Alerts from this host per day for two weeks, from the events the app has loaded. */
@Composable
private fun History(
    host: NetworkHost,
    events: List<Event>,
    now: Instant,
    onSeeAll: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val perDay = eventsPerDay(events.filter { it.srcMac == host.mac }, now.atZone(zone).toLocalDate(), zone, HISTORY_DAYS).map { it.size }
    val total = perDay.sum()
    InsetSection(title = stringResource(R.string.host_history)) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            Text(
                if (total ==
                    0
                ) {
                    stringResource(R.string.host_history_none)
                } else {
                    pluralStringResource(R.plurals.host_history_count, total, total)
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Sparkline(perDay, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(SPARKLINE))
        }
        InsetRow(stringResource(R.string.host_see_alerts), onClick = onSeeAll)
    }
}

@Composable
private fun About(host: NetworkHost) {
    InsetSection(title = stringResource(R.string.host_about)) {
        KeyValueRow(stringResource(R.string.event_detail_vendor), host.vendor ?: stringResource(R.string.network_unknown_maker))
        host.model?.let { KeyValueRow(stringResource(R.string.host_model), it) }
        host.os?.let { KeyValueRow(stringResource(R.string.host_system), stringResource(osName(it))) }
        host.hostname?.let { KeyValueRow(stringResource(R.string.host_announced_name), it) }
        if (host.services.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(stringResource(R.string.host_services), style = MaterialTheme.typography.bodyLarge)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
                    verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
                ) {
                    host.services.forEach { Tag(it) }
                }
            }
        }
    }
}

@Composable
private fun Connection(
    host: NetworkHost,
    networks: List<Network>,
) {
    val network = networks.firstOrNull { it.deviceId == host.deviceId && it.contains(host.ip) }
    InsetSection(
        title = stringResource(R.string.host_connection),
        footer = stringResource(R.string.host_private_note).takeIf { host.isPrivateMac },
    ) {
        KeyValueRow(stringResource(R.string.host_address), host.ip)
        network?.let { KeyValueRow(stringResource(R.string.host_network), it.cidr) }
        CopyableRow(stringResource(R.string.event_detail_mac), host.mac)
        KeyValueRow(stringResource(R.string.host_first_seen), absoluteTime(host.firstSeenAt))
        KeyValueRow(stringResource(R.string.host_last_seen), absoluteTime(host.lastSeenAt))
    }
}

/**
 * The owner's name and room for a host, both optional. [unnamed] is what
 * the app calls it without a name of its own. Owner text: plain text only,
 * never in a notification (ADR 0013). Material 3 still marks its bottom
 * sheet experimental; this is the app's only use of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditSheet(
    host: NetworkHost,
    unnamed: String,
    rooms: List<String>,
    onSave: (String?, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var label by rememberSaveable(host.mac) { mutableStateOf(host.label.orEmpty()) }
    var room by rememberSaveable(host.mac) { mutableStateOf(host.room.orEmpty()) }
    val close: () -> Unit = { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.padding(start = Dimens.gutter, end = Dimens.gutter, bottom = Dimens.section),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
        ) {
            Text(
                stringResource(R.string.host_edit),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            OutlinedTextField(
                value = label,
                onValueChange = { label = ownerText(it, LABEL_MAX) },
                label = { Text(stringResource(R.string.host_name)) },
                placeholder = { Text(unnamed) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = room,
                onValueChange = { room = ownerText(it, ROOM_MAX) },
                label = { Text(stringResource(R.string.host_room)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.host_suggested_rooms),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                rooms.forEach { suggestion ->
                    FilterChip(
                        selected = room == suggestion,
                        onClick = { room = if (room == suggestion) "" else suggestion },
                        label = { Text(suggestion) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS, Alignment.End)) {
                TextButton(onClick = close) { Text(stringResource(R.string.action_cancel)) }
                OutlinedButton(onClick = {
                    onSave(savedOwnerText(label, LABEL_MAX), savedOwnerText(room, ROOM_MAX))
                    close()
                }) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

@StringRes
private fun osName(os: HostOs): Int =
    when (os) {
        HostOs.ANDROID -> R.string.os_android
        HostOs.IOS -> R.string.os_ios
        HostOs.IPADOS -> R.string.os_ipados
        HostOs.MACOS -> R.string.os_macos
        HostOs.WINDOWS -> R.string.os_windows
        HostOs.LINUX -> R.string.os_linux
        HostOs.CHROMEOS -> R.string.os_chromeos
        HostOs.TVOS -> R.string.os_tvos
        HostOs.WEBOS -> R.string.os_webos
        HostOs.TIZEN -> R.string.os_tizen
        HostOs.FIRE_OS -> R.string.os_fire_os
        HostOs.OTHER -> R.string.os_other
    }
