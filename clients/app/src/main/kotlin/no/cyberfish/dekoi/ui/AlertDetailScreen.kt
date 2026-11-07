package no.cyberfish.dekoi.ui

import android.icu.text.ListFormatter
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.DEKOI_STATUS_KINDS
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.Fact
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.Incident
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SEPARATOR
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.Step
import no.cyberfish.dekoi.Threat
import no.cyberfish.dekoi.ThreatLevel
import no.cyberfish.dekoi.amount
import no.cyberfish.dekoi.cancelAlertNotification
import no.cyberfish.dekoi.contains
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.facts
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.incidentSubtitle
import no.cyberfish.dekoi.incidents
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.steps
import no.cyberfish.dekoi.threat
import no.cyberfish.dekoi.threatLabel
import no.cyberfish.dekoi.trustNeedsConfirmation
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.severityGlyph
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val ALARM_PULSES = 3
private const val ALARM_MS = 3000
private const val ALARM_RINGS = 2
private const val RING_ALPHA = 0.45f
private const val GLOW_ALPHA = 0.3f
private const val SHOWN_EVENTS = 8
private const val MAX_VALUES = 10

// As many as the agent reports for one scan.
private const val MAX_PORTS = 64

private const val HUD_ALPHA = 0.15f
private val ALARM_AREA = 192.dp
private val RING_WIDTH = 3.dp
private val HUD_CUT = 12.dp
private val STEP_MARK = 28.dp
private val SMALL_ICON = 18.dp
private val EVENT_DOT = 8.dp

/** A device that DeKoi saw within two inventory rounds (15 minutes each, device/agent) is online. */
private val HOST_ONLINE = Duration.ofMinutes(30)

/**
 * One incident (ADR 0013): how serious, whether it is a real threat, what
 * to do now, then what happened, which device, and the technical details.
 * A real alarm opens with the alarm hero; everything else with a calm band.
 * Opening it marks its events seen and clears their notifications.
 */
@Composable
fun AlertDetailScreen(
    data: OwnerData,
    now: Instant,
    eventId: Long,
    onAcknowledge: (List<Long>) -> Unit,
    onTrust: (NetworkHost, Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val incident = remember(data.events, eventId) { incidents(data.events).firstOrNull { it.events.any { event -> event.id == eventId } } }
    val ids = incident?.events?.map { it.id } ?: listOf(eventId)
    // Seeing the incident here makes its notifications redundant.
    LaunchedEffect(ids) { ids.forEach { cancelAlertNotification(context, it) } }
    val unseen = incident?.unseenIds.orEmpty()
    LaunchedEffect(unseen) { if (unseen.isNotEmpty()) onAcknowledge(unseen) }
    if (incident == null) {
        MissingScreen(R.string.tab_alerts, data.loaded, R.drawable.ic_notifications, R.string.alert_missing, onBack)
        return
    }
    val host = data.hostFor(incident.mac)
    val threat = threat(incident.kinds, knownSource = host?.trusted == true)
    val alarm = incident.severity == Severity.CRITICAL && threat.level == ThreatLevel.LIKELY
    val zone = ZoneId.systemDefault()
    val clock = rememberClock(zone)
    val gateways = data.networks.filter { it.deviceId == incident.lead.deviceId }.mapNotNullTo(mutableSetOf()) { it.gateway }
    Screen(title = null, onBack = onBack) {
        item { if (alarm) AlarmHero(incident, host, now, clock) else Band(incident, host, now, clock) }
        item { ThreatCard(threat) }
        item { Steps(incident, host, urgent = alarm) }
        // Every incident from a device on the network, a new one included, offers to mark it as known.
        host?.let { item { Trust(it, incident.kinds.any(::trustNeedsConfirmation), onTrust) } }
        item { WhatHappened(incident, gateways, clock) }
        item { Source(incident, host, data, now, onNavigate) }
        item { TechnicalDetails(incident, host) }
    }
}

/** "Today · 14 times · 15:13–15:20". */
@Composable
private fun summary(
    incident: Incident,
    now: Instant,
    clock: DateTimeFormatter,
): String {
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val day = dayLabel(incident.end.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate())
    val rest = incidentSubtitle(incident, false, clock::format, resources::getString) { id, n -> resources.getQuantityString(id, n, n) }
    return day + SEPARATOR + rest
}

/** Severity glyph and word, title, consequence, when and how much: still, unboxed, coloured only in the glyph and word. */
@Composable
private fun Band(
    incident: Incident,
    host: NetworkHost?,
    now: Instant,
    clock: DateTimeFormatter,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        SeverityLine(incident.severity)
        Headline(incident, host, summary(incident, now, clock), TextAlign.Start)
    }
}

