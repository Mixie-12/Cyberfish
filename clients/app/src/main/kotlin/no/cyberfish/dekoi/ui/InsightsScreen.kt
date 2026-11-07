package no.cyberfish.dekoi.ui

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.DnsCheck
import no.cyberfish.dekoi.HealthSample
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.NetworkInsights
import no.cyberfish.dekoi.PortForward
import no.cyberfish.dekoi.Probe
import no.cyberfish.dekoi.ProbeSummary
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SpeedTest
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.hostDisplayName
import no.cyberfish.dekoi.internetUptime
import no.cyberfish.dekoi.outages
import no.cyberfish.dekoi.probeSummary
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Skeleton
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.UptimeStrip
import no.cyberfish.dekoi.ui.components.kindIcon
import no.cyberfish.dekoi.uptimePercent
import java.text.NumberFormat
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

// The CyberFish brand (ADR 0020): numbered, widely tracked uppercase labels,
// one figure per section, hairlines instead of cards. Colours and type come
// from the theme.

private const val WEEK_HOURS = 7 * 24
private const val MAX_OUTAGES = 5
private const val PERCENT = 100.0
private val LABEL_TRACKING = 0.12.em
private val SPARK_WIDTH = 72.dp
private val SPARK_HEIGHT = 24.dp
private val SPEED_CHART_HEIGHT = 48.dp
private val SKELETON_HEIGHT = 96.dp
private val GLYPH = 24.dp

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
    LaunchedEffect(deviceId) { onLoad(deviceId) }
    // What the view model holds may still be another DeKoi's.
    val loaded = insights?.takeIf { it.deviceId == deviceId }
    Screen(stringResource(R.string.insights_title).uppercase(locale()), onBack = onBack) {
        item { InternetSection(loaded?.samples, now) }
        item { DnsSection(device, now) }
        item { PortsSection(device, loaded?.forwards, data.hosts, onNavigate) }
        item { ConnectionSection(device) }
        item { SpeedSection(device, loaded?.speedTests, onSpeedTest) }
    }
}

@Composable
private fun InternetSection(
    samples: List<HealthSample>?,
    now: Instant,
) {
    val hours = samples?.let { internetUptime(it, now, WEEK_HOURS) }
    val percent = hours?.let(::uptimePercent)
    val body = if (samples != null && percent == null) R.string.insights_internet_none else R.string.insights_internet_body
    Section(1, R.string.insights_internet, percent?.let { percentText(it) }, stringResource(body)) {
        if (samples == null || hours == null) {
            Skeleton(Modifier.fillMaxWidth().height(SKELETON_HEIGHT))
            return@Section
        }
        val known = hours.count { it != Uptime.UNKNOWN }
        if (known > 0) {
            val online = hours.count { it == Uptime.ONLINE }
            UptimeStrip(hours, pluralStringResource(R.plurals.insights_uptime_summary, known, online, known))
        }
        Probe.entries.forEach { ProbeLine(it, probeSummary(samples, it)) }
        val outages = outages(samples)
        if (outages.isEmpty()) Line(stringResource(R.string.insights_outages_none))
        outages.take(MAX_OUTAGES).forEach { outage ->
            val text = if (outage.atHome) R.plurals.insights_outage_home else R.plurals.insights_outage
            Line(
                pluralStringResource(text, outage.minutes, weekdayAndTime(outage.start), outage.minutes),
                leading = { Glyph(R.drawable.ic_warning, LocalSeverityColors.current.warning.color) },
            )
        }
    }
}

@Composable
private fun ProbeLine(
    probe: Probe,
    summary: ProbeSummary,
) {
    val (title, icon) =
        when (probe) {
            Probe.GATEWAY -> R.string.insights_probe_gateway to R.drawable.ic_router
            Probe.INTERNET -> R.string.insights_probe_internet to R.drawable.ic_network_check
            Probe.RESOLVER -> R.string.insights_probe_resolver to R.drawable.ic_dns
        }
    val typical = summary.typicalMs
    val lost = summary.lostPercent
    val value =
        when {
            lost == null -> stringResource(R.string.insights_probe_none)
            typical == null -> stringResource(R.string.insights_probe_no_answer)
            else -> stringResource(R.string.insights_probe_value, typical, percentText(lost))
        }
    Line(
        stringResource(title),
        subtitle = value,
        leading = { Glyph(icon, MaterialTheme.colorScheme.onSurfaceVariant) },
        trailing = { Sparkline(summary.medians, MaterialTheme.colorScheme.primary, Modifier.size(SPARK_WIDTH, SPARK_HEIGHT)) },
    )
}

