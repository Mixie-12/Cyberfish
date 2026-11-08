package no.cyberfish.dekoi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
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
import no.cyberfish.dekoi.checksOn
import no.cyberfish.dekoi.dekoiToShow
import no.cyberfish.dekoi.eventsPerDay
import no.cyberfish.dekoi.highestSeverity
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.isIntrusion
import no.cyberfish.dekoi.isNew
import no.cyberfish.dekoi.isSetUp
import no.cyberfish.dekoi.needsAttention
import no.cyberfish.dekoi.protection
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.ui.components.Bar
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.RollingNumber
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Section
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.StackedBarChart
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.StatTile
import no.cyberfish.dekoi.ui.components.StatusRing
import no.cyberfish.dekoi.ui.components.kindIcon
import no.cyberfish.dekoi.ui.components.severityGlyph
import no.cyberfish.dekoi.ui.motion.AnimatedStatus
import no.cyberfish.dekoi.ui.motion.ExpandingVisibility
import no.cyberfish.dekoi.ui.motion.rememberHaptics
import no.cyberfish.dekoi.ui.motion.sharedSeverity
import no.cyberfish.dekoi.ui.onboarding.Welcome
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
private const val SEPARATOR = " · "
private val TILE_SPARK = 24.dp
private val SMALL_ICON = 16.dp
private val LINE_ICON = 18.dp
private val ACTION_ICON = 20.dp
private val SKELETON_LINE = 28.dp
private val SKELETON_BODY = 44.dp
private val SKELETON_TILE = 120.dp
private val SKELETON_CHART = 200.dp

@Composable
fun HomeScreen(
    data: OwnerData,
    now: Instant,
    refreshing: Boolean,
    listState: LazyListState,
    welcome: Welcome,
    onRefresh: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    val state = protection(data.devices, data.events, data.trustedMacs, now)
    val highest = highestSeverity(recentIntrusions(data.events, data.trustedMacs, now))
    val dekoi = dekoiToShow(data.devices, now)
    // Until a DeKoi watches the network or the owner closes it; then adding one is a row.
    val welcoming = welcome.open && data.devices.none { it.isSetUp }
    val add = { onNavigate(Route.AddDekoi) }
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
            if (welcoming) {
                item(key = "welcome") {
                    WelcomeCard(add, { onNavigate(Route.Onboarding) }, welcome::close, Modifier.itemMotion(this, still))
                }
            } else if (dekoi == null) {
                item(key = "add") { InsetSection(Modifier.itemMotion(this, still)) { AddDekoiRow(R.string.action_add_dekoi, add) } }
            }
            if (dekoi == null) return@Screen
            val attention = needsAttention(data.events, data.trustedMacs, now, ATTENTION_ITEMS)
            if (attention.isNotEmpty()) {
                val number = ++section
                item(key = "attention") { NeedsAttention(number, attention, data, now, onNavigate, Modifier.itemMotion(this, still)) }
            }
            val tiles = ++section
            item(key = "tiles") {
                Tiles(tiles, data, dekoi, now, onNavigate, add.takeUnless { welcoming }, Modifier.itemMotion(this, still))
            }
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
        AnimatedStatus(
            state,
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.TopCenter,
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

/** One step of the welcome: its symbol, what to do and how. */
private class WelcomeStep(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
)

private val welcomeSteps =
    listOf(
        WelcomeStep(R.drawable.ic_sd_card, R.string.home_welcome_card_title, R.string.home_welcome_card_body),
        WelcomeStep(R.drawable.ic_cable, R.string.home_welcome_plug_title, R.string.home_welcome_plug_body),
        WelcomeStep(R.drawable.ic_smartphone, R.string.home_welcome_watch_title, R.string.home_welcome_watch_body),
    )

/**
 * The way in for a new owner, in the HUD frame: three numbered steps to a
 * DeKoi that watches the network, the way to add one, and the tour.
 */
@Composable
private fun WelcomeCard(
    onAdd: () -> Unit,
    onTour: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    HudFrame(modifier.fillMaxWidth(), contentPadding = Dimens.gutter) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.home_welcome_title).headingCase(),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = {
                    haptics.confirm()
                    onClose()
                }) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.home_welcome_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            welcomeSteps.forEachIndexed { index, step -> WelcomeStepRow(index + 1, step) }
            Column {
                PrimaryButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_add_dekoi)) }
                TextButton(onClick = onTour, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_take_tour)) }
            }
        }
    }
}

