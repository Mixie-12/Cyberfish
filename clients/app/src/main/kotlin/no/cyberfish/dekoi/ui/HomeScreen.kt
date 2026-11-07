package no.cyberfish.dekoi.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.dekoiToShow
import no.cyberfish.dekoi.eventsPerDay
import no.cyberfish.dekoi.highestSeverity
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.isIntrusion
import no.cyberfish.dekoi.isNew
import no.cyberfish.dekoi.needsAttention
import no.cyberfish.dekoi.protection
import no.cyberfish.dekoi.protectionsOn
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.ui.components.Bar
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.RollingNumber
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.StackedBarChart
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.StatTile
import no.cyberfish.dekoi.ui.components.StatusRing
import no.cyberfish.dekoi.ui.components.kindIcon
import no.cyberfish.dekoi.ui.components.severityGlyph
import no.cyberfish.dekoi.watchList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

private const val LATEST_EVENTS = 5
private const val ATTENTION_ITEMS = 3
private const val CHART_DAYS = 7
private const val CHANGE_MS = 300
private const val GLOW_ALPHA = 0.22f
private const val GLOW_REACH = 0.8f
private const val HORIZON_LINES = 6
private const val HORIZON_FADE = 0.8f
private const val SEPARATOR = " · "
private val TILE_SPARK = 24.dp
private val SMALL_ICON = 16.dp
private val LINE_ICON = 18.dp
private val ACTION_ICON = 20.dp
private val SKELETON_LINE = 28.dp
private val SKELETON_BODY = 44.dp
private val SKELETON_TILE = 120.dp
private val SKELETON_CHART = 200.dp
private val HORIZON_HEIGHT = 96.dp

@Composable
fun HomeScreen(
    data: OwnerData,
    now: Instant,
    refreshing: Boolean,
    listState: LazyListState,
    onRefresh: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val state = protection(data.devices, data.events, data.trustedMacs, now)
    val highest = highestSeverity(recentIntrusions(data.events, data.trustedMacs, now))
    val dekoi = dekoiToShow(data.devices, now)
    val still = LocalReduceMotion.current
    // The ring draws in the first time Home shows it, not each time the owner comes back to it.
    var ringDrawn by rememberSaveable { mutableStateOf(false) }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        Screen(stringResource(R.string.app_name), listState = listState, subtitle = { StaleLabel(data.updatedAt, now) }) {
            data.problem?.let { problem ->
                item(key = "problem") { ErrorBanner(stringResource(problem), onRefresh, Modifier.itemMotion(this, still)) }
            }
            if (!data.loaded) {
                item(key = "loading") { HomeSkeleton(Modifier.itemMotion(this, still)) }
                return@Screen
            }
            var section = 0
            val status = ++section
            item(key = "hero") {
                Section(status, stringResource(R.string.home_status), Modifier.itemMotion(this, still)) {
                    StatusHero(state, highest, drawIn = !ringDrawn)
                }
                LaunchedEffect(Unit) { ringDrawn = true }
            }
            if (dekoi == null) {
                item(key = "add") { NoDekoi({ onNavigate(Route.AddDekoi) }, Modifier.itemMotion(this, still)) }
                return@Screen
            }
            val attention = needsAttention(data.events, data.trustedMacs, now, ATTENTION_ITEMS)
            if (attention.isNotEmpty()) {
                val number = ++section
                item(key = "attention") { NeedsAttention(number, attention, data, now, onNavigate, Modifier.itemMotion(this, still)) }
            }
            val tiles = ++section
            item(key = "tiles") { Tiles(tiles, data, dekoi, now, onNavigate, Modifier.itemMotion(this, still)) }
            val chart = ++section
            item(key = "chart") { AlertChart(chart, data.events, now, onNavigate, Modifier.itemMotion(this, still)) }
            val latest = ++section
            item(key = "latest") { Latest(latest, data, now, onNavigate, Modifier.itemMotion(this, still)) }
        }
    }
}

/** The ring around a shield with a soft glow of the status colour behind it, and the status in words. */
@Composable
private fun StatusHero(
    state: Protection,
    highest: Severity?,
    drawIn: Boolean,
) {
    val color = protectionColor(state, highest)
    val still = LocalReduceMotion.current
    val change: FiniteAnimationSpec<Float> = if (still) snap() else tween(CHANGE_MS)
    val glow by animateColorAsState(color.copy(alpha = GLOW_ALPHA), if (still) snap() else tween(CHANGE_MS), label = "glow")
    Column(
        Modifier.fillMaxWidth().padding(vertical = Dimens.spaceL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.section),
    ) {
        StatusRing(
            color,
            Modifier.drawBehind {
                val reach = size.minDimension * GLOW_REACH
                drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), center, reach), reach)
            },
            drawIn = drawIn,
        )
        Crossfade(
            state,
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            animationSpec = change,
            label = "status",
        ) { shown ->
            val (title, body) = statusText(shown)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** No DeKoi yet: the way to add one, above the brand's horizon. */
@Composable
private fun NoDekoi(
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXl)) {
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_add_dekoi)) }
        Horizon()
    }
}

