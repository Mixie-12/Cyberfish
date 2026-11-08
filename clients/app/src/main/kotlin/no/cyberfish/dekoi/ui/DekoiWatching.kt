package no.cyberfish.dekoi.ui

import android.text.format.DateFormat
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.Feature
import no.cyberfish.dekoi.FeatureState
import no.cyberfish.dekoi.Freshness
import no.cyberfish.dekoi.Monitor
import no.cyberfish.dekoi.MonitorKind
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.checkState
import no.cyberfish.dekoi.checksOn
import no.cyberfish.dekoi.freshness
import no.cyberfish.dekoi.lastFound
import no.cyberfish.dekoi.softwareUpdatedAt
import no.cyberfish.dekoi.ui.components.CheckStatus
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.Section
import no.cyberfish.dekoi.ui.components.TechnicalDetailsRow
import no.cyberfish.dekoi.ui.components.drawHairline
import no.cyberfish.dekoi.ui.features.FeatureDrawing
import no.cyberfish.dekoi.ui.features.loopMs
import no.cyberfish.dekoi.watchList
import no.cyberfish.dekoi.wifiGuardState
import no.cyberfish.dekoi.wifiWatchText
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.sin

private const val SEPARATOR = " · "

/** Day and short month in the phone's order, such as "3 Nov". */
private const val DAY_MONTH = "dMMM"

private const val SWEEP_ALPHA = 0.14f
private const val RESTING = 0.45f

/** A check in "What DeKoi watches": the drawing that shows it at work, what it does, its state and its numbers. */
private class WatchItem(
    val drawing: Feature?,
    val title: String,
    val state: FeatureState,
    val detail: String? = null,
)

/** The row a light is passing over, and how far across it the light is, from 0 to 1. */
internal data class Light(
    val row: Int,
    val progress: Float,
)

/**
 * Where the light is [time] ms into its round. It visits each row for its
 * dwell in ms, one after another; a row with no dwell is passed by. Null
 * when no row has one.
 */
internal fun lightAt(
    dwells: List<Int>,
    time: Float,
): Light? {
    val round = dwells.sum()
    if (round <= 0) return null
    var left = time.coerceIn(0f, round.toFloat()) % round
    dwells.forEachIndexed { row, dwell ->
        if (left < dwell) return Light(row, left / dwell)
        left -= dwell
    }
    return null
}

/** The drawing that shows a monitor at work; null for a check this app does not know. */
private fun drawing(kind: MonitorKind): Feature? =
    when (kind) {
        MonitorKind.DECOYS -> Feature.DECOY
        MonitorKind.PORT_SCAN -> Feature.SCANS
        MonitorKind.ARP_WATCH -> Feature.ARP_GUARD
        MonitorKind.DHCP_WATCH, MonitorKind.ROUTER_WATCH -> Feature.ROUTER_GUARD
        MonitorKind.NAME_POISONING -> Feature.NAME_TRAP
        MonitorKind.IDS -> Feature.IDS
        MonitorKind.INVENTORY -> Feature.NETWORK_MAP
        MonitorKind.OTHER -> null
    }

/** What each monitor guards against, in plain words; numbers when the DeKoi reports them. */
@Composable
private fun watchTitle(
    monitor: Monitor,
    device: Device,
    working: Boolean,
): String {
    val number = NumberFormat.getIntegerInstance()
    val decoys = device.services.size
    return when (monitor.kind) {
        MonitorKind.DECOYS -> {
            if (working && decoys > 0) {
                pluralStringResource(R.plurals.dekoi_watch_decoys, decoys, decoys)
            } else {
                stringResource(R.string.dekoi_decoys)
            }
        }

        MonitorKind.PORT_SCAN -> {
            stringResource(R.string.dekoi_watch_port_scan)
        }

        MonitorKind.ARP_WATCH -> {
            stringResource(R.string.dekoi_watch_arp)
        }

        MonitorKind.DHCP_WATCH -> {
            stringResource(R.string.dekoi_watch_dhcp)
        }

        MonitorKind.ROUTER_WATCH -> {
            stringResource(R.string.dekoi_watch_router)
        }

        MonitorKind.NAME_POISONING -> {
            stringResource(R.string.dekoi_watch_name_poisoning)
        }

        MonitorKind.IDS -> {
            monitor.detail.rules?.let { pluralStringResource(R.plurals.dekoi_watch_ids, it, number.format(it)) }
                ?: stringResource(R.string.dekoi_watch_ids_plain)
        }

        MonitorKind.INVENTORY -> {
            monitor.detail.hosts?.let { pluralStringResource(R.plurals.dekoi_watch_inventory, it, it) }
                ?: stringResource(R.string.dekoi_watch_inventory_plain)
        }

        MonitorKind.OTHER -> {
            stringResource(R.string.dekoi_watch_other)
        }
    }
}

