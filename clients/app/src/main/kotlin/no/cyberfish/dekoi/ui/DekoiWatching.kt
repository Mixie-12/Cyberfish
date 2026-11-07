package no.cyberfish.dekoi.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.Monitor
import no.cyberfish.dekoi.MonitorKind
import no.cyberfish.dekoi.MonitorState
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SetupStep
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.cleanName
import no.cyberfish.dekoi.hasDone
import no.cyberfish.dekoi.hourlyUptime
import no.cyberfish.dekoi.nameToSave
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.softwareUpdatedAt
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.touchesPerDay
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.UptimeStrip
import no.cyberfish.dekoi.watchList
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val SEPARATOR = " · "

/**
 * The glyph and tone that mark a monitor's state: green when it works,
 * amber when it fails, grey otherwise. Null means paused, because the
 * DeKoi is not online to run it.
 */
@Composable
fun monitorMark(state: MonitorState?): Pair<Int, Tone> {
    val colors = LocalSeverityColors.current
    return when (state) {
        MonitorState.OK -> R.drawable.ic_check_circle to colors.safe
        MonitorState.FAILED -> R.drawable.ic_warning to colors.warning
        MonitorState.STARTING -> R.drawable.ic_refresh to colors.info
        MonitorState.OFF, MonitorState.UNKNOWN, null -> R.drawable.ic_info to colors.info
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
                stringResource(R.string.stat_decoys)
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
 * One row per protection. States hold only while the DeKoi is online; while
 * it is not, every row is grey and the footer says why.
 */
@Composable
fun Watching(
    device: Device,
    status: DekoiStatus,
    modifier: Modifier = Modifier,
) {
    val live = status == DekoiStatus.ONLINE
    val footer =
        when {
            !live -> stringResource(R.string.dekoi_watching_offline)
            device.monitors.isEmpty() -> stringResource(R.string.dekoi_watching_older)
            else -> null
        }
    InsetSection(modifier, title = stringResource(R.string.dekoi_watching), footer = footer) {
        device.watchList.forEach { monitor ->
            val state = monitor.state.takeIf { live }
            val on = stringResource(R.string.dekoi_state_on)
            InsetRow(
                watchTitle(monitor, device, working = state == MonitorState.OK),
                Modifier.semantics(mergeDescendants = true) { if (state == MonitorState.OK) stateDescription = on },
                subtitle = watchDetail(monitor, state),
                leading = { MarkTile(state) },
            )
        }
    }
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
    return stringResource(R.string.dekoi_watch_ids_updated, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(updated))
}

@Composable
private fun MarkTile(state: MonitorState?) {
    val (glyph, tone) = monitorMark(state)
    Box(Modifier.size(Dimens.tile).background(tone.container, MaterialTheme.shapes.medium), contentAlignment = Alignment.Center) {
        Icon(painterResource(glyph), contentDescription = null, tint = tone.color)
    }
}

/** Collapsed by default: the monitors' and images' own names and values, as the DeKoi reports them. */
@Composable
fun TechnicalDetails(
    device: Device,
    modifier: Modifier = Modifier,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    InsetSection(modifier.animateContentSize(spec)) {
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
        device.monitors.forEach { KeyValueRow(it.kindName, technicalValue(it)) }
        device.images.forEach { image ->
            KeyValueRow(image.name, listOfNotNull(image.version, image.updatedAtText).joinToString(SEPARATOR))
        }
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
