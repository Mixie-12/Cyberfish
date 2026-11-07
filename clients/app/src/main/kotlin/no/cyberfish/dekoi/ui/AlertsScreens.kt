package no.cyberfish.dekoi.ui

import android.text.format.DateFormat
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.incidentSubtitle
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.subject
import no.cyberfish.dekoi.timeline
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SeverityTile
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.severityGlyph
import no.cyberfish.dekoi.ui.motion.sharedSeverity
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
            host?.let { stringResource(if (known) R.string.known_device else looksLike(it.kind)) },
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
                val detail = Route.AlertDetail(incident.lead.id)
                TimelineEntry(
                    incident,
                    data.hostFor(incident.mac),
                    clock,
                    detail,
                    first = index == 0,
                    last = index == incidents.lastIndex,
                    modifier = if (motion) Modifier.animateItem() else Modifier,
                ) { onNavigate(detail) }
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
        label.headingCase(),
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
 * glyph and word, the title and "14 times · 15:13–15:20". Seen incidents
 * are grey and quieter; incidents from a device the owner knows are dimmed.
 * The glyph and word move into the hero of [detail], the screen it opens.
 */
@Composable
private fun TimelineEntry(
    incident: Incident,
    host: NetworkHost?,
    clock: DateTimeFormatter,
    detail: Route,
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
                Modifier.sharedSeverity(detail).onPlaced { dotY = it.positionInParent().y + it.size.height / 2f },
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
                    severityWord.headingCase(),
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
