package no.cyberfish.dekoi.ui.features

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.DecoyEntrance
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.DnsCheck
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.Feature
import no.cyberfish.dekoi.FeatureState
import no.cyberfish.dekoi.MonitorKind
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.decoyEntrances
import no.cyberfish.dekoi.dekoiToShow
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.featureState
import no.cyberfish.dekoi.isNew
import no.cyberfish.dekoi.lastFound
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.EntranceRow
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.address
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.dayAndMonth
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label
import no.cyberfish.dekoi.ui.learn.learnTopicFor
import no.cyberfish.dekoi.ui.relativeTime
import no.cyberfish.dekoi.wifiWatch
import no.cyberfish.dekoi.wifiWatchText
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

private const val SEPARATOR = " · "
private val ICON = 56.dp
private val STATUS_ICON = 18.dp
private val CHEVRON = 20.dp

/** What a feature is called and what it promises, in one line. */
internal class Words(
    @param:StringRes val name: Int,
    @param:StringRes val promise: Int,
)

internal fun words(feature: Feature): Words =
    when (feature) {
        Feature.DECOY -> Words(R.string.features_decoy, R.string.features_decoy_promise)
        Feature.IDS -> Words(R.string.features_ids, R.string.features_ids_promise)
        Feature.SCANS -> Words(R.string.features_scans, R.string.features_scans_promise)
        Feature.ARP_GUARD -> Words(R.string.features_arp, R.string.features_arp_promise)
        Feature.ROUTER_GUARD -> Words(R.string.features_router, R.string.features_router_promise)
        Feature.NAME_TRAP -> Words(R.string.features_names, R.string.features_names_promise)
        Feature.WIFI_GUARD -> Words(R.string.wifi_guard, R.string.features_wifi_promise)
        Feature.NETWORK_MAP -> Words(R.string.features_map, R.string.features_map_promise)
        Feature.NEW_DEVICES -> Words(R.string.features_new, R.string.features_new_promise)
        Feature.INTERNET_HEALTH -> Words(R.string.features_internet, R.string.features_internet_promise)
        Feature.OPEN_PORTS -> Words(R.string.insights_ports, R.string.features_ports_promise)
        Feature.WEEKLY_SUMMARY -> Words(R.string.settings_weekly_summary, R.string.features_summary_promise)
    }

/**
 * Everything DeKoi does, one HUD-framed card per feature: its drawing,
 * what it promises, how it is doing on [deviceId] (the DeKoi Home speaks
 * for when null) and what it last found. Without a DeKoi the cards show
 * what each feature does, without a status. One drawing moves at a time:
 * the card nearest the middle of the screen.
 */
