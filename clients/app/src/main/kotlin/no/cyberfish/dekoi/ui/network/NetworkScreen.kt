package no.cyberfish.dekoi.ui.network

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityManager
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.attentionByMac
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.kindName
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SegmentedControl
import no.cyberfish.dekoi.ui.components.SeverityChip
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.StaleLabel
import java.time.Instant

private const val PREFERENCES = "network"
private const val VIEW = "view"
private val SKELETON = 240.dp

/** How the Network tab shows the devices, in the order of the switch. */
private enum class NetworkView(
    @param:StringRes val label: Int,
) {
    MAP(R.string.network_view_map),
    ROOMS(R.string.network_view_rooms),
    LIST(R.string.network_view_list),
}

@Composable
fun NetworkScreen(
    data: OwnerData,
    now: Instant,
    listState: LazyListState,
    onNavigate: (Route) -> Unit,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var view by remember { mutableStateOf(savedView(context, preferences)) }
    val choose = { chosen: NetworkView ->
        preferences.edit { putString(VIEW, chosen.name) }
        view = chosen
    }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(HostSort.NAME) }
    var byRoom by rememberSaveable { mutableStateOf(false) }
    val entries =
        hostEntries(
            data.hosts,
            attentionByMac(data.events, data.trustedMacs, now),
            now,
            { hostDisplayName(it, resources::getString) },
            { resources.getString(kindName(it)) },
        )
    val summary = summary(entries)
    val labels = NetworkView.entries.map { stringResource(it.label) }
    val open = { entry: HostEntry -> onNavigate(entry.host.detail) }
    Screen(stringResource(R.string.tab_network), listState = listState, subtitle = { StaleLabel(data.updatedAt, now) }) {
        if (!data.loaded) {
            item { Skeleton(Modifier.fillMaxWidth().height(SKELETON), MaterialTheme.shapes.large) }
            return@Screen
        }
        if (entries.isNotEmpty()) {
            item { Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Links(data.networks.size, data.devices, onNavigate) }
        if (entries.isEmpty()) {
            item { EmptyState(R.drawable.ic_lan, stringResource(R.string.network_empty)) }
            return@Screen
        }
        item { SegmentedControl(labels, view.ordinal, { choose(NetworkView.entries[it]) }) }
        when (view) {
            NetworkView.MAP -> {
                item { NetworkMap(entries, data.devices, data.networks, now, summary, onNavigate) { choose(NetworkView.LIST) } }
            }

            NetworkView.ROOMS -> {
                item { RoomsView(entries, now, open) { onNavigate(Route.PlaceDevices) } }
            }

            NetworkView.LIST -> {
                val rooms = entries.any { it.host.room != null }
                item { ListControls(query, { query = it }, sort, { sort = it }, rooms, byRoom, { byRoom = it }) }
                val groups = sections(entries, query, sort, rooms = byRoom && rooms)
                if (groups.isEmpty()) item { EmptyState(R.drawable.ic_search, stringResource(R.string.network_no_match)) }
                groups.forEach { (group, rows) ->
                    item {
                        InsetSection(title = group.title(resources::getString)) {
                            rows.forEach { HostRow(it, now) { open(it) } }
                        }
                    }
                }
            }
        }
    }
}

// Without a saved choice, TalkBack users start on the list: the map is a
// single summary node for them.
private fun savedView(
    context: Context,
    preferences: SharedPreferences,
): NetworkView {
    val saved = preferences.getString(VIEW, null)
    val talkBack = context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    return NetworkView.entries.firstOrNull { it.name == saved } ?: if (talkBack) NetworkView.LIST else NetworkView.MAP
}

/** "38 devices · 3 new this week · 1 needs attention", leaving out what is zero. */
@Composable
private fun summary(entries: List<HostEntry>): String {
    val new = entries.count { it.new }
    val attention = entries.count { it.attention != null }
    return listOfNotNull(
        pluralStringResource(R.plurals.network_devices, entries.size, entries.size),
        new.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.stat_new_this_week, it, it) },
        attention.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.network_need_attention, it, it) },
    ).joinToString(" · ")
}

