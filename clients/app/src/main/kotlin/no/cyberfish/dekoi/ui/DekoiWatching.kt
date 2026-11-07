package no.cyberfish.dekoi.ui

import android.text.format.DateFormat
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Monitor
import no.cyberfish.dekoi.MonitorKind
import no.cyberfish.dekoi.MonitorState
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.softwareUpdatedAt
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.Section
import no.cyberfish.dekoi.ui.components.TechnicalDetailsRow
import no.cyberfish.dekoi.watchList
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val SEPARATOR = " · "

/** Day and short month in the phone's order, such as "3 Nov". */
private const val DAY_MONTH = "dMMM"

/**
 * The glyph and colour that mark a monitor's state: a green check when it
 * works, amber when it fails, a calm grey otherwise. Null means paused,
 * because the DeKoi is not online to run it.
 */
@Composable
fun monitorMark(state: MonitorState?): Pair<Int, Color> {
    val colors = LocalSeverityColors.current
    return when (state) {
        MonitorState.OK -> R.drawable.ic_check_circle to colors.safe.color
        MonitorState.FAILED -> R.drawable.ic_warning to colors.warning.color
        MonitorState.STARTING, MonitorState.OFF, MonitorState.UNKNOWN, null -> R.drawable.ic_info to colors.offline
    }
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

@StringRes
private fun stateWord(state: MonitorState): Int =
    when (state) {
        MonitorState.OK -> R.string.dekoi_state_on
        MonitorState.STARTING -> R.string.dekoi_state_starting
        MonitorState.OFF -> R.string.dekoi_state_off
        MonitorState.UNKNOWN -> R.string.dekoi_state_unknown
        MonitorState.FAILED -> R.string.dekoi_state_failed
    }

/**
 * One row per check, then the software, then, collapsed, the names
 * the DeKoi reports them by. States hold only while the DeKoi is online;
 * while it is not, every row is grey and the footer says why. Older
 * software reports less: the rows show what it does report, and the
 * footer says more follows. No row is made up.
 */
@Composable
fun Watching(
    number: Int,
    device: Device,
    status: DekoiStatus,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val live = status == DekoiStatus.ONLINE
    val footer =
        when {
            !live -> stringResource(R.string.dekoi_watching_offline)
            device.monitors.isEmpty() -> stringResource(R.string.dekoi_watching_older)
            else -> null
        }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    Section(number, stringResource(R.string.dekoi_watching), modifier) {
        InsetSection(Modifier.animateContentSize(spec), footer = footer) {
            device.watchList.forEach { WatchRow(it, device, it.state.takeIf { live }) }
            device.agentVersion?.let { SoftwareRow(it, device.softwareUpdatedAt, now) }
            if (device.monitors.isNotEmpty() || device.images.isNotEmpty()) TechnicalRows(device)
        }
    }
}

@Composable
private fun WatchRow(
    monitor: Monitor,
    device: Device,
    state: MonitorState?,
) {
    val (glyph, tint) = monitorMark(state)
    val on = stringResource(R.string.dekoi_state_on)
    InsetRow(
        watchTitle(monitor, device, working = state == MonitorState.OK),
        Modifier.semantics(mergeDescendants = true) { if (state == MonitorState.OK) stateDescription = on },
        subtitle = watchDetail(monitor, state),
        leading = { Icon(painterResource(glyph), contentDescription = null, tint = tint) },
    )
}

/** The line under a row: the state when the check is not on, or when the attack patterns were updated. */
@Composable
private fun watchDetail(
    monitor: Monitor,
    state: MonitorState?,
): String? {
    if (state == null) return null
    if (state != MonitorState.OK) return stringResource(stateWord(state))
    val updated = monitor.detail.rulesetDate?.takeIf { monitor.kind == MonitorKind.IDS } ?: return null
    return stringResource(R.string.updated_ago, dayAndMonth(updated))
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
