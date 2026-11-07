package no.cyberfish.dekoi.ui.network

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.DnsCheck
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HealthSample
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.NetworkInsights
import no.cyberfish.dekoi.PortForward
import no.cyberfish.dekoi.Probe
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SpeedTest
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.internetUptime
import no.cyberfish.dekoi.outages
import no.cyberfish.dekoi.probeSummary
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.UptimeWeek
import no.cyberfish.dekoi.ui.components.CopyableRow
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Section
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.TechnicalDetailsRow
import no.cyberfish.dekoi.ui.expandSpring
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label
import no.cyberfish.dekoi.ui.learn.LearnTopic
import no.cyberfish.dekoi.ui.relativeTime
import no.cyberfish.dekoi.uptimePercent
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

private const val WEEK_HOURS = 7 * 24
private const val MAX_OUTAGES = 5
private const val PERCENT = 100.0

/** The IGD wildcard (backend: port_forwards.external_port): every port. */
private const val EVERY_PORT = 0
private val SPARK_WIDTH = 72.dp
private val SPARK_HEIGHT = 24.dp
private val SPEED_CHART = 48.dp
private val SKELETON = 96.dp

/** Port rows sit under their device's name, past its tile. */
private val PORT_INDENT = Dimens.tile + Dimens.spaceM

/**
 * What one DeKoi measured about its network (ADR 0015): the week of
 * internet in the HUD frame, then the internet, web addresses, open ports,
 * the connection and speed. Loads each time the screen comes to the front,
 * so a failed load is tried again.
 */