@Composable
private fun DnsSection(
    device: Device,
    now: Instant,
) {
    val colors = LocalSeverityColors.current
    val (text, icon, color) =
        when (DnsCheck.of(device.dnsCheck)) {
            DnsCheck.OK -> Triple(R.string.insights_dns_ok, R.drawable.ic_check_circle, colors.safe.color)
            DnsCheck.MISMATCH -> Triple(R.string.insights_dns_mismatch, R.drawable.ic_warning, colors.critical.color)
            DnsCheck.UNAVAILABLE -> Triple(R.string.insights_dns_unavailable, R.drawable.ic_info, colors.info.color)
            null -> Triple(R.string.insights_dns_unknown, R.drawable.ic_info, colors.info.color)
        }
    Section(2, R.string.insights_dns, figure = null, body = stringResource(R.string.insights_dns_body)) {
        Line(
            stringResource(text),
            subtitle = device.dnsCheckedAt?.let { stringResource(R.string.insights_checked, relativeTime(it, now)) },
            leading = { Glyph(icon, color) },
        )
        device.dnsResolver?.let { Details(listOf(stringResource(R.string.insights_resolver) to it)) }
    }
}

@Composable
private fun PortsSection(
    device: Device,
    forwards: List<PortForward>?,
    hosts: List<NetworkHost>,
    onNavigate: (Route) -> Unit,
) {
    val known = forwards?.takeIf { device.forwardsCheckedAt != null }
    Section(3, R.string.insights_ports, known?.size?.toString(), stringResource(R.string.insights_ports_body)) {
        when {
            forwards == null -> {
                Skeleton(Modifier.fillMaxWidth().height(SKELETON_HEIGHT))
            }

            known == null -> {
                Line(stringResource(R.string.insights_ports_unknown))
            }

            known.isEmpty() -> {
                Line(
                    stringResource(R.string.insights_ports_none),
                    leading = { Glyph(R.drawable.ic_check_circle, LocalSeverityColors.current.safe.color) },
                )
            }

            else -> {
                Line(pluralStringResource(R.plurals.insights_ports_open, known.size, known.size))
                known.groupBy { it.internalClient }.forEach { (address, ports) ->
                    val host = hosts.filter { it.deviceId == device.id && it.ip == address }.maxByOrNull { it.lastSeenAt }
                    ForwardedDevice(host, ports, onNavigate)
                }
            }
        }
    }
}

/** A device that ports lead to. Descriptions come from the device itself: plain text only. */
@Composable
private fun ForwardedDevice(
    host: NetworkHost?,
    ports: List<PortForward>,
    onNavigate: (Route) -> Unit,
) {
    val resources = LocalResources.current
    val lines =
        ports.map {
            val protocol = it.protocol.uppercase(Locale.ROOT)
            it.description?.let { named -> stringResource(R.string.insights_port_named, it.externalPort, protocol, named) }
                ?: stringResource(R.string.insights_port, it.externalPort, protocol)
        }
    Line(
        host?.let { hostDisplayName(it, resources::getString) } ?: stringResource(R.string.kind_unknown),
        modifier = host?.let { Modifier.clickable { onNavigate(Route.HostDetail(it.deviceId, it.mac)) } } ?: Modifier,
        subtitle = lines.joinToString("\n"),
        leading = { Glyph(kindIcon(host?.kind ?: HostKind.UNKNOWN), MaterialTheme.colorScheme.onSurfaceVariant) },
    )
}

