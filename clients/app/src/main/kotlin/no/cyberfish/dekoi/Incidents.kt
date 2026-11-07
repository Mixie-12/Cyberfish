package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** An event further than this from the one before it from the same source starts a new incident (ADR 0013). */
val INCIDENT_GAP: Duration = Duration.ofMinutes(30)

/** Written by the backend about DeKoi itself rather than reported about another device. */
val DEKOI_STATUS_KINDS = setOf("device_offline", "device_online")

/** Separates the parts of a one-line summary. */
const val SEPARATOR = " · "

/**
 * Events from one source close together in time, newest first: what the
 * Alerts timeline shows (ADR 0013). Never empty.
 */
data class Incident(
    val events: List<Event>,
) {
    val start: Instant get() = events.last().occurredAt
    val end: Instant get() = events.first().occurredAt

    /** The most serious event, the latest of equals: it names the incident and sets its severity. */
    val lead: Event get() = events.minBy { it.severity }

    val severity: Severity get() = lead.severity

    /** The source's hardware address, when DeKoi knows it; the same for every event of the incident. */
    val mac: String? get() = events.first().srcMac

    val kinds: Set<String> get() = events.mapTo(mutableSetOf()) { it.kind }

    val seen: Boolean get() = events.all { it.seenAt != null }

    val unseenIds: List<Long> get() = events.filter { it.seenAt == null }.map { it.id }
}

/** Who an event came from: DeKoi itself, or a device by hardware address, else by address. */
private data class Source(
    val deviceId: String,
    val dekoi: Boolean,
    val address: String?,
)

private fun Event.source(): Source =
    if (kind in DEKOI_STATUS_KINDS) Source(deviceId, true, null) else Source(deviceId, false, srcMac ?: srcIp)

/**
 * Groups events into incidents, newest first: events from the same DeKoi and
 * the same source, each within [INCIDENT_GAP] of the one before it.
 */
fun incidents(events: List<Event>): List<Incident> {
    val open = mutableMapOf<Source, MutableList<Event>>()
    val groups = mutableListOf<MutableList<Event>>()
    for (event in events.sortedBy { it.occurredAt }) {
        val group = open[event.source()]
        if (group != null && Duration.between(group.last().occurredAt, event.occurredAt) <= INCIDENT_GAP) {
            group += event
            continue
        }
        val started = mutableListOf(event)
        open[event.source()] = started
        groups += started
    }
    return groups.map { Incident(it.reversed()) }.sortedByDescending { it.end }
}

/**
 * What the timeline shows. A cross-link's scope (one source device, decoy
 * service or day) picks events before they are grouped; unread and
 * severity pick incidents.
 */
data class AlertFilter(
    val unread: Boolean = false,
    val severity: Severity? = null,
    val mac: String? = null,
    val service: String? = null,
    val day: LocalDate? = null,
) {
    fun inScope(
        event: Event,
        zone: ZoneId,
    ): Boolean =
        (mac == null || event.srcMac == mac) &&
            (service == null || event.service == service) &&
            (day == null || event.occurredAt.atZone(zone).toLocalDate() == day)

    fun shows(incident: Incident): Boolean = (!unread || !incident.seen) && (severity == null || incident.severity == severity)
}

/** The incidents [filter] lets through, grouped by the day they ended on in [zone], newest first. */
fun timeline(
    events: List<Event>,
    filter: AlertFilter,
    zone: ZoneId,
): Map<LocalDate, List<Incident>> =
    incidents(events.filter { filter.inScope(it, zone) })
        .filter(filter::shows)
        .groupBy { it.end.atZone(zone).toLocalDate() }

/**
 * The line under an incident's title: "14 times · 15:13–15:20", led by
 * "Known device" when the owner knows the source. A single event says how
 * much happened instead, "60 times · 15:20". [clock] formats a time of
 * day; [string] and [plural] look up resources.
 */
fun incidentSubtitle(
    incident: Incident,
    known: Boolean,
    clock: (Instant) -> String,
    string: (Int) -> String,
    plural: (Int, Int) -> String,
): String {
    val size = incident.events.size
    val amount = if (size > 1) plural(R.plurals.alert_times, size) else amount(incident.lead)?.let { plural(it.words, it.count) }
    val start = clock(incident.start)
    val end = clock(incident.end)
    return listOfNotNull(
        string(R.string.alert_known).takeIf { known },
        amount,
        if (start == end) end else string(R.string.alert_time_range).format(start, end),
    ).joinToString(SEPARATOR)
}