/**
 * Stand-in for the brand's horizon until the shared one lands (ADR 0020):
 * a lit line, and faint lines below it that spread out like a dark sea.
 */
@Composable
private fun Horizon(modifier: Modifier = Modifier) {
    val glow = MaterialTheme.colorScheme.primary
    val sea = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.fillMaxWidth().height(HORIZON_HEIGHT)) {
        val stroke = Dimens.hairline.toPx()
        drawLine(
            Brush.horizontalGradient(listOf(Color.Transparent, glow, Color.Transparent)),
            Offset(0f, stroke),
            Offset(size.width, stroke),
            stroke * 2,
        )
        for (line in 1..HORIZON_LINES) {
            val depth = line.toFloat() / HORIZON_LINES
            val y = size.height * depth * depth
            drawLine(sea.copy(alpha = 1 - depth * HORIZON_FADE), Offset(0f, y), Offset(size.width, y), stroke)
        }
    }
}

private fun statusText(state: Protection): Pair<Int, Int> =
    when (state) {
        Protection.PROTECTED -> R.string.status_protected_title to R.string.status_protected_body
        Protection.ATTENTION -> R.string.status_attention_title to R.string.status_attention_body
        Protection.OFFLINE -> R.string.status_offline_title to R.string.status_offline_body
        Protection.SETTING_UP -> R.string.status_setting_up_title to R.string.status_setting_up_body
        Protection.NO_DEVICE -> R.string.status_no_device_title to R.string.status_no_device_body
    }

/** At most three, one per source; each opens the alert and its steps. */
@Composable
private fun NeedsAttention(
    number: Int,
    events: List<Event>,
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val resize = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    Section(number, stringResource(R.string.home_needs_attention), modifier.animateContentSize(resize)) {
        events.forEach { event ->
            AttentionCard(event, data.hostFor(event.srcMac), now) { onNavigate(Route.AlertDetail(event.id)) }
        }
    }
}