@Composable
private fun ConnectionSection(device: Device) {
    Section(4, R.string.insights_connection, figure = null, body = stringResource(R.string.insights_connection_body)) {
        val address = device.publicIp
        if (address == null) {
            Line(stringResource(R.string.insights_connection_unknown))
        } else {
            Line(stringResource(R.string.insights_public_ip), trailing = { Value(address, technical = true) })
        }
        device.publicCountry?.let {
            val country =
                Locale
                    .Builder()
                    .setRegion(it)
                    .build()
                    .getDisplayCountry(locale())
            Line(stringResource(R.string.insights_country), trailing = { Value(country, technical = false) })
        }
        Line(stringResource(R.string.insights_bandwidth))
    }
}

@Composable
private fun SpeedSection(
    device: Device,
    tests: List<SpeedTest>?,
    onSpeedTest: (Device, Boolean) -> Unit,
) {
    val latest = tests?.firstOrNull()?.let { stringResource(R.string.insights_speed_value, mbpsText(it.downloadMbps)) }
    Section(5, R.string.insights_speed, latest, stringResource(R.string.insights_speed_body)) {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(device.speedTest, role = Role.Switch) { onSpeedTest(device, it) }
                .heightIn(min = Dimens.row),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.insights_speed_toggle), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = device.speedTest, onCheckedChange = null)
        }
        when {
            tests == null -> {
                Skeleton(Modifier.fillMaxWidth().height(SKELETON_HEIGHT))
            }

            tests.isEmpty() -> {
                Line(stringResource(if (device.speedTest) R.string.insights_speed_waiting else R.string.insights_speed_off))
            }

            else -> {
                Sparkline(
                    tests.reversed().map { it.downloadMbps.roundToInt() },
                    MaterialTheme.colorScheme.primary,
                    Modifier.fillMaxWidth().height(SPEED_CHART_HEIGHT),
                )
                tests.forEach {
                    Line(
                        weekdayAndTime(it.testedAt),
                        trailing = { Value(stringResource(R.string.insights_speed_value, mbpsText(it.downloadMbps)), technical = false) },
                    )
                }
            }
        }
    }
}

/** "01 / INTERNET" over a hairline, then the section's figure, what it means, and its lines. */
@Composable
private fun Section(
    number: Int,
    @StringRes title: Int,
    figure: String?,
    body: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        Hairline()
        Text(
            stringResource(R.string.insights_label, number, stringResource(title)).uppercase(locale()),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = LABEL_TRACKING),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics { heading() },
        )
        figure?.let { Text(it, style = MaterialTheme.typography.headlineMedium.tabular()) }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** One line of a section, under a hairline. */
@Composable
private fun Line(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().heightIn(min = Dimens.row).padding(vertical = Dimens.spaceS),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            trailing?.invoke()
        }
    }
}

/** Collapsed by default: technical values are for whoever wants them. */
@Composable
private fun Details(rows: List<Pair<String, String>>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    Column(Modifier.animateContentSize(spec)) {
        Line(
            stringResource(R.string.insights_details),
            modifier =
                Modifier
                    .clickable { open = !open }
                    .semantics {
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
        )
        if (open) rows.forEach { (label, value) -> Line(label, trailing = { Value(value, technical = true) }) }
    }
}

@Composable
private fun Hairline() {
    HorizontalDivider(thickness = Dimens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun Glyph(
    @DrawableRes icon: Int,
    tint: Color,
) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(GLYPH), tint = tint)
}

@Composable
private fun Value(
    text: String,
    technical: Boolean,
) {
    Text(
        text,
        style = if (technical) MaterialTheme.typography.address else MaterialTheme.typography.bodyLarge.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun locale(): Locale = LocalConfiguration.current.locales[0]

@Composable
private fun percentText(percent: Double): String =
    NumberFormat.getPercentInstance(locale()).apply { maximumFractionDigits = 1 }.format(percent / PERCENT)

@Composable
private fun mbpsText(mbps: Double): String = NumberFormat.getNumberInstance(locale()).apply { maximumFractionDigits = 1 }.format(mbps)

/** "Tue 21:00", in the phone's language and time zone. */
@Composable
private fun weekdayAndTime(time: Instant): String =
    DateUtils.formatDateTime(
        LocalContext.current,
        time.toEpochMilli(),
        DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME,
    )
