package no.cyberfish.dekoi.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.cancelAlertNotification
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.steps
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.SeverityTile
import no.cyberfish.dekoi.ui.components.StaleLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val KNOWN_ALPHA = 0.6f
private const val SEPARATOR = " · "

/** The event's title in the owner's words. */
@Composable
fun eventTitle(event: Event): String {
    val text = eventText(event)
    val service = text.service?.let { stringResource(it) }
    return if (service != null) stringResource(text.title, service) else stringResource(text.title)
}

/** One alert in a list: severity tile, title, what the source looks like, how often and when. */
@Composable
fun AlertRow(
    event: Event,
    host: NetworkHost?,
    now: Instant,
    onClick: () -> Unit,
) {
    val known = host?.trusted == true
    val subtitle =
        listOfNotNull(
            stringResource(R.string.alert_known).takeIf { known },
            host?.let { stringResource(looksLike(it.kind)) },
            pluralStringResource(R.plurals.alert_times, event.count, event.count).takeIf { event.count > 1 },
            relativeTime(event.occurredAt, now),
        ).joinToString(SEPARATOR)
    InsetRow(
        eventTitle(event),
        modifier = if (known) Modifier.alpha(KNOWN_ALPHA) else Modifier,
        subtitle = subtitle,
        leading = { SeverityTile(event.severity) },
        onClick = onClick,
    )
}

/** Whether [event] passes this filter; [zone] decides which day an event fell on. */
private fun Route.FilteredAlerts.matches(
    event: Event,
    zone: ZoneId,
): Boolean =
    (mac == null || event.srcMac == mac) &&
        (service == null || event.service == service) &&
        (
            day == null || event.occurredAt
                .atZone(zone)
                .toLocalDate()
                .toString() == day
        )

/** All alerts, newest first and grouped by day; [filter] narrows them for a cross-link. */
@Composable
fun AlertsScreen(
    data: OwnerData,
    now: Instant,
    filter: Route.FilteredAlerts,
    listState: LazyListState,
    onNavigate: (Route) -> Unit,
    onBack: (() -> Unit)?,
) {
    val zone = ZoneId.systemDefault()
    val days = data.events.filter { filter.matches(it, zone) }.groupBy { it.occurredAt.atZone(zone).toLocalDate() }
    val dayTitle = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    Screen(stringResource(R.string.tab_alerts), listState = listState, onBack = onBack, subtitle = { StaleLabel(data.updatedAt, now) }) {
        if (data.loaded && days.isEmpty()) item { EmptyState(R.drawable.ic_notifications, stringResource(R.string.alerts_empty)) }
        items(days.entries.toList(), key = { it.key.toEpochDay() }) { (day, events) ->
            InsetSection(title = dayTitle.format(day)) {
                events.forEach { event -> AlertRow(event, data.hostFor(event.srcMac), now) { onNavigate(Route.AlertDetail(event.id)) } }
            }
        }
    }
}

