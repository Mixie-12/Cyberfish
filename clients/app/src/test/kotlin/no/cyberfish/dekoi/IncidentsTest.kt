package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IncidentsTest {
    private val start = Instant.parse("2026-11-07T15:13:00Z")
    private val phone = "3c:22:fb:00:00:01"
    private val laptop = "3c:22:fb:00:00:02"
    private var nextId = 1L

    private fun event(
        minutes: Long,
        kind: String = "connection",
        mac: String? = phone,
        ip: String? = "192.168.1.23",
        device: String = "d",
        severity: Severity = Severity.WARNING,
        count: Int = 1,
        seen: Boolean = false,
        service: String? = null,
    ) = Event(
        id = nextId++,
        deviceId = device,
        occurredAt = start.plus(Duration.ofMinutes(minutes)),
        kind = kind,
        service = service,
        srcIp = ip,
        srcMac = mac,
        count = count,
        severityName = severity.name.lowercase(),
        seenAt = if (seen) start else null,
    )

    // Newest first, as the backend returns them.
    private fun group(vararg events: Event) = incidents(events.sortedByDescending { it.occurredAt })

    @Test
    fun `events from one source, each within 30 minutes of the one before, are one incident`() {
        val incidents = group(event(0), event(25), event(50), event(81))
        assertEquals(listOf(1, 3), incidents.map { it.events.size })
        assertEquals(start.plus(Duration.ofMinutes(50)), incidents[1].end)
        assertEquals(start, incidents[1].start)
    }

    @Test
    fun `different sources and different DeKois are different incidents`() {
        val incidents = group(event(0), event(1, mac = laptop), event(2, device = "other"))
        assertEquals(3, incidents.size)
    }

    @Test
    fun `without a hardware address the address identifies the source`() {
        val incidents = group(event(0, mac = null), event(1, mac = null), event(2, mac = null, ip = "192.168.1.99"))
        assertEquals(listOf(1, 2), incidents.map { it.events.size })
    }

    @Test
    fun `DeKoi's own status is its own source, apart from devices`() {
        val offline = event(0, kind = "device_offline", mac = null, ip = null)
        val online = event(10, kind = "device_online", mac = null, ip = null, severity = Severity.INFO)
        val unattributed = event(5, kind = "ids_alert", mac = null, ip = null)
        val incidents = group(offline, online, unattributed)
        assertEquals(setOf(setOf("device_offline", "device_online"), setOf("ids_alert")), incidents.map { it.kinds }.toSet())
    }

    @Test
    fun `the most serious event leads, and the newest incident comes first`() {
        val incidents =
            group(
                event(0, kind = "port_scan"),
                event(2, kind = "login_attempt", severity = Severity.CRITICAL),
                event(4, kind = "connection"),
                event(3, mac = laptop),
            )
        assertEquals(listOf(phone, laptop), incidents.map { it.mac })
        assertEquals("login_attempt", incidents[0].lead.kind)
        assertEquals(Severity.CRITICAL, incidents[0].severity)
    }

    @Test
    fun `an incident is seen only when all its events are`() {
        val unseen = event(1)
        val incident = group(event(0, seen = true), unseen).single()
        assertFalse(incident.seen)
        assertEquals(listOf(unseen.id), incident.unseenIds)
        assertTrue(group(event(0, seen = true)).single().seen)
    }

    @Test
    fun `the filter's scope picks events, unread and severity pick incidents`() {
        val events =
            listOf(
                event(0, service = "ssh"),
                event(1, service = "ftp"),
                event(2, mac = laptop, severity = Severity.CRITICAL, seen = true),
                event(60 * 24, mac = laptop, severity = Severity.INFO),
            ).sortedByDescending { it.occurredAt }
        val zone = ZoneOffset.UTC

        fun sizes(filter: AlertFilter) = timeline(events, filter, zone).values.flatten().map { it.events.size }
        assertEquals(listOf(1, 1, 2), sizes(AlertFilter()))
        assertEquals(listOf(1), sizes(AlertFilter(service = "ssh")))
        assertEquals(listOf(1, 1), sizes(AlertFilter(mac = laptop)))
        assertEquals(listOf(1, 2), sizes(AlertFilter(unread = true)))
        assertEquals(listOf(1), sizes(AlertFilter(severity = Severity.CRITICAL)))
        assertEquals(listOf(1, 2), sizes(AlertFilter(day = LocalDate.of(2026, 11, 7))))
    }

    @Test
    fun `incidents are listed under the day they ended, newest day first`() {
        val events = listOf(event(0), event(60 * 9), event(60 * 9 + 20)).sortedByDescending { it.occurredAt }
        val days = timeline(events, AlertFilter(), ZoneOffset.ofHours(1))
        assertEquals(listOf(LocalDate.of(2026, 11, 8), LocalDate.of(2026, 11, 7)), days.keys.toList())
        assertEquals(listOf(2), days.getValue(LocalDate.of(2026, 11, 8)).map { it.events.size })
    }

    private val strings =
        mapOf(
            R.string.alert_known to "Known device",
            R.string.alert_time_range to "%1\$s–%2\$s",
        )

    private fun plural(
        id: Int,
        count: Int,
    ) = when (id) {
        R.plurals.alert_times -> "$count times"
        R.plurals.alert_openings -> "$count openings"
        else -> error("unexpected plural $id")
    }

    private fun subtitle(
        incident: Incident,
        known: Boolean = false,
    ): String {
        val clock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC)
        return incidentSubtitle(incident, known, clock::format, strings::getValue, ::plural)
    }

    @Test
    fun `the subtitle counts events and spans their times`() {
        assertEquals("14 times · 15:13–15:19", subtitle(group(*Array(14) { event(it / 2L) }).single()))
    }

    @Test
    fun `a single event says how much it stands for and when`() {
        assertEquals("60 times · 15:13", subtitle(group(event(0, count = 60)).single()))
        assertEquals("37 openings · 15:13", subtitle(group(event(0, kind = "port_scan", count = 37)).single()))
        assertEquals("15:13", subtitle(group(event(0)).single()))
    }

    @Test
    fun `a known device says so first`() {
        assertEquals("Known device · 2 times · 15:13–15:14", subtitle(group(event(0), event(1)).single(), known = true))
    }

    @Test
    fun `the badge counts unseen serious incidents from devices the owner does not know`() {
        val events =
            listOf(
                event(0, severity = Severity.CRITICAL),
                event(1, severity = Severity.WARNING),
                event(5, mac = laptop, severity = Severity.CRITICAL),
                event(6, mac = "3c:22:fb:00:00:03", severity = Severity.INFO),
                event(7, mac = "3c:22:fb:00:00:04", severity = Severity.WARNING, seen = true),
                event(120, severity = Severity.WARNING),
            ).sortedByDescending { it.occurredAt }
        assertEquals(2, alertBadgeCount(events, setOf(laptop)))
    }
}
