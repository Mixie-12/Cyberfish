package no.cyberfish.dekoi.ui.network

import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.isNew
import no.cyberfish.dekoi.isOnline
import java.time.Instant

/**
 * One host as the Network screens show it, with its words looked up once.
 * [name] may come from the host itself: plain text only.
 */
data class HostEntry(
    val host: NetworkHost,
    val name: String,
    val kindName: String,
    /** The most serious alert it caused in the last 24 hours, or null. */
    val attention: Severity?,
    val new: Boolean,
    val online: Boolean,
) {
    val section: HostSection
        get() =
            when {
                attention != null -> HostSection.ATTENTION
                new -> HostSection.NEW
                online -> HostSection.ONLINE
                else -> HostSection.NOT_SEEN
            }
}

fun hostEntries(
    hosts: List<NetworkHost>,
    attention: Map<String, Severity>,
    now: Instant,
    name: (NetworkHost) -> String,
    kindName: (HostKind) -> String,
): List<HostEntry> =
    hosts.map { host ->
        HostEntry(host, name(host), kindName(host.kind), attention[host.mac], host.isNew(now), host.isOnline(now))
    }

/** The device list's status sections, in the order they are shown. */
enum class HostSection { ATTENTION, NEW, ONLINE, NOT_SEEN }

/** A heading in the device list, or a cluster on the map. A room of null means hosts without one. */
sealed interface Group {
    data class Status(
        val section: HostSection,
    ) : Group

    data class Kind(
        val kind: HostKind,
    ) : Group

    data class Room(
        val name: String?,
    ) : Group
}

enum class HostSort { NAME, LAST_SEEN, KIND }

// The hardware address breaks ties, so equal names keep a stable order.
private val nameOrder = compareBy<HostEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.host.mac }

/** Kinds in their declared order (network gear first), then by name. */
val kindOrder: Comparator<HostEntry> = compareBy<HostEntry> { it.host.kind }.then(nameOrder)

/** Rooms in alphabetical order, hosts without a room last. */
val roomOrder: Comparator<HostEntry> = compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.host.room }

private fun HostSort.comparator(): Comparator<HostEntry> =
    when (this) {
        HostSort.NAME -> nameOrder
        HostSort.LAST_SEEN -> compareByDescending<HostEntry> { it.host.lastSeenAt }.then(nameOrder)
        HostSort.KIND -> kindOrder
    }

/** Whether a name, the kind, maker, model, room or an address contains [query], ignoring case. */
fun HostEntry.matches(query: String): Boolean {
    val words = query.trim()
    return listOfNotNull(name, kindName, host.vendor, host.model, host.hostname, host.room, host.ip, host.mac).any {
        it.contains(words, ignoreCase = true)
    }
}

/**
 * The entries that match [query], sorted by [sort] within their groups:
 * by room when [rooms] is true, else by status. Empty groups are left out.
 */
fun sections(
    entries: List<HostEntry>,
    query: String,
    sort: HostSort,
    rooms: Boolean,
): List<Pair<Group, List<HostEntry>>> {
    val found = entries.filter { it.matches(query) }
    if (rooms) return found.sortedWith(roomOrder.then(sort.comparator())).groupBy { Group.Room(it.host.room) }.toList()
    val bySection = found.sortedWith(sort.comparator()).groupBy { it.section }
    return HostSection.entries.mapNotNull { section -> bySection[section]?.let { Group.Status(section) to it } }
}
