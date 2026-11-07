package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.eventsPerDay
import no.cyberfish.dekoi.highestSeverity
import no.cyberfish.dekoi.isIntrusion
import no.cyberfish.dekoi.isOnline
import no.cyberfish.dekoi.protection
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.ui.components.Bar
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.RollingNumber
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.StackedBarChart
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.StatTile
import no.cyberfish.dekoi.ui.components.StatusRing
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

private const val LATEST_EVENTS = 5
private const val CHART_DAYS = 7
private const val ONE_COLUMN_FONT_SCALE = 1.5f
private val NEW_WINDOW = Duration.ofDays(7)

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
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh) {
        Screen(stringResource(R.string.app_name), listState = listState, subtitle = { StaleLabel(data.updatedAt, now) }) {
            data.problem?.let { item { ErrorBanner(stringResource(it), onRefresh) } }
            if (!data.loaded) {
                item { HomeSkeleton() }
                return@Screen
            }
            item { StatusHero(state, highestSeverity(recentIntrusions(data.events, data.trustedMacs, now))) }
            if (state == Protection.NO_DEVICE) {
                item {
                    Button(onClick = { onNavigate(Route.AddDekoi) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_add_dekoi))
                    }
                }
                return@Screen
            }
            item { Tiles(data, now, onNavigate) }
            item { AlertChart(data, now, onNavigate) }
            item { YourDekoi(data.devices, now, onNavigate) }
            item { Latest(data, now, onNavigate) }
        }
    }
}

@Composable
private fun StatusHero(
    state: Protection,
    highest: Severity?,
) {
    val (title, body) =
        when (state) {
            Protection.PROTECTED -> R.string.status_protected_title to R.string.status_protected_body
            Protection.ATTENTION -> R.string.status_attention_title to R.string.status_attention_body
            Protection.OFFLINE -> R.string.status_offline_title to R.string.status_offline_body
            Protection.SETTING_UP -> R.string.status_setting_up_title to R.string.status_setting_up_body
            Protection.NO_DEVICE -> R.string.status_no_device_title to R.string.status_no_device_body
        }
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        StatusRing(protectionColor(state, highest))
        Column(
            Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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

@Composable
private fun Tiles(
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val newThisWeek = data.hosts.count { Duration.between(it.firstSeenAt, now) < NEW_WINDOW }
    val zone = ZoneId.systemDefault()
    val perDay = eventsPerDay(data.events, now.atZone(zone).toLocalDate(), zone, CHART_DAYS).map { it.size }
    val decoys = data.devices.filter { it.isOnline(now) }.sumOf { it.services.size }
    // Home shows tiles only once there is a DeKoi.
    val dekoi = data.devices.first()
    val lastCheckIn = data.devices.mapNotNull { it.lastSeenAt }.maxOrNull()
    val tiles: List<@Composable (Modifier) -> Unit> =
        listOf(
            {
                StatTile(
                    stringResource(R.string.stat_devices_seen),
                    onClick = { onNavigate(Route.Network) },
                    modifier = it,
                    detail = pluralStringResource(R.plurals.stat_new_this_week, newThisWeek, newThisWeek),
                ) { RollingNumber(data.hosts.size) }
            },
            {
                StatTile(
                    stringResource(R.string.stat_alerts),
                    onClick = { onNavigate(Route.Alerts) },
                    modifier = it,
                    chart = { Sparkline(perDay, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().height(24.dp)) },
                ) { RollingNumber(perDay.sum()) }
            },
            {
                StatTile(
                    stringResource(R.string.home_your_dekoi),
                    onClick = { onNavigate(Route.Dekoi(dekoi.id)) },
                    modifier = it,
                    detail =
                        lastCheckIn?.let { seen -> stringResource(R.string.device_checked_in, relativeTime(seen, now)) }
                            ?: stringResource(R.string.device_never_checked_in),
                ) { Text(stringResource(statusWord(dekoi, now)), style = MaterialTheme.typography.headlineSmall) }
            },
            {
                StatTile(
                    stringResource(R.string.stat_decoys),
                    onClick = { onNavigate(Route.Dekoi(dekoi.id)) },
                    modifier = it,
                ) { RollingNumber(decoys) }
            },
        )
    val columns = if (LocalDensity.current.fontScale > ONE_COLUMN_FONT_SCALE) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        tiles.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                row.forEach { tile -> tile(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AlertChart(
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val days = eventsPerDay(data.events, today, zone, CHART_DAYS)
    val colors = LocalSeverityColors.current
    val locale = LocalLocale.current.platformLocale
    val dayName = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    val bars =
        days.mapIndexed { index, events ->
            val day = today.minusDays((CHART_DAYS - 1 - index).toLong())
            Bar(
                values = Severity.entries.map { severity -> events.count { it.severity == severity } },
                label = day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                description =
                    stringResource(
                        R.string.home_chart_bar,
                        dayName.format(day),
                        pluralStringResource(R.plurals.home_alerts_count, events.size, events.size),
                    ),
            )
        }
    InsetSection(title = stringResource(R.string.last_seven_days)) {
        Box(Modifier.padding(Dimens.spaceL)) {
            StackedBarChart(
                bars,
                Severity.entries.map { colors.of(it).color },
                onBarClick = { index ->
                    onNavigate(Route.FilteredAlerts(day = today.minusDays((CHART_DAYS - 1 - index).toLong()).toString()))
                },
            )
        }
    }
}

@Composable
private fun YourDekoi(
    devices: List<Device>,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    InsetSection(title = stringResource(R.string.home_your_dekoi)) {
        devices.forEach { device ->
            InsetRow(
                device.name,
                subtitle = stringResource(statusWord(device, now)),
                leading = { BrandTile() },
                onClick = { onNavigate(Route.Dekoi(device.id)) },
            )
        }
        InsetRow(
            stringResource(R.string.action_add_dekoi),
            titleColor = MaterialTheme.colorScheme.primary,
            leading = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { onNavigate(Route.AddDekoi) },
        )
    }
}

@Composable
private fun Latest(
    data: OwnerData,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val latest = data.events.filter { it.isIntrusion }.take(LATEST_EVENTS)
    InsetSection(title = stringResource(R.string.home_latest_activity)) {
        if (latest.isEmpty()) InsetRow(stringResource(R.string.home_no_activity))
        latest.forEach { event -> AlertRow(event, data.hostFor(event.srcMac), now) { onNavigate(Route.AlertDetail(event.id)) } }
    }
}

@Composable
private fun HomeSkeleton() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        Skeleton(Modifier.size(Dimens.ring), CircleShape)
        Skeleton(Modifier.fillMaxWidth(0.6f).height(28.dp))
        Skeleton(Modifier.fillMaxWidth().height(120.dp), MaterialTheme.shapes.large)
        Skeleton(Modifier.fillMaxWidth().height(160.dp), MaterialTheme.shapes.large)
    }
}
