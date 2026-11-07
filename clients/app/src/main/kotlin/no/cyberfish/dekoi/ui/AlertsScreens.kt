package no.cyberfish.dekoi.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.AlertFilter
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.Incident
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SEPARATOR
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.amount
import no.cyberfish.dekoi.cancelAlertNotification
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.incidentSubtitle
import no.cyberfish.dekoi.isIntrusion
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.steps
import no.cyberfish.dekoi.subject
import no.cyberfish.dekoi.timeline
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.SeverityTile
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.severityGlyph
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val KNOWN_ALPHA = 0.6f
private const val GLOW_ALPHA = 0.45f
private const val SKELETON_ROWS = 4
private const val SKELETON_TITLE = 0.8f
private const val SKELETON_SUBTITLE = 0.5f
private val RAIL = 16.dp
private val DOT = 8.dp
private val GLOW = 12.dp
private val GLYPH = 16.dp
private val CHEVRON = 20.dp
private val ENTRY_PADDING = 12.dp
private val SKELETON_LABEL = 96.dp
private val SKELETON_LINE = 16.dp

/** The event's title in the owner's words; an event about another device names what [source] looks like. */
@Composable
fun eventTitle(
    event: Event,
    source: NetworkHost?,
): String {
    val text = eventText(event)
    if (!text.aboutSource) return stringResource(text.title)
    return stringResource(text.title, stringResource(subject(source?.kind ?: HostKind.UNKNOWN)))
}

/** One event in a list without a timeline, such as Home's latest events: severity tile, title, the source and when. */
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
            host?.let { stringResource(if (known) R.string.alert_known else looksLike(it.kind)) },
            amount(event)?.let { pluralStringResource(it.words, it.count, it.count) },
            relativeTime(event.occurredAt, now),
        ).joinToString(SEPARATOR)
    InsetRow(
        eventTitle(event, host),
        modifier = if (known) Modifier.alpha(KNOWN_ALPHA) else Modifier,
        subtitle = subtitle,
        leading = { SeverityTile(event.severity) },
        onClick = onClick,
    )
}

/** Times of day in the phone's format, language and time zone. */
@Composable
fun rememberClock(zone: ZoneId): DateTimeFormatter {
    val locale = LocalLocale.current.platformLocale
    return remember(locale, zone) { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone) }
}