@Composable
fun InsightsScreen(
    data: OwnerData,
    insights: NetworkInsights?,
    now: Instant,
    deviceId: String,
    onLoad: (String) -> Unit,
    onSpeedTest: (Device, Boolean) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val device = data.devices.firstOrNull { it.id == deviceId }
    if (device == null) {
        MissingScreen(R.string.insights_title, data.loaded, R.drawable.ic_network_check, R.string.dekoi_missing, onBack)
        return
    }
    LifecycleResumeEffect(deviceId) {
        onLoad(deviceId)
        onPauseOrDispose { }
    }
    // What the view model holds may still be another DeKoi's.
    val loaded = insights?.takeIf { it.deviceId == deviceId }
    val several = data.devices.size > 1
    Screen(
        stringResource(R.string.insights_title),
        onBack = onBack,
        subtitle = {
            if (several) Text(device.name, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    ) {
        item { Hero(loaded?.samples, now) }
        item { Internet(1, loaded?.samples) }
        item { WebAddresses(2, device, data.events, now, onNavigate) }
        item { Ports(3, device, loaded?.forwards, data.hosts, onNavigate) }
        item { Connection(4, device) }
        item { Speed(5, device, loaded?.speedTests, onSpeedTest) }
    }
}

/** The share of the week the internet worked, and the week hour by hour. */
@Composable
private fun Hero(
    samples: List<HealthSample>?,
    now: Instant,
) {
    HudFrame(Modifier.fillMaxWidth(), contentPadding = Dimens.gutter) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            Text(
                stringResource(R.string.last_seven_days).headingCase(),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (samples == null) {
                Skeleton(Modifier.fillMaxWidth().height(SKELETON))
                return@Column
            }
            val hours = internetUptime(samples, now, WEEK_HOURS)
            val percent = uptimePercent(hours)
            if (percent == null) {
                Text(stringResource(R.string.insights_internet_none), style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            val known = hours.count { it != Uptime.UNKNOWN }
            Text(percentText(percent), style = MaterialTheme.typography.displayMedium)
            UptimeWeek(
                hours,
                pluralStringResource(R.plurals.insights_uptime_summary, known, hours.count { it == Uptime.ONLINE }, known),
            )
        }
    }
}

/** The three probes, how fast they answered and how many were lost, then the times without internet. */
@Composable
private fun Internet(
    number: Int,
    samples: List<HealthSample>?,
) {
    Section(number, stringResource(R.string.insights_internet)) {
        if (samples == null) {
            Skeleton(Modifier.fillMaxWidth().height(SKELETON))
            return@Section
        }
        InsetSection(footer = stringResource(R.string.insights_internet_body)) {
            Probe.entries.forEach { ProbeRow(it, samples) }
        }
        // Without a single measurement there is nothing to say about outages.
        if (samples.isEmpty()) return@Section
        val outages = outages(samples)
        InsetSection(title = stringResource(R.string.insights_outages)) {
            if (outages.isEmpty()) {
                InsetRow(
                    stringResource(R.string.insights_outages_none),
                    leading = { Glyph(R.drawable.ic_check_circle, LocalSeverityColors.current.safe.color) },
                )
            }
            outages.take(MAX_OUTAGES).forEach { outage ->
                val text = if (outage.atHome) R.plurals.insights_outage_home else R.plurals.insights_outage
                InsetRow(
                    pluralStringResource(text, outage.minutes, outage.minutes),
                    subtitle = weekdayAndTime(outage.start),
                    leading = { Glyph(R.drawable.ic_warning, LocalSeverityColors.current.warning.color) },
                )
            }
        }
    }
}

@Composable
private fun ProbeRow(
    probe: Probe,
    samples: List<HealthSample>,
) {
    val (title, glyph) =
        when (probe) {
            Probe.GATEWAY -> R.string.insights_probe_gateway to R.drawable.ic_router
            Probe.INTERNET -> R.string.insights_probe_internet to R.drawable.ic_network_check
            Probe.RESOLVER -> R.string.insights_probe_resolver to R.drawable.ic_dns
        }
    val summary = probeSummary(samples, probe)
    val typical = summary.typicalMs
    val lost = summary.lostPercent
    InsetRow(
        stringResource(title),
        subtitle =
            when {
                lost == null -> stringResource(R.string.insights_probe_none)
                typical == null -> stringResource(R.string.insights_probe_no_answer)
                else -> stringResource(R.string.insights_probe_value, typical, percentText(lost))
            },
        leading = { Glyph(glyph, MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { Sparkline(summary.medians, MaterialTheme.colorScheme.primary, Modifier.size(SPARK_WIDTH, SPARK_HEIGHT)) },
    )
}

/**
 * Whether the router's answers for well-known names match an independent
 * service's. When they do not, the one primary action on the screen opens
 * what to do: the alert about it, or the Learn topic before there is one.
 */
@Composable
private fun WebAddresses(
    number: Int,
    device: Device,
    events: List<Event>,
    now: Instant,
    onNavigate: (Route) -> Unit,
) {
    val colors = LocalSeverityColors.current
    val check = DnsCheck.of(device.dnsCheck)
    val (text, glyph, tint) =
        when (check) {
            DnsCheck.OK -> Triple(R.string.insights_dns_ok, R.drawable.ic_check_circle, colors.safe.color)
            DnsCheck.MISMATCH -> Triple(R.string.insights_dns_mismatch, R.drawable.ic_error, colors.critical.color)
            DnsCheck.UNAVAILABLE -> Triple(R.string.insights_dns_unavailable, R.drawable.ic_info, colors.offline)
            null -> Triple(R.string.insights_dns_unknown, R.drawable.ic_info, colors.offline)
        }
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    val learn = Route.LearnTopic(LearnTopic.DNS_TAMPERING.id)
    Section(number, stringResource(R.string.insights_dns)) {
        InsetSection(Modifier.animateContentSize(spec), footer = stringResource(R.string.insights_dns_body)) {
            InsetRow(
                stringResource(text),
                subtitle = device.dnsCheckedAt?.let { stringResource(R.string.insights_checked, relativeTime(it, now)) },
                leading = { Glyph(glyph, tint) },
            )
            device.dnsResolver?.let { resolver ->
                TechnicalDetailsRow(open) { open = it }
                if (open) CopyableRow(stringResource(R.string.insights_resolver), resolver)
            }
        }
        if (check == DnsCheck.MISMATCH) {
            val alert = events.firstOrNull { it.deviceId == device.id && it.kind == "dns_hijack" }
            PrimaryButton(onClick = { onNavigate(alert?.let { Route.AlertDetail(it.id) } ?: learn) }) {
                Text(stringResource(R.string.host_what_to_do))
            }
        } else {
            LearnMore { onNavigate(learn) }
        }
    }
}

/** The ports the router opened to the internet, under the device each leads to. */
@Composable
private fun Ports(
    number: Int,
    device: Device,
    forwards: List<PortForward>?,
    hosts: List<NetworkHost>,
    onNavigate: (Route) -> Unit,
) {
    Section(number, stringResource(R.string.insights_ports)) {
        if (forwards == null) {
            Skeleton(Modifier.fillMaxWidth().height(SKELETON))
            return@Section
        }
        // Until the router has answered once, an empty list means not known, not none.
        val known = forwards.takeIf { device.forwardsCheckedAt != null }
        if (!known.isNullOrEmpty()) {
            Text(pluralStringResource(R.plurals.insights_ports_open, known.size, known.size), style = MaterialTheme.typography.bodyLarge)
        }
        InsetSection(footer = stringResource(R.string.insights_ports_body)) {
            when {
                known == null -> {
                    InsetRow(
                        stringResource(R.string.insights_ports_unknown),
                        leading = { Glyph(R.drawable.ic_info, LocalSeverityColors.current.offline) },
                    )
                }

                known.isEmpty() -> {
                    InsetRow(
                        stringResource(R.string.insights_ports_none),
                        leading = { Glyph(R.drawable.ic_check_circle, LocalSeverityColors.current.safe.color) },
                    )
                }

                else -> {
                    known.groupBy { it.internalClient }.forEach { (address, ports) ->
                        val host = hosts.filter { it.deviceId == device.id && it.ip == address }.maxByOrNull { it.lastSeenAt }
                        ForwardedDevice(host, address, onNavigate)
                        ports.forEach { PortRow(it) }
                    }
                }
            }
        }
        LearnMore { onNavigate(Route.LearnTopic(LearnTopic.PORT_FORWARD.id)) }
    }
}

/** The device ports lead to: the one the inventory names at that address, else its address alone. */
@Composable
private fun ForwardedDevice(
    host: NetworkHost?,
    address: String,
    onNavigate: (Route) -> Unit,
) {
    val resources = LocalResources.current
    InsetRow(
        host?.let { hostDisplayName(it, resources::getString) } ?: stringResource(R.string.kind_unknown),
        subtitle = address,
        leading = { KindTile(host?.kind ?: HostKind.UNKNOWN) },
        onClick = host?.let { { onNavigate(Route.HostDetail(it.deviceId, it.mac)) } },
    )
}

/** A port and the name the device gave it, which any device can set: plain text only. */
@Composable
private fun PortRow(forward: PortForward) {
    val protocol = forward.protocol.uppercase(Locale.ROOT)
    InsetRow(
        if (forward.externalPort == EVERY_PORT) {
            stringResource(R.string.insights_port_every, protocol)
        } else {
            stringResource(R.string.insights_port, forward.externalPort, protocol)
        },
        Modifier.padding(start = PORT_INDENT),
        subtitle = forward.description?.let { stringResource(R.string.insights_port_named, it) },
    )
}

@Composable
private fun Connection(
    number: Int,
    device: Device,
) {
    val locale = LocalLocale.current.platformLocale
    Section(number, stringResource(R.string.insights_connection)) {
        InsetSection(footer = stringResource(R.string.insights_connection_body)) {
            val address = device.publicIp
            if (address == null) {
                InsetRow(stringResource(R.string.insights_connection_unknown))
            } else {
                CopyableRow(stringResource(R.string.insights_public_ip), address)
            }
            device.publicCountry?.let {
                KeyValueRow(
                    stringResource(R.string.insights_country),
                    Locale
                        .Builder()
                        .setRegion(it)
                        .build()
                        .getDisplayCountry(locale),
                )
            }
        }
        Text(
            stringResource(R.string.insights_bandwidth),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The owner's nightly test (devices.speed_test) and its results, newest first. */
@Composable
private fun Speed(
    number: Int,
    device: Device,
    tests: List<SpeedTest>?,
    onSpeedTest: (Device, Boolean) -> Unit,
) {
    Section(number, stringResource(R.string.insights_speed)) {
        InsetSection(footer = stringResource(R.string.insights_speed_body)) {
            InsetRow(
                stringResource(R.string.insights_speed_toggle),
                modifier = Modifier.toggleable(device.speedTest, role = Role.Switch) { onSpeedTest(device, it) },
                trailing = { Switch(checked = device.speedTest, onCheckedChange = null) },
            )
        }
        when {
            tests == null -> {
                Skeleton(Modifier.fillMaxWidth().height(SKELETON))
            }

            tests.isEmpty() -> {
                Text(
                    stringResource(if (device.speedTest) R.string.insights_speed_waiting else R.string.insights_speed_off),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                Sparkline(
                    tests.reversed().map { it.downloadMbps.roundToInt() },
                    MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxWidth().height(SPEED_CHART),
                )
                InsetSection {
                    tests.forEach {
                        KeyValueRow(weekdayAndTime(it.testedAt), stringResource(R.string.insights_speed_value, mbpsText(it.downloadMbps)))
                    }
                }
            }
        }
    }
}

@Composable
private fun LearnMore(onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(R.string.alert_learn_more)) }
}

@Composable
private fun Glyph(
    @DrawableRes glyph: Int,
    tint: Color,
) {
    Icon(painterResource(glyph), contentDescription = null, tint = tint)
}

@Composable
private fun percentText(percent: Double): String =
    NumberFormat.getPercentInstance(LocalLocale.current.platformLocale).apply { maximumFractionDigits = 1 }.format(percent / PERCENT)

@Composable
private fun mbpsText(mbps: Double): String =
    NumberFormat.getNumberInstance(LocalLocale.current.platformLocale).apply { maximumFractionDigits = 1 }.format(mbps)

/** "Tue 21:00", in the phone's language and time zone. */
@Composable
private fun weekdayAndTime(time: Instant): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        time.toEpochMilli(),
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME,
    )