@Composable
fun AlertDetailScreen(
    data: OwnerData,
    eventId: Long,
    onTrust: (NetworkHost, Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Seeing the alert here makes its notification redundant.
    LaunchedEffect(eventId) { cancelAlertNotification(context, eventId) }
    val event = data.events.firstOrNull { it.id == eventId }
    if (event == null) {
        MissingScreen(R.string.tab_alerts, data.loaded, R.drawable.ic_notifications, R.string.alert_missing, onBack)
        return
    }
    val host = data.hostFor(event.srcMac)
    Screen(
        eventTitle(event),
        onBack = onBack,
        subtitle = {
            Row(
                Modifier.padding(top = Dimens.spaceS),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            ) {
                SeverityChip(event.severity)
                Text(
                    absoluteTime(event.occurredAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        item {
            InsetSection(title = stringResource(R.string.event_why)) {
                Text(
                    stringResource(eventText(event).explanation),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Dimens.spaceL),
                )
            }
        }
        host?.let { item { SourceDevice(it, onNavigate) } }
        item { WhatToDo(event, host) }
        // Every event with a source device on the network, a new one included, offers to mark it as known.
        host?.let { item { Trust(it, onTrust) } }
        item { TechnicalDetails(event, host) }
    }
}

@Composable
private fun SourceDevice(
    host: NetworkHost,
    onNavigate: (Route) -> Unit,
) {
    val resources = LocalResources.current
    InsetSection(title = stringResource(R.string.alert_device)) {
        InsetRow(
            hostDisplayName(host, resources::getString),
            subtitle = stringResource(looksLike(host.kind)),
            leading = { KindTile(host.kind) },
            onClick = { onNavigate(Route.HostDetail(host.deviceId, host.mac)) },
        )
    }
}

@Composable
private fun WhatToDo(
    event: Event,
    host: NetworkHost?,
) {
    val steps = steps(event.kind, host?.kind ?: HostKind.UNKNOWN)
    InsetSection(title = stringResource(R.string.event_advice)) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            if (steps.isEmpty()) Text(stringResource(eventText(event).advice), style = MaterialTheme.typography.bodyLarge)
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge.tabular(), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun Trust(
    host: NetworkHost,
    onTrust: (NetworkHost, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        if (host.trusted) {
            Text(
                stringResource(R.string.event_trusted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onTrust(host, false) }) { Text(stringResource(R.string.event_untrust)) }
        } else {
            OutlinedButton(
                onClick = { onTrust(host, true) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.event_trust)) }
        }
    }
}

/** Collapsed by default: addresses and ports are for whoever wants them. Values from the device are plain text. */
@Composable
private fun TechnicalDetails(
    event: Event,
    host: NetworkHost?,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    InsetSection(Modifier.animateContentSize(spec)) {
        InsetRow(
            stringResource(R.string.event_details),
            modifier =
                Modifier.semantics {
                    if (open) {
                        collapse {
                            open = false
                            true
                        }
                    } else {
                        expand {
                            open = true
                            true
                        }
                    }
                },
            trailing = {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (open) 180f else 0f),
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
            onClick = { open = !open },
            chevron = false,
        )
        if (!open) return@InsetSection
        KeyValueRow(stringResource(R.string.event_detail_time), absoluteTime(event.occurredAt))
        if (event.count > 1) InsetRow(pluralStringResource(R.plurals.event_repeated, event.count, event.count))
        event.srcIp?.let { KeyValueRow(stringResource(R.string.event_detail_source), it) }
        event.srcMac?.let { CopyableRow(stringResource(R.string.event_detail_mac), it) }
        host?.vendor?.let { KeyValueRow(stringResource(R.string.event_detail_vendor), it) }
        event.dstPort?.let { KeyValueRow(stringResource(R.string.event_detail_port), it.toString()) }
        event.detail.username?.let { KeyValueRow(stringResource(R.string.event_detail_username), it) }
        event.detail.path?.let { KeyValueRow(stringResource(R.string.event_detail_path), it) }
        (event.detail.clientVersion ?: event.detail.userAgent)?.let { KeyValueRow(stringResource(R.string.event_detail_client), it) }
        event.detail.command?.let { KeyValueRow(stringResource(R.string.event_detail_command), it) }
        event.detail.signature?.let { KeyValueRow(stringResource(R.string.event_detail_signature), it) }
        event.detail.category?.let { KeyValueRow(stringResource(R.string.event_detail_category), it) }
        event.detail.claimedIp?.let { KeyValueRow(stringResource(R.string.event_detail_claimed_ip), it) }
        event.detail.previousMac?.let { CopyableRow(stringResource(R.string.event_detail_previous_mac), it) }
        event.detail.serverIp?.let { KeyValueRow(stringResource(R.string.event_detail_server), it) }
        event.detail.protocol?.let { KeyValueRow(stringResource(R.string.event_detail_protocol), it.uppercase()) }
        event.detail.targets?.let { KeyValueRow(stringResource(R.string.event_detail_targets), it.toString()) }
        if (event.detail.ports.isNotEmpty()) KeyValueRow(stringResource(R.string.event_detail_ports), event.detail.ports.joinToString(", "))
    }
}