/** "Today", "Yesterday", or the weekday and date, with the year when it is not this year. */
@Composable
fun dayLabel(
    day: LocalDate,
    today: LocalDate,
): String {
    val locale = LocalLocale.current.platformLocale
    val skeleton = if (day.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy"
    return when (day) {
        today -> stringResource(R.string.alerts_today)
        today.minusDays(1) -> stringResource(R.string.alerts_yesterday)
        else -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(day)
    }
}

/**
 * The Alerts tab: incidents on a calm timeline, newest first, under a
 * heading per day (ADR 0013). [scope] narrows it for a cross-link; the
 * owner can remove it.
 */
@Composable
fun AlertsScreen(
    data: OwnerData,
    now: Instant,
    scope: Route.FilteredAlerts,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: (() -> Unit)?,
) {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val clock = rememberClock(zone)
    val motion = !LocalReduceMotion.current
    var unread by rememberSaveable { mutableStateOf(false) }
    var severity by rememberSaveable { mutableStateOf<Severity?>(null) }
    var mac by rememberSaveable { mutableStateOf(scope.mac) }
    var service by rememberSaveable { mutableStateOf(scope.service) }
    var day by rememberSaveable { mutableStateOf(scope.day?.let(LocalDate::parse)) }
    val days = timeline(data.events, AlertFilter(unread, severity, mac, service, day), zone)
    val scopes =
        listOfNotNull(
            mac?.let { stringResource(R.string.alerts_filter_device) to { mac = null } },
            service?.let { stringResource(R.string.alerts_filter_decoy, stringResource(serviceName(it))) to { service = null } },
            day?.let { dayLabel(it, today) to { day = null } },
        )
    val showAll = {
        unread = false
        severity = null
        mac = null
        service = null
        day = null
    }
    Screen(
        stringResource(R.string.tab_alerts),
        listState = listState,
        onBack = onBack,
        subtitle = { StaleLabel(data.updatedAt, now) },
        spacing = 0.dp,
    ) {
        data.problem?.let { item(key = "problem") { ErrorBanner(stringResource(it), onRefresh, Modifier.padding(top = Dimens.section)) } }
        item(key = "filters") { Filters(unread, severity, scopes, onUnread = { unread = it }, onSeverity = { severity = it }) }
        when {
            !data.loaded && data.problem == null -> {
                item(key = "loading") { TimelineSkeleton() }
            }

            data.loaded && data.events.isEmpty() -> {
                item(key = "empty") { EmptyState(R.drawable.ic_check_circle, stringResource(R.string.alerts_empty)) }
            }

            data.loaded && days.isEmpty() -> {
                item(key = "none") {
                    EmptyState(R.drawable.ic_notifications, stringResource(R.string.alerts_empty_filtered)) {
                        TextButton(onClick = showAll) { Text(stringResource(R.string.alerts_show_all)) }
                    }
                }
            }
        }
        days.forEach { (date, incidents) ->
            stickyHeader(key = "day-$date", contentType = "day") { DayHeader(dayLabel(date, today)) }
            // The first event stays the same while an incident grows, so it keys the entry.
            itemsIndexed(
                incidents,
                key = { _, incident -> incident.events.last().id },
                contentType = { _, _ -> "incident" },
            ) { index, incident ->
                TimelineEntry(
                    incident,
                    data.hostFor(incident.mac),
                    clock,
                    first = index == 0,
                    last = index == incidents.lastIndex,
                    modifier = if (motion) Modifier.animateItem() else Modifier,
                ) { onNavigate(Route.AlertDetail(incident.lead.id)) }
            }
        }
    }
}

@Composable
private fun Filters(
    unread: Boolean,
    severity: Severity?,
    scopes: List<Pair<String, () -> Unit>>,
    onUnread: (Boolean) -> Unit,
    onSeverity: (Severity?) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = Dimens.spaceL),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        scopes.forEach { (label, onRemove) ->
            InputChip(
                selected = true,
                onClick = onRemove,
                label = { Text(label) },
                trailingIcon = {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.alerts_filter_remove),
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
            )
        }
        FilterChip(selected = !unread, onClick = { onUnread(false) }, label = { Text(stringResource(R.string.alerts_filter_all)) })
        FilterChip(selected = unread, onClick = { onUnread(true) }, label = { Text(stringResource(R.string.alerts_filter_unread)) })
        SeverityMenu(severity, onSeverity)
    }
}

/** One chip for the severity filter, so the row stays short; the choices open in a menu. */
@Composable
private fun SeverityMenu(
    selected: Severity?,
    onSelect: (Severity?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    fun choose(severity: Severity?) {
        onSelect(severity)
        open = false
    }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { open = true },
            label = { Text(stringResource(selected?.let(::severityLabel) ?: R.string.alerts_filter_severity)) },
            leadingIcon =
                if (selected == null) {
                    null
                } else {
                    { SeverityGlyph(selected, Modifier.size(FilterChipDefaults.IconSize)) }
                },
            trailingIcon = {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.alerts_filter_any_severity)) }, onClick = { choose(null) })
            Severity.entries.forEach { severity ->
                DropdownMenuItem(
                    text = { Text(stringResource(severityLabel(severity))) },
                    leadingIcon = { SeverityGlyph(severity) },
                    onClick = { choose(severity) },
                )
            }
        }
    }
}

@Composable
private fun SeverityGlyph(
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    Icon(
        painterResource(severityGlyph(severity)),
        contentDescription = null,
        modifier = modifier,
        tint = LocalSeverityColors.current.of(severity).color,
    )
}

/** "TODAY": a small uppercase label over the day's part of the timeline. */
@Composable
private fun DayHeader(label: String) {
    Text(
        label.uppercase(LocalLocale.current.platformLocale),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = Dimens.section, bottom = Dimens.spaceS)
                .semantics { heading() },
    )
}

