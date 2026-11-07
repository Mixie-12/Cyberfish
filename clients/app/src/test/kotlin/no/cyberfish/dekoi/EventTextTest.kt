package no.cyberfish.dekoi

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventTextTest {
    private fun event(
        kind: String,
        service: String? = null,
        count: Int = 1,
        detail: EventDetail = EventDetail(),
    ) = Event(id = 1, deviceId = "d", occurredAt = Instant.EPOCH, kind = kind, service = service, count = count, detail = detail)

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
        )

    @Test
    fun `every event kind the backend can send has its own title and action`() {
        val kinds = deviceKinds + listOf("device_offline", "device_online")
        val texts = kinds.map { eventText(event(it)) }
        assertEquals(kinds.size, texts.map { it.title }.toSet().size)
        assertEquals(kinds.size, texts.map { it.action }.toSet().size)
        assertFalse(texts.any { it.title == R.string.event_other_title })
    }

    @Test
    fun `titles about another device name it, titles about DeKoi do not`() {
        for (kind in deviceKinds) assertTrue(eventText(event(kind)).aboutSource, kind)
        assertFalse(eventText(event("device_offline")).aboutSource)
        assertFalse(eventText(event("device_online")).aboutSource)
        assertFalse(eventText(event("something_new")).aboutSource)
    }

    @Test
    fun `every decoy the device runs has a plain name`() {
        val decoys = listOf("ftp", "http", "mysql", "rdp", "redis", "ssh", "telnet", "vnc")
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
}