/** A quiet card with a hairline edge, the one kind of box on Home: each is something to act on. */
@Composable
private fun AttentionCard(
    event: Event,
    host: NetworkHost?,
    now: Instant,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val occurrence =
        listOfNotNull(
            pluralStringResource(R.plurals.alert_times, event.count, event.count).takeIf { event.count > 1 },
            relativeTime(event.occurredAt, now),
        ).joinToString(SEPARATOR)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(Dimens.spaceL),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            SeverityChip(event.severity)
            Text(
                occurrence,
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Text(eventTitle(event), style = MaterialTheme.typography.titleMedium)
        SourceLine(host)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(
                stringResource(R.string.home_see_what_to_do),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(ACTION_ICON),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** Which device it came from: its kind's symbol and its name, as plain text. */
@Composable
private fun SourceLine(host: NetworkHost?) {
    val resources = LocalResources.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Icon(
            painterResource(kindIcon(host?.kind ?: HostKind.UNKNOWN)),
            contentDescription = null,
            modifier = Modifier.size(LINE_ICON),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            host?.let { hostDisplayName(it, resources::getString) } ?: stringResource(R.string.kind_unknown),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Tiles(
    number: Int,
    data: OwnerData,
    dekoi: Device,
    now: Instant,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val perDay = eventsPerDay(data.events, now.atZone(zone).toLocalDate(), zone, CHART_DAYS).map { it.size }
    val newHosts = data.hosts.count { it.isNew(now) }
    val status = dekoi.status(now)
    Section(number, stringResource(R.string.home_overview), modifier) {
        TileGrid(
            listOf(
                { tile ->
                    StatTile(
                        stringResource(R.string.stat_devices_seen),
                        onClick = { onNavigate(Route.Network) },
                        modifier = tile,
                        detail = pluralStringResource(R.plurals.stat_new_this_week, newHosts, newHosts),
                    ) { RollingNumber(data.hosts.size) }
                },
                { tile ->
                    StatTile(
                        stringResource(R.string.stat_alerts),
                        onClick = { onNavigate(Route.Alerts) },
                        modifier = tile,
                        chart = { Sparkline(perDay, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(TILE_SPARK)) },
                    ) { RollingNumber(perDay.sum()) }
                },
                { tile ->
                    StatTile(
                        stringResource(R.string.home_your_dekoi),
                        onClick = { onNavigate(Route.Dekoi(dekoi.id)) },
                        modifier = tile,
                        detail = checkedIn(dekoi, now),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                            StatusDot(statusColor(status))
                            Text(stringResource(statusWord(status)), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                },
                { tile -> ProtectionsTile(dekoi, now, { onNavigate(Route.Dekoi(dekoi.id)) }, tile) },
            ),
        )
    }
}

/**
 * "All 8 protections on", or as much as the DeKoi reports, marked like the
 * worst of them; opens Your DeKoi. Older software reports only its decoys.
 */
@Composable
private fun ProtectionsTile(
    dekoi: Device,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = dekoi.status(now)
    val total = dekoi.monitors.size
    val on = dekoi.protectionsOn
    val decoys = dekoi.services.size
    val summary =
        when {
            status == DekoiStatus.SETTING_UP -> stringResource(R.string.device_setting_up)
            status == DekoiStatus.OFFLINE -> stringResource(R.string.home_protections_paused)
            total == 0 -> pluralStringResource(R.plurals.dekoi_watch_decoys, decoys, decoys)
            on == total -> pluralStringResource(R.plurals.home_protections_all, total, total)
            else -> pluralStringResource(R.plurals.home_protections_some, total, on, total)
        }
    val (glyph, tint) = monitorMark(dekoi.watchList.maxOfOrNull { it.state }.takeIf { status == DekoiStatus.ONLINE })
    StatTile(stringResource(R.string.home_protections), onClick, modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(ACTION_ICON), tint = tint)
            Text(summary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Alerts per day for a week, stacked by severity; a bar opens that day's alerts. */
@Composable
private fun AlertChart(
    number: Int,
    events: List<Event>,
    now: Instant,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val dates = (CHART_DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
    val perDay = eventsPerDay(events, today, zone, CHART_DAYS)
    val colors = LocalSeverityColors.current
    val locale = LocalLocale.current.platformLocale
    val dayName = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val bars =
        dates.zip(perDay) { day, dayEvents ->
            val counts = Severity.entries.map { severity -> dayEvents.count { it.severity == severity } }
            val parts =
                listOf(pluralStringResource(R.plurals.home_alerts_count, dayEvents.size, dayEvents.size)) +
                    Severity.entries.zip(counts).filter { it.second > 0 }.map { (severity, count) ->
                        stringResource(R.string.home_chart_severity, stringResource(severityLabel(severity)), count)
                    }
            Bar(
                values = counts,
                label = day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                description = stringResource(R.string.home_chart_bar, dayName.format(day), parts.joinToString(", ")),
            )
        }
    Section(number, stringResource(R.string.last_seven_days), modifier) {
        StackedBarChart(
            bars,
            Severity.entries.map { colors.of(it).color },
            onBarClick = { index -> onNavigate(Route.FilteredAlerts(day = dates[index].toString())) },
        )
        SeverityLegend()
        Text(
            stringResource(R.string.home_chart_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Colour, glyph and word for each severity, so the bars never rely on colour alone. */
@Composable
private fun SeverityLegend() {
    val colors = LocalSeverityColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Severity.entries.forEach { severity ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                Icon(
                    painterResource(severityGlyph(severity)),
                    contentDescription = null,
                    modifier = Modifier.size(SMALL_ICON),
                    tint = colors.of(severity).color,
                )
                Text(
                    stringResource(severityLabel(severity)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Latest(
    number: Int,
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest = data.events.filter { it.isIntrusion }.take(LATEST_EVENTS)
    Section(number, stringResource(R.string.home_latest_activity), modifier) {
        InsetSection {
            if (latest.isEmpty()) {
                InsetRow(
                    stringResource(R.string.home_no_activity),
                    leading = {
                        Icon(
                            painterResource(R.drawable.ic_check_circle),
                            contentDescription = null,
                            tint = LocalSeverityColors.current.safe.color,
                        )
                    },
                )
                return@InsetSection
            }
            latest.forEach { event -> AlertRow(event, data.hostFor(event.srcMac), now) { onNavigate(Route.AlertDetail(event.id)) } }
            InsetRow(
                stringResource(R.string.home_see_all_alerts),
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = { onNavigate(Route.Alerts) },
            )
        }
    }
}

/** Shaped like the hero, the tiles and the chart, so nothing jumps when the data arrives. */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    val tile: @Composable (Modifier) -> Unit = { Skeleton(it.height(SKELETON_TILE), MaterialTheme.shapes.large) }
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        Skeleton(Modifier.size(Dimens.ring), CircleShape)
        Skeleton(Modifier.fillMaxWidth(0.6f).height(SKELETON_LINE))
        Skeleton(Modifier.fillMaxWidth(0.85f).height(SKELETON_BODY))
        TileGrid(List(4) { tile }, Modifier.padding(top = Dimens.spaceS))
        Skeleton(Modifier.fillMaxWidth().height(SKELETON_CHART), MaterialTheme.shapes.large)
    }
}
