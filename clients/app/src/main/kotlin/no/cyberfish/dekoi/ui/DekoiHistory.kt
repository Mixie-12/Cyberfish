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

private const val UPTIME_HOURS = 7 * 24
private const val WEEK_DAYS = 7
private val SWATCH = 10.dp
private val DECOY_SPARK = 32.dp

/** When the DeKoi was online this week, hour by hour, with a summary and a legend. */
@Composable
fun UptimeCard(
    device: Device,
    events: List<Event>,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val hours = hourlyUptime(device, events, now, UPTIME_HOURS)
    val known = hours.count { it != Uptime.UNKNOWN }
    val summary =
        if (known == 0) {
            stringResource(R.string.dekoi_uptime_none)
        } else {
            pluralStringResource(R.plurals.dekoi_uptime_summary, known, hours.count { it == Uptime.ONLINE }, known)
        }
    val colors = LocalSeverityColors.current
    InsetSection(modifier, title = stringResource(R.string.last_seven_days)) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            // TalkBack hears the summary once, from the strip.
            Text(summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics { })
            UptimeStrip(hours, summary, Modifier.padding(top = Dimens.spaceXs))
            Row(Modifier.fillMaxWidth().clearAndSetSemantics { }) {
                AxisLabel(stringResource(R.string.dekoi_uptime_start), Modifier.weight(1f))
                AxisLabel(stringResource(R.string.dekoi_uptime_end))
            }
            FlowRow(
                Modifier.padding(top = Dimens.spaceXs),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL),
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                Swatch(colors.safe.color, stringResource(R.string.device_online))
                Swatch(colors.offline, stringResource(R.string.device_offline))
                Swatch(MaterialTheme.colorScheme.outlineVariant, stringResource(R.string.dekoi_uptime_unknown))
            }
        }
    }
}

@Composable
private fun AxisLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun Swatch(
    color: Color,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Box(Modifier.size(SWATCH).background(color, MaterialTheme.shapes.extraSmall))
        AxisLabel(label)
    }
}

/** One tile per decoy service; a tile opens the alerts about that decoy. */
@Composable
fun Decoys(
    device: Device,
    events: List<Event>,
    now: Instant,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val zone = ZoneId.systemDefault()
    val today = now.atZone(zone).toLocalDate()
    val tiles =
        device.services.map<String, @Composable (Modifier) -> Unit> { service ->
            { tile ->
                DecoyTile(
                    service,
                    touchesPerDay(events, device.id, service, today, zone, WEEK_DAYS),
                    onClick = { onNavigate(Route.FilteredAlerts(service = service)) },
                    modifier = tile,
                )
            }
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        SectionTitle(stringResource(R.string.stat_decoys))
        TileGrid(tiles)
        Text(
            stringResource(R.string.dekoi_decoys_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.spaceL),
        )
    }
}

/**
 * A decoy: its plain name, its own label (two decoys can share a plain
 * name), how often it was touched this week and a line of the days.
 */
@Composable
private fun DecoyTile(
    service: String,
    perDay: List<Int>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val touches = perDay.sum()
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(stringResource(serviceName(service)).replaceFirstChar(Char::titlecase), style = MaterialTheme.typography.titleMedium)
            Text(service, style = MaterialTheme.typography.address, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (touches == 0) {
                    stringResource(R.string.dekoi_not_touched)
                } else {
                    pluralStringResource(R.plurals.dekoi_touched, touches, touches)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Sparkline(
                perDay,
                if (touches == 0) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                Modifier.padding(top = Dimens.spaceS).fillMaxWidth().height(DECOY_SPARK),
            )
        }
    }
}
