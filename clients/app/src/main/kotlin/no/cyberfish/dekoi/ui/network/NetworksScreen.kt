package no.cyberfish.dekoi.ui.network

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.contains
import no.cyberfish.dekoi.kindName
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.components.BarList
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.Screen

/**
 * The networks DeKoi has evidence of (docs/design.md, "Networks"). Until
 * a DeKoi has reported them, its own address stands in for its network.
 */
@Composable
fun NetworksScreen(
    data: OwnerData,
    onBack: () -> Unit,
) {
    Screen(stringResource(R.string.networks_title), onBack = onBack) {
        if (data.loaded && data.networks.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.networks_pending),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            data.devices.forEach { device ->
                val address = device.localIp ?: return@forEach
                item(key = device.id) {
                    InsetSection(title = stringResource(R.string.networks_home)) {
                        KeyValueRow(stringResource(R.string.networks_dekoi_address), address)
                        Devices(data.hosts.filter { it.deviceId == device.id })
                    }
                }
            }
        }
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
    InsetSection(title = stringResource(if (home) R.string.networks_home else R.string.networks_other)) {
        KeyValueRow(stringResource(R.string.networks_addresses), network.cidr)
        KeyValueRow(stringResource(R.string.networks_found), stringResource(sourceWords(network.source)))
        network.gateway?.let { KeyValueRow(stringResource(R.string.networks_gateway), it) }
        Devices(data.hosts.filter { it.deviceId == network.deviceId && network.contains(it.ip) })
    }
}

/** How many devices are on a network, never a bare 0, and how many of each kind. */
@Composable
private fun Devices(hosts: List<NetworkHost>) {
    KeyValueRow(
        stringResource(R.string.networks_devices),
        if (hosts.isEmpty()) stringResource(R.string.networks_none_seen) else hosts.size.toString(),
    )
    val kinds =
        hosts
            .groupingBy { it.kind }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
    if (kinds.isEmpty()) return
    Box(Modifier.padding(Dimens.spaceL)) { BarList(kinds.map { stringResource(kindName(it.key)) to it.value }) }
}

/** How DeKoi learned of a network (ADR 0010 vocabulary), in plain words. */
private fun sourceWords(source: String): Int =
    when (source) {
        "interface" -> R.string.networks_source_interface
        "router_advertisement" -> R.string.networks_source_router_advertisement
        "dhcp_route" -> R.string.networks_source_dhcp_route
        else -> R.string.networks_source_observed
    }