/** "1", the step's symbol, and what to do with how below it; read as one. */
@Composable
private fun WelcomeStepRow(
    number: Int,
    step: WelcomeStep,
) {
    val blue = MaterialTheme.colorScheme.primary
    Row(
        Modifier.semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Text(number.toString(), style = MaterialTheme.typography.label, color = blue)
        Icon(painterResource(step.icon), contentDescription = null, modifier = Modifier.size(LINE_ICON), tint = blue)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(stringResource(step.title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(step.body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    // The cards there when the section shows are simply there; one that
    // arrives while Home is open opens in (ADR 0032).
    val first = remember { events.mapTo(mutableSetOf()) { it.id } }
    Section(number, stringResource(R.string.home_needs_attention), modifier.animateContentSize(resize)) {
        events.forEach { event ->
            key(event.id) {
                val shown = remember { MutableTransitionState(event.id in first) }.apply { targetState = true }
                val detail = Route.AlertDetail(event.id)
                ExpandingVisibility(shown) { AttentionCard(event, data.hostFor(event.srcMac), now, detail) { onNavigate(detail) } }
            }
        }
    }
}

/**
 * A quiet card with a hairline edge, the one kind of box on Home: each is
 * something to act on. Its severity chip moves into the hero of [detail].
 * Only here: Latest activity may show the same alert, and a screen has one
 * shared element per route.
 */
@Composable
private fun AttentionCard(
    event: Event,
    host: NetworkHost?,
    now: Instant,
    detail: Route,
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
            SeverityChip(event.severity, Modifier.sharedSeverity(detail))
            Text(
                occurrence,
                style = MaterialTheme.typography.labelMedium.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Text(eventTitle(event, host), style = MaterialTheme.typography.titleMedium)
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

/** The four tiles, Your DeKoi among them, and under them [onAddAnother] as a row unless the welcome offers it. */
@Composable
private fun Tiles(
    number: Int,
    data: OwnerData,
    dekoi: Device,
    now: Instant,
    onNavigate: (Route) -> Unit,
    onAddAnother: (() -> Unit)?,
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
                { tile -> ChecksTile(dekoi, now, { onNavigate(Route.Features(dekoi.id)) }, tile) },
            ),
        )
        onAddAnother?.let { InsetSection { AddDekoiRow(R.string.home_add_another, it) } }
    }
}

/**
 * "All 8 checks on", or as much as the DeKoi reports, marked like the
 * worst of them; opens Features. Older software reports only its decoys.
 */
@Composable
private fun ChecksTile(
    dekoi: Device,
    now: Instant,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = dekoi.status(now)
    val total = dekoi.monitors.size
    val on = dekoi.checksOn
    val decoys = dekoi.services.size
    val summary =
        when {
            status == DekoiStatus.SETTING_UP -> stringResource(R.string.device_setting_up)
            status == DekoiStatus.OFFLINE -> stringResource(R.string.home_checks_paused)
            total == 0 -> pluralStringResource(R.plurals.dekoi_watch_decoys, decoys, decoys)
            on == total -> pluralStringResource(R.plurals.home_checks_all, total, total)
            else -> pluralStringResource(R.plurals.home_checks_some, total, on, total)
        }
    val (glyph, tint) = monitorMark(dekoi.watchList.maxOfOrNull { it.state }.takeIf { status == DekoiStatus.ONLINE })
    StatTile(stringResource(R.string.home_checks), onClick, modifier) {
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