/** The monitors the DeKoi reports, then Wi-Fi Guard, which reports on its own (ADR 0019). */
@Composable
private fun watchItems(
    device: Device,
    events: List<Event>,
    now: Instant,
): List<WatchItem> {
    val monitors =
        device.watchList.map { monitor ->
            val state = device.checkState(monitor, now)
            WatchItem(drawing(monitor.kind), watchTitle(monitor, device, state.isOn), state, idsDetail(monitor, device, events, now, state))
        }
    val wifi = device.wifiGuardState(now)
    val wifiDetail =
        when (wifi) {
            FeatureState.ON -> device.wifiName
            FeatureState.LEARNING, FeatureState.OFF, FeatureState.NEEDS_WIFI_NAME -> stringResource(wifiWatchText(device, now))
            else -> null
        }
    return monitors + WatchItem(Feature.WIFI_GUARD, stringResource(R.string.dekoi_watch_wifi), wifi, wifiDetail)
}

/** When the attack patterns were published and when they last found something, while the check is on. */
@Composable
private fun idsDetail(
    monitor: Monitor,
    device: Device,
    events: List<Event>,
    now: Instant,
    state: FeatureState,
): String? {
    if (monitor.kind != MonitorKind.IDS || !state.isOn) return null
    return listOfNotNull(
        monitor.detail.rulesetDate?.let { stringResource(R.string.updated_ago, dayAndMonth(it)) },
        lastFound(Feature.IDS, device.id, events)?.let { stringResource(R.string.features_last_found, relativeTime(it.occurredAt, now)) },
    ).joinToString(SEPARATOR).ifEmpty { null }
}

/**
 * "What DeKoi watches", live: when the DeKoi last reported and how many
 * checks are on, one row per check with its drawing, state and numbers,
 * then the software, then, collapsed, the names the DeKoi reports them by.
 * While [playing], a light passes over each check that is on, one after
 * another, and its drawing plays: one loop on screen, none without
 * animations. States hold only while the DeKoi is online; while it is not,
 * every row says so and the footer says why. Older software reports less:
 * the rows show what it does report, and the footer says more follows. No
 * row is made up.
 */
@Composable
fun Watching(
    number: Int,
    device: Device,
    events: List<Event>,
    status: DekoiStatus,
    now: Instant,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = watchItems(device, events, now)
    val footer =
        when {
            status == DekoiStatus.OFFLINE -> stringResource(R.string.dekoi_watching_offline)
            device.monitors.isEmpty() -> stringResource(R.string.dekoi_watching_older)
            else -> null
        }
    val dwells = items.map { item -> item.drawing?.takeIf { item.state.isOn }?.let(::loopMs) ?: 0 }
    val time = lightTime(dwells.sum(), playing)
    val lit by remember(dwells, time) { derivedStateOf { time?.let { lightAt(dwells, it.value)?.row } } }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    Section(number, stringResource(R.string.dekoi_watching), modifier) {
        ReportLine(device, status, now)
        InsetSection(Modifier.animateContentSize(spec), footer = footer) {
            items.forEachIndexed { row, item -> WatchRow(item, time?.takeIf { row == lit }?.let { frameOf(row, dwells, it) }) }
            device.agentVersion?.let { SoftwareRow(it, device.softwareUpdatedAt, now) }
            if (device.monitors.isNotEmpty() || device.images.isNotEmpty()) TechnicalRows(device)
        }
    }
}

/** How far the light is across [row] at [time], read while drawing; null once it has moved on. */
private fun frameOf(
    row: Int,
    dwells: List<Int>,
    time: State<Float>,
): () -> Float? = { lightAt(dwells, time.value)?.takeIf { it.row == row }?.progress }

