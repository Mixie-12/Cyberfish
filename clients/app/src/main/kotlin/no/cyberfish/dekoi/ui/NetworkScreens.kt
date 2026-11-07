package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.contains
import no.cyberfish.dekoi.kindName
import no.cyberfish.dekoi.ui.components.BarList
import no.cyberfish.dekoi.ui.components.EmptyState
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.Screen

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
