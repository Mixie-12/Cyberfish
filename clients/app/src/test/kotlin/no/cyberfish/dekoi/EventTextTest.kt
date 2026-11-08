package no.cyberfish.dekoi

import kotlinx.serialization.json.Json
import no.cyberfish.dekoi.ui.learn.learnTopicFor
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTextTest {
    private fun event(
        kind: String,
        service: String? = null,
        count: Int = 1,
        detail: EventDetail = EventDetail(),
    ) = Event(id = 1, deviceId = "d", occurredAt = Instant.EPOCH, kind = kind, service = service, count = count, detail = detail)

    // Titles about another device: it is the subject.
    private val deviceKinds =
        listOf(
            "login_attempt",
            "connection",
            "port_scan",
            "host_scan",
            "arp_spoof",
            "rogue_dhcp",
            "rogue_router",
            "name_poisoning",
            "ids_alert",
            "new_host",
            "port_forward",
        )

    // About DeKoi itself, a decoy file, the router's answers or a Wi-Fi network nearby.
    private val otherKinds = listOf("device_offline", "device_online", "file_access", "dns_hijack", "evil_twin", "new_access_point")

    private val everyKind = deviceKinds + otherKinds

    @Test
    fun `every event kind has its own title and action, an explanation, steps and a Learn topic`() {
        val texts = everyKind.map { eventText(event(it)) }
        assertEquals(everyKind.size, texts.map { it.title }.toSet().size)
        assertEquals(everyKind.size, texts.map { it.action }.toSet().size)
        assertFalse(texts.any { it.title == R.string.event_other_title || it.explanation == R.string.event_other_why })
        for (kind in everyKind - "device_online") assertTrue(steps(kind, HostKind.UNKNOWN).isNotEmpty(), kind)
        for (kind in everyKind) assertNotNull(learnTopicFor(kind), kind)
    }

    @Test
    fun `titles about another device name it, titles about DeKoi do not`() {
        for (kind in deviceKinds) assertTrue(eventText(event(kind)).aboutSource, kind)
        for (kind in otherKinds + "something_new") assertFalse(eventText(event(kind)).aboutSource, kind)
    }

    @Test
    fun `every decoy the device runs has a plain name`() {
        val decoys = listOf("ftp", "http", "modbus", "mssql", "mysql", "rdp", "redis", "s7", "sap", "smb", "ssh", "telnet", "vnc")
        for (decoy in decoys) {
            assertNotEquals(R.string.service_other, serviceName(decoy), decoy)
        }
    }

    @Test
    fun `unknown kinds still produce a readable event`() {
        assertEquals(R.string.event_other_title, eventText(event("something_new")).title)
    }

    @Test
    fun `a scan counts openings, a network search counts addresses, repeats count times`() {
        assertEquals(Amount(R.plurals.alert_openings, 37), amount(event("port_scan", count = 37)))
        assertEquals(Amount(R.plurals.alert_addresses, 253), amount(event("host_scan", detail = EventDetail(targets = 253))))
        assertEquals(Amount(R.plurals.alert_times, 14), amount(event("login_attempt", count = 14)))
        assertNull(amount(event("login_attempt")))
        assertNull(amount(event("host_scan")))
    }

    @Test
    fun `facts name the decoys and well-known openings in plain words`() {
        val events =
            listOf(
                event("login_attempt", service = "ssh"),
                event("connection", service = "mysql"),
                event("connection", service = "redis"),
                event("port_scan", detail = EventDetail(ports = listOf(22, 80, 443, 445, 12345))),
            )
        assertEquals(
            listOf(
                Fact.Decoys(listOf(R.string.service_ssh, R.string.service_database)),
                Fact.Openings(listOf(R.string.service_ssh, R.string.port_web, R.string.port_file_sharing)),
            ),
            facts(events, emptySet()),
        )
        assertTrue(facts(listOf(event("port_scan", detail = EventDetail(ports = listOf(12345)))), emptySet()).isEmpty())
    }

    @Test
    fun `a device that claims the router's address is called out`() {
        val spoof = event("arp_spoof", detail = EventDetail(claimedIp = "192.168.1.1"))
        assertEquals(listOf(Fact.ClaimedRouter), facts(listOf(spoof), setOf("192.168.1.1")))
        assertTrue(facts(listOf(spoof), setOf("192.168.1.254")).isEmpty())
    }

    @Test
    fun `a sign-in with a name from the bait files says the bait was read`() {
        val detail = Json.decodeFromString<EventDetail>("""{"username": "ks-drift", "bait": true}""")
        val bait = eventText(event("login_attempt", service = "smb", detail = detail))
        val ordinary = eventText(event("login_attempt", service = "smb", detail = EventDetail(username = "admin")))
        assertEquals(R.string.event_login_bait_consequence, bait.consequence)
        assertEquals(R.string.event_login_consequence, ordinary.consequence)
        assertEquals(ordinary.copy(consequence = bait.consequence), bait)
        assertFalse(Json.decodeFromString<EventDetail>("""{"username": "admin"}""").bait)
    }
}