/**
 * One incident on the timeline, without a box: a dot on the hairline that
 * glows in the severity's colour until the incident is seen, the severity
 * glyph and word, the title and "14 events · 15:13–15:20". Seen incidents
 * are grey and quieter; incidents from a device the owner knows are dimmed.
 */
@Composable
private fun TimelineEntry(
    incident: Incident,
    host: NetworkHost?,
    clock: DateTimeFormatter,
    first: Boolean,
    last: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val known = host?.trusted == true
    val seen = incident.seen
    val accent = if (seen) colors.outline else LocalSeverityColors.current.of(incident.severity).color
    val subtitle = incidentSubtitle(incident, known, clock::format, resources::getString) { id, n -> resources.getQuantityString(id, n, n) }
    val severityWord = stringResource(severityLabel(incident.severity))
    val state = if (seen) severityWord else stringResource(R.string.alerts_unread_state, severityWord)
    // Where the dot sits: level with the severity line, wherever font scaling puts it.
    var dotY by remember { mutableFloatStateOf(0f) }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (known) KNOWN_ALPHA else 1f)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = state }
            .drawBehind {
                val x = RAIL.toPx() / 2
                val center = Offset(if (layoutDirection == LayoutDirection.Ltr) x else size.width - x, ENTRY_PADDING.toPx() + dotY)
                drawLine(
                    colors.outlineVariant,
                    Offset(center.x, if (first) center.y else 0f),
                    Offset(center.x, if (last) center.y else size.height),
                    Dimens.hairline.toPx(),
                )
                drawDot(center, accent, colors.background, glowing = !seen)
            }.padding(vertical = ENTRY_PADDING),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Spacer(Modifier.width(RAIL))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Row(
                Modifier.onPlaced { dotY = it.positionInParent().y + it.size.height / 2f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                Icon(
                    painterResource(severityGlyph(incident.severity)),
                    contentDescription = null,
                    modifier = Modifier.size(GLYPH),
                    tint = accent,
                )
                Text(
                    severityWord.uppercase(LocalLocale.current.platformLocale),
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            Text(
                eventTitle(incident.lead, host),
                style = if (seen) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
                color = if (seen) colors.onSurfaceVariant else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodyMedium.tabular(), color = colors.onSurfaceVariant)
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(CHEVRON).align(Alignment.CenterVertically),
            tint = colors.outline,
        )
    }
}

/** A filled dot in a soft glow of its colour, or, once seen, a hollow grey ring over the line. */
private fun DrawScope.drawDot(
    center: Offset,
    color: Color,
    background: Color,
    glowing: Boolean,
) {
    val radius = DOT.toPx() / 2
    if (glowing) {
        val glow = GLOW.toPx()
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = GLOW_ALPHA), Color.Transparent), center, glow), glow, center)
        drawCircle(color, radius, center)
        return
    }
    drawCircle(background, radius, center)
    drawCircle(color, radius, center, style = Stroke(Dimens.hairline.toPx()))
}

@Composable
private fun TimelineSkeleton() {
    Column(Modifier.padding(top = Dimens.section), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        Skeleton(Modifier.width(SKELETON_LABEL).height(SKELETON_LINE))
        repeat(SKELETON_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                Box(Modifier.width(RAIL), contentAlignment = Alignment.Center) { Skeleton(Modifier.size(DOT), CircleShape) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                    Skeleton(Modifier.fillMaxWidth(SKELETON_TITLE).height(SKELETON_LINE))
                    Skeleton(Modifier.fillMaxWidth(SKELETON_SUBTITLE).height(SKELETON_LINE))
                }
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
        eventTitle(event, host),
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
        if (event.isIntrusion && host != null) item { Trust(host, onTrust) }
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
            if (steps.isEmpty()) Text(stringResource(R.string.alert_nothing_to_do), style = MaterialTheme.typography.bodyLarge)
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                    Text("${index + 1}.", style = MaterialTheme.typography.bodyLarge.tabular(), color = MaterialTheme.colorScheme.primary)
                    Text(stringResource(step.text), style = MaterialTheme.typography.bodyLarge)
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
