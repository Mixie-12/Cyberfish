package no.cyberfish.dekoi.ui.network

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label
import no.cyberfish.dekoi.ui.tabular
import no.cyberfish.dekoi.ui.tileColumns
import java.time.Instant

/**
 * The Rooms view of the Network tab (docs/design.md, "Network"): a plan of
 * the home with one HUD-framed area per room the owner uses, laid out as
 * [floorPlan] says, then the devices not placed yet with "Place your
 * devices" ([onPlace]). Nothing in it moves. Rooms are owner text and
 * names may come from the devices: plain text only.
 */
@Composable
internal fun RoomsView(
    entries: List<HostEntry>,
    now: Instant,
    onOpen: (HostEntry) -> Unit,
    onPlace: () -> Unit,
) {
    val areas = roomAreas(entries, stringArrayResource(R.array.host_rooms).toList())
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        if (areas.all { it.room == null }) Text(stringResource(R.string.rooms_intro), style = MaterialTheme.typography.bodyLarge)
        floorPlan(areas, tileColumns()).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                row.forEach { area ->
                    val modifier = Modifier.weight(1f).fillMaxHeight()
                    when (val room = area.room) {
                        null -> NotPlaced(area.entries, now, onOpen, onPlace, modifier)
                        else -> Room(room, area.entries, now, onOpen, modifier)
                    }
                }
            }
        }
        Text(
            stringResource(R.string.rooms_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Room(
    name: String,
    entries: List<HostEntry>,
    now: Instant,
    onOpen: (HostEntry) -> Unit,
    modifier: Modifier,
) {
    HudFrame(modifier, contentPadding = Dimens.spaceM) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            AreaHeading(name, entries.size)
            Devices(entries, now, onOpen)
        }
    }
}

/** Outside the plan: a heading over a hairline, the way to place them, and the devices. */
@Composable
private fun NotPlaced(
    entries: List<HostEntry>,
    now: Instant,
    onOpen: (HostEntry) -> Unit,
    onPlace: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.padding(top = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        AreaHeading(stringResource(R.string.rooms_not_placed), entries.size)
        HorizontalDivider()
        PrimaryButton(onClick = onPlace) { Text(stringResource(R.string.place_title)) }
        Devices(entries, now, onOpen)
    }
}

/** The area's name as a label and its device count in blue; TalkBack reads "Living room, 3 devices" as a heading. */
@Composable
private fun AreaHeading(
    name: String,
    count: Int,
) {
    val description = "$name, ${pluralStringResource(R.plurals.network_devices, count, count)}"
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            heading()
            contentDescription = description
        },
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Text(
            name.headingCase(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(count.toString(), style = MaterialTheme.typography.label.tabular(), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Devices(
    entries: List<HostEntry>,
    now: Instant,
    onOpen: (HostEntry) -> Unit,
) {
    FlowRow(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        entries.forEach { entry -> Device(entry, now) { onOpen(entry) } }
    }
}

/** A device as on the map: its tile, then its name under it. TalkBack reads the name, whether it is online and any alert. */
@Composable
private fun Device(
    entry: HostEntry,
    now: Instant,
    onClick: () -> Unit,
) {
    val seen = seenText(entry.online, entry.host.lastSeenAt, now)
    val state = listOfNotNull(seen, entry.attention?.let { stringResource(severityLabel(it)) }).joinToString(", ")
    Column(
        Modifier
            .width(PITCH.dp)
            .clickable(onClick = onClick)
            .semantics { stateDescription = state }
            .padding(vertical = Dimens.spaceXs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        HostTile(entry.host, entry.online, entry.attention)
        Text(
            entry.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