/**
 * A real alarm, inside a HUD frame: the source's kind tile at the centre of
 * rings that pulse outwards in the critical colour for about three seconds,
 * then a steady glow. Only very likely attacks get it; with animations off
 * it is the steady glow at once.
 */
@Composable
private fun AlarmHero(
    incident: Incident,
    host: NetworkHost?,
    now: Instant,
    clock: DateTimeFormatter,
) {
    val critical = LocalSeverityColors.current.critical.color
    // Once per visit: scrolling the hero away and back does not sound it again.
    var played by rememberSaveable { mutableStateOf(false) }
    val still = played || LocalReduceMotion.current
    val progress = remember { Animatable(if (still) ALARM_PULSES.toFloat() else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(ALARM_PULSES.toFloat(), tween(ALARM_MS, easing = LinearEasing))
        played = true
    }
    HudFrame {
        Column(
            Modifier.fillMaxWidth().padding(Dimens.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Box(Modifier.size(ALARM_AREA).drawBehind { drawAlarm(progress.value, critical) }, contentAlignment = Alignment.Center) {
                KindTile(host?.kind ?: HostKind.UNKNOWN, hero = true, contentColor = critical)
            }
            SeverityLine(Severity.CRITICAL)
            Headline(incident, host, summary(incident, now, clock), TextAlign.Center)
        }
    }
}

/**
 * The HUD frame of ADR 0020 around a hero: a faint accent line with cut
 * corners and nothing else. Drawn here until the shared one is in
 * ui/components; the two are meant to be swapped one for the other.
 */
@Composable
private fun HudFrame(content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth().border(Dimens.hairline, MaterialTheme.colorScheme.primary.copy(alpha = HUD_ALPHA), CutCornerShape(HUD_CUT)),
    ) { content() }
}

/**
 * Rings grow from the tile to the edge and fade, [ALARM_RINGS] at a time,
 * for [ALARM_PULSES] pulses; the glow builds up meanwhile and stays.
 * [progress] runs from 0 to [ALARM_PULSES].
 */
private fun DrawScope.drawAlarm(
    progress: Float,
    color: Color,
) {
    val inner = Dimens.heroTile.toPx() / 2
    val outer = size.minDimension / 2
    if (progress < ALARM_PULSES) {
        for (ring in 0 until ALARM_RINGS) {
            val phase = (progress + ring.toFloat() / ALARM_RINGS) % 1f
            drawCircle(
                color.copy(alpha = RING_ALPHA * (1 - phase)),
                radius = inner + (outer - inner) * phase,
                style = Stroke(RING_WIDTH.toPx()),
            )
        }
    }
    val glow = GLOW_ALPHA * (progress / ALARM_PULSES)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), center, outer), outer)
}

