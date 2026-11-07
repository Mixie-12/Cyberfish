package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The events of each of the last [days] calendar days in [zone], oldest first; the last list is [today]. */
fun eventsPerDay(
    events: List<Event>,
    today: LocalDate,
    zone: ZoneId,
    days: Int,
): List<List<Event>> {
    val byDay = events.groupBy { it.occurredAt.atZone(zone).toLocalDate() }
    return (days - 1 downTo 0).map { byDay[today.minusDays(it.toLong())].orEmpty() }
}

/** How a DeKoi spent one hour. */
enum class Uptime { ONLINE, OFFLINE, UNKNOWN }

/**
 * One entry per hour for the last [hours] hours, oldest first, from the
 * device_offline and device_online events the backend writes. An hour with
 * any time offline counts as offline; hours before the device first
 * registered are unknown.
 */
fun hourlyUptime(
    device: Device,
    events: List<Event>,
    now: Instant,
    hours: Int,
): List<Uptime> {
    val outages = outages(events.filter { it.deviceId == device.id }, now)
    val registered = device.registeredAt
    return (hours downTo 1).map { ago ->
        val start = now.minus(Duration.ofHours(ago.toLong()))
        val end = start.plus(Duration.ofHours(1))
        when {
            registered == null || end <= registered -> Uptime.UNKNOWN
            outages.any { it.start < end && it.endExclusive > start } -> Uptime.OFFLINE
            else -> Uptime.ONLINE
        }
    }
}

// From each device_offline to the next device_online, or to now while
// still offline. An outage that began before the oldest loaded event is
// not known and counts as online.
private fun outages(
    events: List<Event>,
    now: Instant,
): List<OpenEndRange<Instant>> {
    val outages = mutableListOf<OpenEndRange<Instant>>()
    var down: Instant? = null
    for (event in events.sortedBy { it.occurredAt }) {
        when (event.kind) {
            "device_offline" -> {
                down = down ?: event.occurredAt
            }

            "device_online" -> {
                down?.let { outages += it..<event.occurredAt }
                down = null
            }
        }
    }
    down?.let { outages += it..<now }
    return outages
}
