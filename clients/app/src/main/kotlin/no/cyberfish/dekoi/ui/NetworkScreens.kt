package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.contains
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.kindName
import no.cyberfish.dekoi.looksLike
import no.cyberfish.dekoi.ui.components.BarList
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.StaleLabel
import java.time.Duration
import java.time.Instant

private val NEW_WINDOW = Duration.ofDays(7)

private fun NetworkHost.isNew(now: Instant) = Duration.between(firstSeenAt, now) < NEW_WINDOW

@Composable
fun NetworkScreen(
    data: OwnerData,
    now: Instant,
    listState: LazyListState,
    onNavigate: (Route) -> Unit,
) {
    Screen(stringResource(R.string.tab_network), listState = listState, subtitle = { StaleLabel(data.updatedAt, now) }) {
        item {
            Text(
                stringResource(R.string.network_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            InsetSection {
                InsetRow(
                    stringResource(R.string.networks_title),
                    subtitle = pluralStringResource(R.plurals.networks_count, data.networks.size, data.networks.size),
                    leading = {
                        Icon(
                            painterResource(R.drawable.ic_wifi),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onNavigate(Route.Networks) },
                )
                data.devices.forEach { device ->
                    InsetRow(
                        stringResource(R.string.insights_open),
                        subtitle = if (data.devices.size > 1) device.name else stringResource(R.string.insights_open_subtitle),
                        leading = {
                            Icon(
                                painterResource(R.drawable.ic_network_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        onClick = { onNavigate(Route.Insights(device.id)) },
                    )
                }
            }
        }
        if (data.loaded && data.hosts.isEmpty()) item { EmptyState(R.drawable.ic_lan, stringResource(R.string.network_empty)) }
        if (data.hosts.isNotEmpty()) {
            item {
                InsetSection {
                    data.hosts.forEach { host -> HostRow(host, now) { onNavigate(Route.HostDetail(host.deviceId, host.mac)) } }
                }
            }
        }
    }
}

@Composable
private fun HostRow(
    host: NetworkHost,
    now: Instant,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val label =
        when {
            host.trusted -> R.string.network_known
            host.isNew(now) -> R.string.network_new
            else -> null
        }
    InsetRow(
        hostDisplayName(host, resources::getString),
        subtitle = listOfNotNull(stringResource(looksLike(host.kind)), host.vendor, host.ip).joinToString(" · "),
        leading = { KindTile(host.kind) },
        trailing = {
            label?.let { Text(stringResource(it), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        },
        onClick = onClick,
    )
}

@Composable
fun HostDetailScreen(
    data: OwnerData,
    now: Instant,
    deviceId: String,
    mac: String,
    onTrust: (NetworkHost, Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val host = data.hosts.firstOrNull { it.deviceId == deviceId && it.mac == mac }
    if (host == null) {
        MissingScreen(R.string.tab_network, data.loaded, R.drawable.ic_device_unknown, R.string.host_missing, onBack)
        return
    }
    val resources = LocalResources.current
    Screen(hostDisplayName(host, resources::getString), onBack = onBack) {
        item {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            ) {
                KindTile(host.kind, hero = true)
                Text(stringResource(looksLike(host.kind)), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.host_last_seen_ago, relativeTime(host.lastSeenAt, now)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            InsetSection {
                InsetRow(stringResource(R.string.host_see_alerts), onClick = { onNavigate(Route.FilteredAlerts(mac = host.mac)) })
            }
        }
        item {
            InsetSection(title = stringResource(R.string.host_about)) {
                KeyValueRow(stringResource(R.string.event_detail_vendor), host.vendor ?: stringResource(R.string.network_unknown_maker))
                host.model?.let { KeyValueRow(stringResource(R.string.host_model), it) }
                host.hostname?.let { KeyValueRow(stringResource(R.string.host_announced_name), it) }
                if (host.services.isNotEmpty()) KeyValueRow(stringResource(R.string.host_services), host.services.joinToString(", "))
            }
        }
        item {
            InsetSection(
                title = stringResource(R.string.host_connection),
                footer = stringResource(R.string.host_private_note).takeIf { host.isPrivateMac },
            ) {
                KeyValueRow(stringResource(R.string.host_address), host.ip)
                CopyableRow(stringResource(R.string.event_detail_mac), host.mac)
                KeyValueRow(stringResource(R.string.host_first_seen), absoluteTime(host.firstSeenAt))
                KeyValueRow(stringResource(R.string.host_last_seen), absoluteTime(host.lastSeenAt))
            }
        }
        item {
            InsetSection {
                InsetRow(
                    stringResource(R.string.host_known),
                    modifier = Modifier.toggleable(host.trusted, role = Role.Switch) { onTrust(host, it) },
                    trailing = { Switch(checked = host.trusted, onCheckedChange = null) },
                )
            }
        }
    }
}

@Composable
fun NetworksScreen(
    data: OwnerData,
    onBack: () -> Unit,
) {
    Screen(stringResource(R.string.networks_title), onBack = onBack) {
        if (data.loaded && data.networks.isEmpty()) item { EmptyState(R.drawable.ic_wifi, stringResource(R.string.networks_empty)) }
        items(data.networks, key = { it.deviceId + it.cidr }) { network -> NetworkCard(network, data) }
        item {
            Text(
                stringResource(R.string.networks_footer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NetworkCard(
    network: Network,
    data: OwnerData,
) {
    val dekoi = data.devices.firstOrNull { it.id == network.deviceId }
    val home = dekoi?.localIp?.let(network::contains) == true
    val hosts = data.hosts.filter { it.deviceId == network.deviceId && network.contains(it.ip) }
    val kinds =
        hosts.groupingBy { it.kind }.eachCount().entries.sortedByDescending { it.value }.map {
            stringResource(kindName(it.key)) to
                it.value
        }
    InsetSection(title = stringResource(if (home) R.string.networks_home else R.string.networks_other)) {
        KeyValueRow(stringResource(R.string.networks_addresses), network.cidr)
        KeyValueRow(stringResource(R.string.networks_found), stringResource(sourceWords(network.source)))
        network.gateway?.let { KeyValueRow(stringResource(R.string.networks_gateway), it) }
        KeyValueRow(stringResource(R.string.networks_devices), hosts.size.toString())
        if (kinds.isNotEmpty()) Box(Modifier.padding(Dimens.spaceL)) { BarList(kinds) }
    }
}

/** How DeKoi learned of a network (ADR 0010 vocabulary), in plain words. */
private fun sourceWords(source: String): Int =
    when (source) {
        "interface" -> R.string.networks_source_interface
        "router_advertisement" -> R.string.networks_source_router_advertisement
        "dhcp_route" -> R.string.networks_source_dhcp_route
        else -> R.string.networks_source_observed
    }