/** Milliseconds into the light's round of [round] ms, while it plays; null when nothing moves. */
@Composable
private fun lightTime(
    round: Int,
    playing: Boolean,
): State<Float>? {
    if (!playing || round <= 0 || LocalReduceMotion.current) return null
    return rememberInfiniteTransition(label = "watch")
        .animateFloat(0f, round.toFloat(), infiniteRepeatable(tween(round, easing = LinearEasing)), label = "watch")
}

/** When the DeKoi last reported, green while that is recent, and how many of the checks it reports are on, as on Home. */
@Composable
private fun ReportLine(
    device: Device,
    status: DekoiStatus,
    now: Instant,
) {
    val on = device.checksOn
    val total = device.monitors.size
    val live = status == DekoiStatus.ONLINE && device.freshness(now) == Freshness.LIVE
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        StatusDot(if (live) LocalSeverityColors.current.safe.color else LocalSeverityColors.current.offline)
        Text(checkedIn(device, now), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (status == DekoiStatus.ONLINE && total > 0) {
            Text(
                if (on == total) {
                    pluralStringResource(R.plurals.home_checks_all, total, total)
                } else {
                    pluralStringResource(R.plurals.home_checks_some, total, on, total)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One check: its drawing, dimmed while it is not on, what it does and its
 * state. While the light is on it, [frame] gives how far across it is,
 * read while drawing: a soft band of blue crosses the row and the drawing
 * plays.
 */
@Composable
private fun WatchRow(
    item: WatchItem,
    frame: (() -> Float?)?,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.rowWithTile)
            .drawBehind {
                drawHairline(hairline, bottom = false)
                frame?.invoke()?.let { drawSweep(blue, it) }
            }.padding(vertical = Dimens.spaceM)
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Box(Modifier.size(Dimens.tile).alpha(if (item.state.isOn) 1f else RESTING), contentAlignment = Alignment.Center) {
            if (item.drawing == null) {
                Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FeatureDrawing(item.drawing, frame ?: { null }, Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge)
            CheckStatus(item.state, item.detail)
        }
    }
}

/** A band of light that crosses the row from left to right as [progress] runs to 1, fading in and out. */
private fun DrawScope.drawSweep(
    color: Color,
    progress: Float,
) {
    val band = size.width / 3
    val x = -band + (size.width + band) * progress
    val light = color.copy(alpha = SWEEP_ALPHA * sin(PI * progress).toFloat())
    drawRect(
        Brush.horizontalGradient(listOf(Color.Transparent, light, Color.Transparent), startX = x, endX = x + band),
        Offset(x, 0f),
        Size(band, size.height),
    )
}

@Composable
fun dayAndMonth(date: LocalDate): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, DAY_MONTH), locale).format(date)
}

/** The software version and when it last changed; it updates by itself, so there is nothing to do here. */
@Composable
private fun SoftwareRow(
    version: String,
    updatedAt: Instant?,
    now: Instant,
) {
    InsetRow(
        stringResource(R.string.dekoi_software, version),
        subtitle =
            updatedAt?.let { stringResource(R.string.updated_ago, relativeTime(it, now)) } ?: stringResource(R.string.dekoi_software_auto),
        leading = {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/** Collapsed by default: the monitors' and images' own names and values, as the DeKoi reports them. */
@Composable
private fun TechnicalRows(device: Device) {
    var open by rememberSaveable { mutableStateOf(false) }
    TechnicalDetailsRow(open) { open = it }
    if (!open) return
    device.monitors.forEach { KeyValueRow(it.kindName, technicalValue(it)) }
    device.images.forEach { image ->
        KeyValueRow(image.name, listOfNotNull(image.version, image.updatedAtText).joinToString(SEPARATOR))
    }
}

/** The monitor's state and detail under the names the DeKoi reports them by. */
private fun technicalValue(monitor: Monitor): String =
    listOfNotNull(
        monitor.stateName,
        monitor.detail.rules?.let { "rules $it" },
        monitor.detail.rulesetDateText?.let { "ruleset_date $it" },
        monitor.detail.hosts?.let { "hosts $it" },
    ).joinToString(SEPARATOR)
