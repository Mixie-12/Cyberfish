package no.cyberfish.dekoi

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// What Home and Your DeKoi count and pick from the owner's data. Plain
// functions, so the screens only lay the results out.

/** Longest DeKoi name the backend accepts (devices.name). */
const val MAX_DEKOI_NAME = 40

/** How a DeKoi is doing, the most worrying first. */
enum class DekoiStatus { OFFLINE, SETTING_UP, ONLINE }

fun Device.status(now: Instant): DekoiStatus =
    when {
        isSettingUp -> DekoiStatus.SETTING_UP
        isOnline(now) -> DekoiStatus.ONLINE
        else -> DekoiStatus.OFFLINE
    }

/**
 * The DeKoi Home speaks for: the first one that is offline, else setting
 * up, else one with a check that is not on, else the first one.
 */
fun dekoiToShow(
    devices: List<Device>,
    now: Instant,
): Device? = devices.minWithOrNull(compareBy<Device> { it.status(now) }.thenBy { it.checksOn == it.monitors.size })

/** Monitors that report they are working. */
val Device.checksOn: Int get() = monitors.count { it.state == MonitorState.OK }

/**
 * "What DeKoi is watching", in the same order on every DeKoi. Software from
 * before ADR 0013 reports only the decoy services it runs, so that is all
 * the list can say for it.
 */
val Device.watchList: List<Monitor>
    get() =
        when {
            monitors.isNotEmpty() -> monitors.sortedBy { it.kind }
            services.isNotEmpty() -> listOf(Monitor(MonitorKind.DECOYS.name.lowercase(), MonitorState.OK.name.lowercase()))
            else -> emptyList()
        }

/** When the DeKoi's software last changed, from its images; null when it does not report them. */
val Device.softwareUpdatedAt: Instant? get() = images.mapNotNull { it.updatedAt }.maxOrNull()

/** Decoy services on DeKois that are online now. */
fun runningDecoys(
    devices: List<Device>,
    now: Instant,
): Int = devices.filter { it.status(now) == DekoiStatus.ONLINE }.sumOf { it.services.size }

/**
 * The alerts under "Needs attention": critical and warning intrusions that
 * still matter, one per source (its most serious, then newest), the most
 * serious first. The steps to take are about the source, so a second alert
 * from the same one would repeat them.
 */
fun needsAttention(
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
    limit: Int,
): List<Event> =
    recentIntrusions(events, trustedMacs, now)
        .filter { it.severity != Severity.INFO }
        .sortedWith(compareBy<Event> { it.severity }.thenByDescending { it.occurredAt })
        .distinctBy { it.srcMac ?: it.srcIp ?: it.id }
        .take(limit)

/**
 * How many times something touched [service] on DeKoi [deviceId] on each of
 * the last [days] days, oldest first. An event that repeated counts each time.
 */
fun touchesPerDay(
    events: List<Event>,
    deviceId: String,
    service: String,
    today: LocalDate,
    zone: ZoneId,
    days: Int,
): List<Int> {
    val touches = events.filter { it.deviceId == deviceId && it.service == service && it.isIntrusion }
    return eventsPerDay(touches, today, zone, days).map { day -> day.sumOf { it.count } }
}

/** What the name field keeps of [typed]: no control characters, at most [MAX_DEKOI_NAME], no half characters. */
fun cleanName(typed: String): String {
    val name = typed.filterNot(Char::isISOControl).take(MAX_DEKOI_NAME)
    return if (name.lastOrNull()?.isHighSurrogate() == true) name.dropLast(1) else name
}

/** The name to save for [typed], or null when it is empty or unchanged. */
fun nameToSave(
    typed: String,
    current: String,
): String? = cleanName(typed).trim().takeIf { it.isNotEmpty() && it != current }

/**
 * A way into the decoy that the owner can try from a computer on the same
 * network (ADR 0018): the decoy [service] that must be running for it, and
 * what goes before the DeKoi's address to open it.
 */
enum class DecoyEntrance(
    val service: String,
    val prefix: String,
) {
    LOGIN_PAGE("http", "http://"),
    WINDOWS_SHARE("smb", "\\\\"),
    MAC_SHARE("smb", "smb://"),
}

/**
 * The ways into this DeKoi's decoy and the address to open for each: only
 * while it is online at a known address, and only through decoys it runs.
 */
fun Device.decoyEntrances(now: Instant): Map<DecoyEntrance, String> {
    val address = localIp?.takeIf { status(now) == DekoiStatus.ONLINE } ?: return emptyMap()
    return DecoyEntrance.entries.filter { it.service in services }.associateWith { it.prefix + address }
}
