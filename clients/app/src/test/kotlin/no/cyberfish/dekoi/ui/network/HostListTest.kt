package no.cyberfish.dekoi.ui.network

import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.attentionByMac
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private val NOW = Instant.parse("2026-11-07T12:00:00Z")

internal fun host(
    mac: String,
    kind: String? = null,
    ip: String = "192.168.1.10",
    vendor: String? = null,
    hostname: String? = null,
    room: String? = null,
    firstSeen: Duration = Duration.ofDays(30),
    lastSeen: Duration = Duration.ofMinutes(5),
) = NetworkHost(
    deviceId = "d",
    mac = mac,
    ip = ip,
    vendor = vendor,
    firstSeenAt = NOW.minus(firstSeen),
    lastSeenAt = NOW.minus(lastSeen),
    hostname = hostname,
    kindName = kind,
    room = room,
)

internal fun entries(
    hosts: List<NetworkHost>,
    attention: Map<String, Severity> = emptyMap(),
): List<HostEntry> = hostEntries(hosts, attention, NOW, { it.hostname ?: it.mac }, { it.name.lowercase() })

class HostListTest {
    private fun names(sections: List<Pair<Group, List<HostEntry>>>) = sections.map { (group, rows) -> group to rows.map { it.name } }

    private fun found(
        hosts: List<NetworkHost>,
        query: String = "",
        sort: HostSort = HostSort.NAME,
    ) = sections(entries(hosts), query, sort, rooms = false).flatMap { it.second }.map { it.name }

    @Test
    fun `each host goes to the first section that applies`() {
        val hosts =
            listOf(
                host("01", hostname = "new and alerting", firstSeen = Duration.ofDays(1)),
                host("02", hostname = "new", firstSeen = Duration.ofDays(1), lastSeen = Duration.ofDays(1)),
                host("03", hostname = "online"),
                host("04", hostname = "gone", lastSeen = Duration.ofHours(2)),
            )
        val sections = sections(entries(hosts, mapOf("01" to Severity.WARNING)), "", HostSort.NAME, rooms = false)
        assertEquals(
            listOf(
                Group.Status(HostSection.ATTENTION) to listOf("new and alerting"),
                Group.Status(HostSection.NEW) to listOf("new"),
                Group.Status(HostSection.ONLINE) to listOf("online"),
                Group.Status(HostSection.NOT_SEEN) to listOf("gone"),
            ),
            names(sections),
        )
    }

    @Test
    fun `empty sections are left out`() {
        val sections = sections(entries(listOf(host("01", hostname = "tv"))), "", HostSort.NAME, rooms = false)
        assertEquals(listOf(Group.Status(HostSection.ONLINE) to listOf("tv")), names(sections))
    }

    @Test
    fun `by room, rooms are in alphabetical order and hosts without one come last`() {
        val hosts =
            listOf(
                host("01", hostname = "speaker"),
                host("02", hostname = "tv", room = "living room"),
                host("03", hostname = "printer", room = "Office"),
                host("04", hostname = "lamp", room = "living room"),
            )
        assertEquals(
            listOf(
                Group.Room("living room") to listOf("lamp", "tv"),
                Group.Room("Office") to listOf("printer"),
                Group.Room(null) to listOf("speaker"),
            ),
            names(sections(entries(hosts), "", HostSort.NAME, rooms = true)),
        )
    }

    @Test
    fun `sorts by name ignoring case, by last seen newest first, or by kind`() {
        val hosts =
            listOf(
                host("01", kind = "tv", hostname = "beta", lastSeen = Duration.ofMinutes(30)),
                host("02", kind = "phone", hostname = "Alpha", lastSeen = Duration.ofMinutes(20)),
                host("03", kind = "router", hostname = "gamma", lastSeen = Duration.ofMinutes(10)),
            )
        assertEquals(listOf("Alpha", "beta", "gamma"), found(hosts, sort = HostSort.NAME))
        assertEquals(listOf("gamma", "Alpha", "beta"), found(hosts, sort = HostSort.LAST_SEEN))
        assertEquals(listOf("gamma", "Alpha", "beta"), found(hosts, sort = HostSort.KIND))
    }

    @Test
    fun `equal names keep a stable order`() {
        val hosts = listOf(host("02", hostname = "tv"), host("01", hostname = "tv"))
        assertEquals(listOf("01", "02"), sections(entries(hosts), "", HostSort.NAME, rooms = false).single().second.map { it.host.mac })
    }

    @Test
    fun `search looks at the name, kind, maker, room and addresses`() {
        val hosts =
            listOf(
                host("aa:01", kind = "tv", vendor = "LG", hostname = "living room tv", ip = "192.168.1.23"),
                host("aa:02", kind = "phone", hostname = "Kari", ip = "192.168.1.40", room = "Kitchen"),
            )
        assertEquals(listOf("living room tv"), found(hosts, "lg"))
        assertEquals(listOf("living room tv"), found(hosts, " TV "))
        assertEquals(listOf("Kari"), found(hosts, "1.40"))
        assertEquals(listOf("Kari"), found(hosts, "AA:02"))
        assertEquals(listOf("Kari"), found(hosts, "kitchen"))
        assertEquals(listOf("Kari", "living room tv"), found(hosts, ""))
        assertEquals(emptyList(), found(hosts, "printer"))
    }

    @Test
    fun `attention is the most serious recent alert from a host the owner does not know`() {
        fun alert(
            mac: String,
            severity: String,
            ago: Duration = Duration.ofHours(1),
        ) = Event(id = 1, deviceId = "d", occurredAt = NOW.minus(ago), kind = "connection", srcMac = mac, severityName = severity)
        val events =
            listOf(
                alert("01", "warning"),
                alert("01", "critical"),
                alert("02", "critical", ago = Duration.ofHours(25)),
                alert("03", "critical"),
            )
        assertEquals(mapOf("01" to Severity.CRITICAL), attentionByMac(events, setOf("03"), NOW))
    }

    @Test
    fun `entries carry their kind and whether they are new and online`() {
        val entry = entries(listOf(host("01", kind = "tv", firstSeen = Duration.ofDays(8), lastSeen = Duration.ofMinutes(59)))).single()
        assertEquals("tv", entry.kindName)
        assertEquals(HostKind.TV, entry.host.kind)
        assertEquals(false, entry.new)
        assertEquals(true, entry.online)
        assertEquals(false, entries(listOf(host("01", lastSeen = Duration.ofMinutes(61)))).single().online)
    }
}