/** The severity glyph and its word in capitals, in the severity's colour. */
@Composable
private fun SeverityLine(severity: Severity) {
    val color = LocalSeverityColors.current.of(severity).color
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Icon(painterResource(severityGlyph(severity)), contentDescription = null, tint = color)
        Text(
            stringResource(severityLabel(severity)).uppercase(LocalLocale.current.platformLocale),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

@Composable
private fun Headline(
    incident: Incident,
    host: NetworkHost?,
    summary: String,
    align: TextAlign,
) {
    val colors = MaterialTheme.colorScheme
    Text(
        eventTitle(incident.lead, host),
        style = MaterialTheme.typography.headlineSmall,
        color = colors.onSurface,
        textAlign = align,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Text(
        stringResource(eventText(incident.lead).consequence),
        style = MaterialTheme.typography.bodyLarge,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        summary,
        style = MaterialTheme.typography.labelMedium.tabular(),
        color = colors.onSurfaceVariant,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Is this a real threat?" and the honest answer. Only a very likely attack is in the critical colour. */
@Composable
private fun ThreatCard(threat: Threat) {
    val critical = LocalSeverityColors.current.critical.color
    val verdictColor = if (threat.level == ThreatLevel.LIKELY) critical else MaterialTheme.colorScheme.onSurface
    InsetSection(title = stringResource(R.string.threat_title)) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(stringResource(threatLabel(threat.level)), style = MaterialTheme.typography.titleMedium, color = verdictColor)
            Text(
                stringResource(threat.reason),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The numbered steps as a checklist the owner ticks off. The ticks live in this screen only; nothing stores them. */
@Composable
private fun Steps(
    incident: Incident,
    host: NetworkHost?,
    urgent: Boolean,
) {
    val steps = steps(incident.lead.kind, host?.kind ?: HostKind.UNKNOWN)
    val mac = incident.mac
    var done by rememberSaveable(incident.events.last().id) { mutableStateOf(emptySet<Int>()) }
    InsetSection(title = stringResource(if (urgent) R.string.alert_do_now else R.string.event_advice)) {
        if (steps.isEmpty()) {
            Text(
                stringResource(R.string.alert_nothing_to_do),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(Dimens.spaceL),
            )
        }
        steps.forEachIndexed { index, step ->
            StepRow(index + 1, step, index in done) { done = if (index in done) done - index else done + index }
            // The address the step refers to, to paste into the router's app.
            if (step.usesHardwareAddress && mac != null) CopyableRow(stringResource(R.string.event_detail_mac), mac)
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    step: Step,
    done: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(done, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceM),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        StepMark(number, done)
        Text(
            stringResource(step.text),
            style = MaterialTheme.typography.bodyLarge,
            color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The step's number in a hairline ring, or a tick in a ring of the accent once done. */
@Composable
private fun StepMark(
    number: Int,
    done: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .sizeIn(minWidth = STEP_MARK, minHeight = STEP_MARK)
            .border(Dimens.hairline, if (done) colors.primary else colors.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (done) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(SMALL_ICON),
                tint = colors.primary,
            )
        } else {
            Text(number.toString(), style = MaterialTheme.typography.labelLarge.tabular(), color = colors.onSurface)
        }
    }
}

/**
 * "Mark as known" after the steps; when the incident shows it could be
 * attacking the whole network, [confirm] asks first.
 */
@Composable
private fun Trust(
    host: NetworkHost,
    confirm: Boolean,
    onTrust: (NetworkHost, Boolean) -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        if (host.trusted) {
            Text(
                stringResource(R.string.event_trusted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onTrust(host, false) }) { Text(stringResource(R.string.event_untrust)) }
        } else {
            Text(
                stringResource(R.string.event_trust_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { if (confirm) confirming = true else onTrust(host, true) },
                modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
            ) { Text(stringResource(R.string.event_trust)) }
        }
    }
    if (confirming) {
        ConfirmDialog(
            R.string.alert_trust_confirm_title,
            R.string.alert_trust_confirm_body,
            R.string.alert_trust_confirm,
            onConfirm = {
                confirming = false
                onTrust(host, true)
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The explanation, the specifics in plain words, and the incident's events oldest first on a small timeline. */
@Composable
private fun WhatHappened(
    incident: Incident,
    gateways: Set<String>,
    clock: DateTimeFormatter,
) {
    var all by rememberSaveable { mutableStateOf(false) }
    val events = incident.events.asReversed()
    val shown = if (all) events else events.take(SHOWN_EVENTS)
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    InsetSection(title = stringResource(R.string.alert_what_happened)) {
        Column(
            Modifier.padding(Dimens.spaceL).animateContentSize(spec),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            Text(stringResource(eventText(incident.lead).explanation), style = MaterialTheme.typography.bodyLarge)
            facts(incident.events, gateways).forEach { Text(factText(it), style = MaterialTheme.typography.bodyLarge) }
            Column {
                shown.forEachIndexed { index, event ->
                    EventLine(event, clock, first = index == 0, last = index == shown.lastIndex)
                }
            }
            if (shown.size < events.size) {
                TextButton(
                    onClick = { all = true },
                ) { Text(pluralStringResource(R.plurals.alert_show_each_time, events.size, events.size)) }
            }
        }
    }
}

@Composable
private fun factText(fact: Fact): String =
    when (fact) {
        is Fact.Decoys -> stringResource(R.string.alert_fact_decoys, listed(fact.services))
        is Fact.Openings -> stringResource(R.string.alert_fact_openings, listed(fact.services))
        Fact.ClaimedRouter -> stringResource(R.string.alert_fact_router)
    }

/** String resources joined as the phone's language lists things: "remote login, file sharing and web pages". */
@Composable
private fun listed(strings: List<Int>): String = ListFormatter.getInstance().format(strings.map { stringResource(it) })

/** One event on the incident's small timeline: a dot on a thin line, the time, what it did and how much. */
@Composable
private fun EventLine(
    event: Event,
    clock: DateTimeFormatter,
    first: Boolean,
    last: Boolean,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    val dot = LocalSeverityColors.current.of(event.severity).color
    val label =
        listOfNotNull(
            stringResource(eventText(event).action),
            event.service?.let { stringResource(serviceName(it)) },
            amount(event)?.let { pluralStringResource(it.words, it.count, it.count) },
        ).joinToString(SEPARATOR)
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = EVENT_DOT.toPx() / 2
                val lineX = if (layoutDirection == LayoutDirection.Ltr) x else size.width - x
                val middle = size.height / 2
                drawLine(
                    line,
                    Offset(lineX, if (first) middle else 0f),
                    Offset(lineX, if (last) middle else size.height),
                    Dimens.hairline.toPx(),
                )
                drawCircle(dot, x, Offset(lineX, middle))
            }.padding(vertical = Dimens.spaceXs),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(EVENT_DOT))
        Text(
            clock.format(event.occurredAt),
            style = MaterialTheme.typography.labelMedium.tabular(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Which device: the source as DeKoi knows it, opening Device detail; DeKoi itself for its own status. */
@Composable
private fun Source(
    incident: Incident,
    host: NetworkHost?,
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val lead = incident.lead
    when {
        host != null -> HostCard(host, data, now, onNavigate)
        lead.kind in DEKOI_STATUS_KINDS -> data.devices.firstOrNull { it.id == lead.deviceId }?.let { DekoiCard(it, onNavigate) }
        else -> UnlistedCard(lead)
    }
}

@Composable
private fun HostCard(
    host: NetworkHost,
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val resources = LocalResources.current
    val network = data.networks.firstOrNull { it.deviceId == host.deviceId && it.contains(host.ip) }
    val home =
        network != null && data.devices
            .firstOrNull { it.id == host.deviceId }
            ?.localIp
            ?.let(network::contains) == true
    val status =
        if (Duration.between(host.lastSeenAt, now) < HOST_ONLINE) {
            stringResource(R.string.alert_device_online)
        } else {
            stringResource(R.string.host_last_seen_ago, relativeTime(host.lastSeenAt, now))
        }
    InsetSection(title = stringResource(R.string.alert_device)) {
        InsetRow(
            hostDisplayName(host, resources::getString),
            subtitle = stringResource(looksLike(host.kind)),
            leading = { KindTile(host.kind) },
            onClick = { onNavigate(Route.HostDetail(host.deviceId, host.mac)) },
        )
        KeyValueRow(stringResource(R.string.event_detail_vendor), host.vendor ?: stringResource(R.string.network_unknown_maker))
        host.model?.let { KeyValueRow(stringResource(R.string.host_model), it) }
        KeyValueRow(stringResource(R.string.host_address), host.ip)
        network?.let {
            KeyValueRow(
                stringResource(R.string.alert_device_network),
                stringResource(if (home) R.string.networks_home else R.string.networks_other),
            )
        }
        KeyValueRow(stringResource(R.string.alert_device_status), status)
    }
}

@Composable
private fun DekoiCard(
    device: Device,
    onNavigate: (Route) -> Unit,
) {
    InsetSection(title = stringResource(R.string.alert_device)) {
        InsetRow(
            device.name,
            subtitle = stringResource(R.string.alert_device_dekoi),
            leading = { BrandTile() },
            onClick = { onNavigate(Route.Dekoi(device.id)) },
        )
    }
}

@Composable
private fun UnlistedCard(event: Event) {
    InsetSection(title = stringResource(R.string.alert_device)) {
        InsetRow(
            stringResource(R.string.kind_unknown),
            subtitle = stringResource(R.string.alert_device_unlisted),
            leading = { KindTile(HostKind.UNKNOWN) },
        )
        event.srcIp?.let { KeyValueRow(stringResource(R.string.host_address), it) }
    }
}

/** The distinct values of one field across the incident's events, at most [MAX_VALUES], or null when none has it. */
private fun <T : Any> Incident.values(of: (Event) -> T?): String? =
    events
        .mapNotNull(of)
        .distinct()
        .take(MAX_VALUES)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

/** Collapsed by default: addresses and ports are for whoever wants them. Values from the device are plain text. */
@Composable
private fun TechnicalDetails(
    incident: Incident,
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
        if (incident.start == incident.end) {
            KeyValueRow(stringResource(R.string.event_detail_time), absoluteTime(incident.end))
        } else {
            KeyValueRow(stringResource(R.string.event_detail_started), absoluteTime(incident.start))
            KeyValueRow(stringResource(R.string.event_detail_ended), absoluteTime(incident.end))
        }
        val rows =
            listOf(
                R.string.event_detail_source to incident.values { it.srcIp },
                R.string.event_detail_mac to incident.values { it.srcMac },
                R.string.event_detail_port to incident.values { it.dstPort },
                R.string.event_detail_username to incident.values { it.detail.username },
                R.string.event_detail_path to incident.values { it.detail.path },
                R.string.event_detail_client to incident.values { it.detail.clientVersion ?: it.detail.userAgent },
                R.string.event_detail_command to incident.values { it.detail.command },
                R.string.event_detail_signature to incident.values { it.detail.signature },
                R.string.event_detail_category to incident.values { it.detail.category },
                R.string.event_detail_claimed_ip to incident.values { it.detail.claimedIp },
                R.string.event_detail_previous_mac to incident.values { it.detail.previousMac },
                R.string.event_detail_server to incident.values { it.detail.serverIp },
                R.string.event_detail_protocol to incident.values { it.detail.protocol?.uppercase() },
                R.string.event_detail_targets to incident.values { it.detail.targets },
                R.string.event_detail_ports to
                    incident.events
                        .flatMap { it.detail.ports }
                        .distinct()
                        .sorted()
                        .take(MAX_PORTS)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(", "),
            )
        host?.vendor?.let { KeyValueRow(stringResource(R.string.event_detail_vendor), it) }
        rows.forEach { (label, value) -> value?.let { CopyableRow(stringResource(label), it) } }
    }
}