@Composable
fun FeaturesScreen(
    data: OwnerData,
    now: Instant,
    deviceId: String?,
    weeklySummary: Boolean?,
    onNavigate: (Route) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val chosen = deviceId?.let { id -> data.devices.firstOrNull { it.id == id } }
    if (!data.loaded || (deviceId != null && chosen == null)) {
        MissingScreen(R.string.features_title, data.loaded, R.drawable.ic_device_unknown, R.string.dekoi_missing, onBack)
        return
    }
    val device = chosen ?: dekoiToShow(data.devices, now)
    val states = device?.let { dekoi -> Feature.entries.associateWith { dekoi.featureState(it, data.hosts, weeklySummary, now) } }.orEmpty()
    val listState = rememberLazyListState()
    val centred by remember(listState) { derivedStateOf { centredFeature(listState) } }
    Screen(
        stringResource(R.string.features_title),
        listState = listState,
        onBack = onBack,
        subtitle = {
            if (device != null && data.devices.size > 1) {
                Text(device.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    ) {
        item(key = "intro") { Intro(device, states, now, onNavigate) }
        Feature.entries.forEach { feature ->
            item(key = feature.name) {
                FeatureCard(
                    feature,
                    device,
                    states[feature],
                    data,
                    now,
                    playing = feature == centred,
                    onNavigate = onNavigate,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

/** The card whose middle is nearest the middle of the screen. */
private fun centredFeature(state: LazyListState): Feature? {
    val layout = state.layoutInfo
    val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo
        .mapNotNull { item -> Feature.entries.firstOrNull { it.name == item.key }?.let { it to item } }
        .minByOrNull { (_, item) -> abs(item.offset + item.size / 2 - middle) }
        ?.first
}

/** The answer first: how many features are on, or why none run. */
@Composable
private fun Intro(
    device: Device?,
    states: Map<Feature, FeatureState>,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val on = states.count { it.value.isOn }
    val total = states.size
    val summary =
        when {
            device == null -> stringResource(R.string.features_no_dekoi)
            device.status(now) == DekoiStatus.OFFLINE -> stringResource(R.string.dekoi_watching_offline)
            device.status(now) == DekoiStatus.SETTING_UP -> stringResource(R.string.status_setting_up_body)
            on == total -> pluralStringResource(R.plurals.features_all_on, total, total)
            else -> pluralStringResource(R.plurals.features_some_on, total, on, total)
        }
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        Text(
            summary,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            stringResource(R.string.features_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (device == null) {
            TextButton(onClick = { onNavigate(Route.AddDekoi) }) { Text(stringResource(R.string.action_add_dekoi)) }
        }
    }
}

/** One feature in the HUD frame. */
@Composable
private fun FeatureCard(
    feature: Feature,
    device: Device?,
    state: FeatureState?,
    data: OwnerData,
    now: Instant,
    playing: Boolean,
    onNavigate: (Route) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val words = words(feature)
    HudFrame(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL), verticalAlignment = Alignment.CenterVertically) {
                FeatureIcon(feature, playing, Modifier.size(ICON))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
                    Text(
                        stringResource(words.name).headingCase(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        stringResource(words.promise),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (feature == Feature.DECOY) DecoyName()
            if (device != null && state != null) StatusLine(state, detail(feature, device, state, data, now))
            device?.let { lastFound(feature, it.id, data.events) }?.let { event ->
                LastFound(event, now) { onNavigate(Route.AlertDetail(event.id)) }
            }
            if (feature == Feature.DECOY && device != null) TryIt(device.decoyEntrances(now))
            Actions(feature, device, state, onNavigate, onOpenSettings)
        }
    }
}

/** The name the decoy goes by on the network, as Windows shows it (ADR 0018). */
@Composable
private fun DecoyName() {
    Text(
        stringResource(R.string.features_decoy_name, BuildConfig.DECOY_NAME.uppercase(Locale.ROOT)),
        style = MaterialTheme.typography.address,
    )
}

/** Glyph and colour of a state: a green check while on, amber when it fails, blue when the owner can turn it on, grey otherwise. */
@Composable
private fun stateMark(state: FeatureState): Pair<Int, Color> {
    val colors = LocalSeverityColors.current
    return when (state) {
        FeatureState.ON, FeatureState.LEARNING -> {
            R.drawable.ic_check_circle to colors.safe.color
        }

        FeatureState.FAILED -> {
            R.drawable.ic_warning to colors.warning.color
        }

        FeatureState.NEEDS_WIFI_NAME -> {
            R.drawable.ic_info to MaterialTheme.colorScheme.primary
        }

        FeatureState.STARTING, FeatureState.WAITING_FOR_UPDATE, FeatureState.PAUSED, FeatureState.OFF, FeatureState.UNKNOWN -> {
            R.drawable.ic_info to colors.offline
        }
    }
}

@StringRes
private fun stateWord(state: FeatureState): Int =
    when (state) {
        FeatureState.ON -> R.string.dekoi_state_on
        FeatureState.LEARNING -> R.string.features_state_learning
        FeatureState.STARTING -> R.string.dekoi_state_starting
        FeatureState.NEEDS_WIFI_NAME -> R.string.features_state_wifi_name
        FeatureState.WAITING_FOR_UPDATE -> R.string.features_state_update
        FeatureState.PAUSED -> R.string.home_checks_paused
        FeatureState.OFF -> R.string.dekoi_state_off
        FeatureState.FAILED -> R.string.dekoi_state_failed
        FeatureState.UNKNOWN -> R.string.dekoi_state_unknown
    }

/** The state in colour, glyph and word, and under it what the DeKoi reports about it. */
@Composable
private fun StatusLine(
    state: FeatureState,
    detail: String?,
) {
    val (glyph, tint) = stateMark(state)
    Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Glyph(glyph, tint)
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(stringResource(stateWord(state)), style = MaterialTheme.typography.labelLarge, color = tint)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Glyph(
    @DrawableRes glyph: Int,
    tint: Color,
) {
    Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(STATUS_ICON), tint = tint)
}

/**
 * The numbers behind a state, only while it holds: an offline DeKoi's last
 * report says nothing about now. Wi-Fi Guard says what it is doing or why
 * it is off, as in Settings.
 */
@Composable
private fun detail(
    feature: Feature,
    device: Device,
    state: FeatureState,
    data: OwnerData,
    now: Instant,
): String? {
    if (state == FeatureState.PAUSED) return null
    if (feature == Feature.WIFI_GUARD) return device.wifiWatch?.let { stringResource(wifiWatchText(device, now)) }
    if (!state.isOn) return null
    val mine = data.hosts.filter { it.deviceId == device.id }
    return when (feature) {
        Feature.DECOY -> {
            device.services.size
                .takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.dekoi_watch_decoys, it, it) }
        }

        Feature.IDS -> {
            idsDetail(device)
        }

        Feature.NETWORK_MAP -> {
            pluralStringResource(R.plurals.dekoi_watch_inventory, mine.size, mine.size)
        }

        Feature.NEW_DEVICES -> {
            if (state == FeatureState.LEARNING) {
                stringResource(R.string.features_new_learning)
            } else {
                mine.count { it.isNew(now) }.let { pluralStringResource(R.plurals.stat_new_this_week, it, it) }
            }
        }

        Feature.INTERNET_HEALTH -> {
            stringResource(
                when (DnsCheck.of(device.dnsCheck)) {
                    DnsCheck.OK -> R.string.insights_dns_ok
                    DnsCheck.MISMATCH -> R.string.insights_dns_mismatch
                    DnsCheck.UNAVAILABLE -> R.string.insights_dns_unavailable
                    null -> R.string.insights_dns_unknown
                },
            )
        }

        Feature.OPEN_PORTS -> {
            device.forwardsCheckedAt?.let { stringResource(R.string.insights_checked, relativeTime(it, now)) }
                ?: stringResource(R.string.features_ports_no_list)
        }

        Feature.SCANS, Feature.ARP_GUARD, Feature.ROUTER_GUARD, Feature.NAME_TRAP, Feature.WIFI_GUARD, Feature.WEEKLY_SUMMARY -> {
            null
        }
    }
}

/** "48,213 attack patterns · Updated 3 Nov", as much of it as the DeKoi reports. */
@Composable
private fun idsDetail(device: Device): String? {
    val ids = device.monitors.firstOrNull { it.kind == MonitorKind.IDS }?.detail ?: return null
    return listOfNotNull(
        ids.rules?.let { pluralStringResource(R.plurals.features_ids_rules, it, NumberFormat.getIntegerInstance().format(it)) },
        ids.rulesetDate?.let { stringResource(R.string.updated_ago, dayAndMonth(it)) },
    ).joinToString(SEPARATOR).ifEmpty { null }
}

/** When the feature last found something, and what; opens that alert. */
@Composable
private fun LastFound(
    event: Event,
    now: Instant,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.touchTarget)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.features_last_found, relativeTime(event.occurredAt, now)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                stringResource(eventText(event).action),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(CHEVRON),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The decoy as an intruder would see it, from a computer on the same network; only while the DeKoi is online. */
@Composable
private fun TryIt(entrances: Map<DecoyEntrance, String>) {
    if (entrances.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Text(
            stringResource(R.string.features_try).headingCase(),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.dekoi_try_body), style = MaterialTheme.typography.bodyMedium)
        Column {
            entrances.forEach { (entrance, address) ->
                HorizontalDivider()
                EntranceRow(entrance, address)
            }
        }
    }
}

/** Where to see more or change the feature, and its Learn topic. */
@Composable
private fun Actions(
    feature: Feature,
    device: Device?,
    state: FeatureState?,
    onNavigate: (Route) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val topic = feature.kinds.firstNotNullOfOrNull(::learnTopicFor)
    val action: Pair<Int, () -> Unit>? =
        when (feature) {
            Feature.WIFI_GUARD -> {
                (R.string.features_add_wifi_name to onOpenSettings).takeIf { state == FeatureState.NEEDS_WIFI_NAME }
            }

            Feature.NETWORK_MAP -> {
                R.string.features_open_map to { onNavigate(Route.Network) }
            }

            Feature.NEW_DEVICES -> {
                R.string.features_see_devices to { onNavigate(Route.Network) }
            }

            Feature.INTERNET_HEALTH, Feature.OPEN_PORTS -> {
                device?.let {
                    R.string.features_open_insights to
                        { onNavigate(Route.Insights(it.id)) }
                }
            }

            Feature.WEEKLY_SUMMARY -> {
                R.string.features_open_settings to onOpenSettings
            }

            Feature.DECOY, Feature.IDS, Feature.SCANS, Feature.ARP_GUARD, Feature.ROUTER_GUARD, Feature.NAME_TRAP -> {
                null
            }
        }
    if (topic == null && action == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(stringResource(label)) } }
        topic?.let { TextButton(onClick = { onNavigate(Route.LearnTopic(it.id)) }) { Text(stringResource(R.string.alert_learn_more)) } }
    }
}
