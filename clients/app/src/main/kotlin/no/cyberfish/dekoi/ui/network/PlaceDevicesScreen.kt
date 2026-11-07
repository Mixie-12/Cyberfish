package no.cyberfish.dekoi.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.kindName
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label
import no.cyberfish.dekoi.ui.motion.AnimatedStatus
import no.cyberfish.dekoi.ui.motion.rememberHaptics
import java.time.Instant

private val SKELETON = 240.dp

// The devices to place, saved as device id and hardware address in turn.
private val queueSaver =
    listSaver<List<Route.HostDetail>, String>(
        save = { queue -> queue.flatMap { listOf(it.deviceId, it.mac) } },
        restore = { saved -> saved.chunked(2) { (deviceId, mac) -> Route.HostDetail(deviceId, mac) } },
    )

/**
 * "Place your devices" (docs/design.md, "Network"): the devices that had
 * no room when the flow opened, one at a time, each with the rooms to pick
 * from or Skip. A pick saves at once through [onNameAndRoom] and leaves the
 * owner's name for the device as it is. A device that leaves the network
 * meanwhile is passed over. Names and rooms are plain text only.
 */
@Composable
fun PlaceDevicesScreen(
    data: OwnerData,
    now: Instant,
    onNameAndRoom: (NetworkHost, String?, String?) -> Unit,
    onBack: () -> Unit,
) {
    val title = stringResource(R.string.place_title)
    if (!data.loaded) {
        Screen(title, onBack = onBack) { item { Skeleton(Modifier.fillMaxWidth().height(SKELETON), MaterialTheme.shapes.large) } }
        return
    }
    val resources = LocalResources.current
    // Only the order matters here, which alerts do not change.
    val entries =
        hostEntries(
            data.hosts,
            emptyMap(),
            now,
            { hostDisplayName(it, resources::getString) },
            { resources.getString(kindName(it)) },
        )
    val byKey = entries.associateBy { it.host.detail }
    val queue = rememberSaveable(saver = queueSaver) { notPlaced(entries).map { it.host.detail } }
    var next by rememberSaveable { mutableIntStateOf(0) }
    var placed by rememberSaveable { mutableIntStateOf(0) }
    val step = (next until queue.size).firstOrNull { queue[it] in byKey }
    val rooms = roomChoices(data.hosts.mapNotNull { it.room }, stringArrayResource(R.array.host_rooms).toList())
    val haptics = rememberHaptics()
    Screen(title, onBack = onBack, subtitle = { step?.let { Progress(it + 1, queue.size) } }) {
        item {
            AnimatedStatus(step) { shown ->
                if (shown == null) {
                    Done(placed, onBack)
                    return@AnimatedStatus
                }
                // The step fading out may name a device that has just left.
                val entry = byKey[queue[shown]] ?: return@AnimatedStatus
                Place(
                    entry,
                    rooms,
                    onPick = { room ->
                        haptics.confirm()
                        onNameAndRoom(entry.host, entry.host.label, room)
                        placed++
                        next = shown + 1
                    },
                    onSkip = { next = shown + 1 },
                )
            }
        }
    }
}

/** "3 of 7", read out as it changes. */
@Composable
private fun Progress(
    position: Int,
    total: Int,
) {
    Text(
        stringResource(R.string.place_progress, position, total).headingCase(),
        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The device in the HUD frame as on Device detail, then the rooms to pick from. */
@Composable
private fun Place(
    entry: HostEntry,
    rooms: List<String>,
    onPick: (String) -> Unit,
    onSkip: () -> Unit,
) {
    val host = entry.host
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.section)) {
        HudFrame(Modifier.fillMaxWidth(), contentPadding = Dimens.section) {
            Column(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            ) {
                KindTile(host.kind, hero = true)
                Text(entry.name, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(
                    host.vendor ?: stringResource(R.string.network_unknown_maker),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(looksLike(host.kind)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            Text(
                stringResource(R.string.place_question),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                rooms.forEach { room -> SuggestionChip(onClick = { onPick(room) }, label = { Text(room) }) }
            }
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.place_skip)) }
        }
    }
}

@Composable
private fun Done(
    placed: Int,
    onBack: () -> Unit,
) {
    EmptyState(
        R.drawable.ic_check_circle,
        if (placed > 0) pluralStringResource(R.plurals.place_done, placed, placed) else stringResource(R.string.place_done_none),
        Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        action = { PrimaryButton(onClick = onBack) { Text(stringResource(R.string.place_done_action)) } },
    )
}