/**
 * The Networks screen, the Insights of each DeKoi (ADR 0015), and Add DeKoi,
 * where an owner looking at devices finds it; all show before any device does.
 */
@Composable
private fun Links(
    networks: Int,
    devices: List<Device>,
    onNavigate: (Route) -> Unit,
) {
    InsetSection {
        InsetRow(
            stringResource(R.string.networks_title),
            subtitle =
                if (networks > 0) {
                    pluralStringResource(R.plurals.networks_count, networks, networks)
                } else {
                    stringResource(R.string.networks_not_reported)
                },
            leading = {
                Icon(painterResource(R.drawable.ic_wifi), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            },
            onClick = { onNavigate(Route.Networks) },
        )
        devices.forEach { device ->
            InsetRow(
                stringResource(R.string.insights_title),
                subtitle = if (devices.size > 1) device.name else stringResource(R.string.insights_open_subtitle),
                leading = {
                    Icon(painterResource(R.drawable.ic_network_check), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                onClick = { onNavigate(Route.Insights(device.id)) },
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
private fun ListControls(
    query: String,
    onQuery: (String) -> Unit,
    sort: HostSort,
    onSort: (HostSort) -> Unit,
    rooms: Boolean,
    byRoom: Boolean,
    onByRoom: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(stringResource(R.string.network_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            trailingIcon =
                if (query.isEmpty()) {
                    null
                } else {
                    {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.network_search_clear))
                        }
                    }
                },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            SortMenu(sort, onSort)
            if (rooms) {
                FilterChip(selected = byRoom, onClick = { onByRoom(!byRoom) }, label = { Text(stringResource(R.string.network_by_room)) })
            }
        }
    }
}

@Composable
private fun SortMenu(
    sort: HostSort,
    onSort: (HostSort) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.network_sort, stringResource(sort.label)))
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            HostSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    onClick = {
                        onSort(option)
                        open = false
                    },
                    trailingIcon =
                        if (option == sort) {
                            { Icon(painterResource(R.drawable.ic_check), contentDescription = null) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}

@get:StringRes
private val HostSort.label: Int
    get() =
        when (this) {
            HostSort.NAME -> R.string.network_sort_name
            HostSort.LAST_SEEN -> R.string.network_sort_last_seen
            HostSort.KIND -> R.string.network_sort_kind
        }

/** A heading in the list or a cluster on the map, in plain words. A room is owner text: plain text only. */
internal fun Group.title(string: (Int) -> String): String =
    when (this) {
        is Group.Status -> {
            string(
                when (section) {
                    HostSection.ATTENTION -> R.string.network_attention
                    HostSection.NEW -> R.string.network_new_this_week
                    HostSection.ONLINE -> R.string.network_online
                    HostSection.NOT_SEEN -> R.string.network_not_seen
                },
            )
        }

        is Group.Kind -> {
            string(kindName(kind))
        }

        is Group.Room -> {
            name ?: string(R.string.network_no_room)
        }
    }

/** Kind, name, "Looks like a TV · LG · 192.168.1.23", and a tag for alerts, new or known hosts. */
@Composable
private fun HostRow(
    entry: HostEntry,
    now: Instant,
    onClick: () -> Unit,
) {
    val host = entry.host
    val status = seenText(entry.online, host.lastSeenAt, now)
    InsetRow(
        entry.name,
        modifier = Modifier.semantics { stateDescription = status },
        subtitle = listOfNotNull(stringResource(looksLike(host.kind)), host.vendor, host.ip).joinToString(" · "),
        leading = { HostTile(host, entry.online, attention = null) },
        trailing = {
            when {
                entry.attention != null -> SeverityChip(entry.attention)
                host.trusted -> Tag(stringResource(R.string.known_device))
                entry.new -> Tag(stringResource(R.string.network_new), color = MaterialTheme.colorScheme.primary)
            }
        },
        onClick = onClick,
    )
}
