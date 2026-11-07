package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What one DeKoi measured about its network (ADR 0015), as the Insights screen shows it. */
data class NetworkInsights(
    val deviceId: String,
    val samples: List<HealthSample>,
    val forwards: List<PortForward>,
    val speedTests: List<SpeedTest>,
)

/** The three things a DeKoi probes every minute; [wire] is health_samples.target. */
enum class Probe(
    val wire: String,
) {
    GATEWAY("gateway"),
    INTERNET("internet"),
    RESOLVER("resolver"),
}

/** The latest DNS check (devices.dns_check); null means not checked yet. */
enum class DnsCheck {
    OK,
    MISMATCH,
    UNAVAILABLE,
    ;

    companion object {
        fun of(name: String?): DnsCheck? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/** A week of one probe: hourly median latencies oldest first, the typical latency and the share of probes lost. */
data class ProbeSummary(
    val medians: List<Int>,
    val typicalMs: Int?,
    val lostPercent: Double?,
)

/** Hours in a row in which the internet probe failed at least twice running; [end] is exclusive. */
data class Outage(
    val start: Instant,
    val end: Instant,
    val minutes: Int,
    /** The router did not answer either: the problem was at home, not with the provider. */
    val atHome: Boolean,
)

private val HOUR: Duration = Duration.ofHours(1)
private const val PERCENT = 100.0

fun probeSummary(
    samples: List<HealthSample>,
    probe: Probe,
): ProbeSummary {
    val mine = samples.filter { it.target == probe.wire }.sortedBy { it.hour }
    val medians = mine.mapNotNull { it.medianMs }
    val probes = mine.sumOf { it.probes }
    return ProbeSummary(
        medians = medians.map { Math.round(it).toInt() },
        typicalMs = medians.sorted().getOrNull(medians.size / 2)?.let { Math.round(it).toInt() },
        lostPercent = if (probes == 0) null else mine.sumOf { it.lost } * PERCENT / probes,
    )
}

/** One entry per hour of the last [hours] finished hours, oldest first, from the internet probe. */
fun internetUptime(
    samples: List<HealthSample>,
    now: Instant,
    hours: Int,
): List<Uptime> {
    val byHour = samples.filter { it.target == Probe.INTERNET.wire }.associateBy { it.hour }
    val current = now.truncatedTo(ChronoUnit.HOURS)
    return (hours downTo 1).map { ago ->
        val sample = byHour[current.minus(HOUR.multipliedBy(ago.toLong()))]
        when {
            sample == null -> Uptime.UNKNOWN
            sample.outageMinutes > 0 -> Uptime.OFFLINE
            else -> Uptime.ONLINE
        }
    }
}

/** The share of the known hours the internet worked, in percent; null when no hour is known. */
fun uptimePercent(hours: List<Uptime>): Double? {
    val known = hours.count { it != Uptime.UNKNOWN }
    return if (known == 0) null else hours.count { it == Uptime.ONLINE } * PERCENT / known
}

/** Internet outages, newest first: consecutive hours with outage minutes are one outage. */
fun outages(samples: List<HealthSample>): List<Outage> {
    val routerDown = samples.filter { it.target == Probe.GATEWAY.wire && it.outageMinutes > 0 }.map { it.hour }.toSet()
    val found = mutableListOf<Outage>()
    for (sample in samples.filter { it.target == Probe.INTERNET.wire && it.outageMinutes > 0 }.sortedBy { it.hour }) {
        val atHome = sample.hour in routerDown
        val last = found.lastOrNull()
        if (last?.end == sample.hour) {
            found[found.lastIndex] =
                last.copy(end = sample.hour.plus(HOUR), minutes = last.minutes + sample.outageMinutes, atHome = last.atHome || atHome)
        } else {
            found += Outage(sample.hour, sample.hour.plus(HOUR), sample.outageMinutes, atHome)
        }
    }
    return found.reversed()
}
