package no.cyberfish.dekoi

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EventTextTest {
    private fun event(
        kind: String,
        service: String? = null,
    ) = Event(id = 1, deviceId = "d", occurredAt = Instant.EPOCH, kind = kind, service = service)

    @Test
    fun `every event kind the backend can send has its own wording`() {
        val kinds =
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
                "file_access",
                "device_offline",
                "device_online",
            )
        val titles = kinds.map { eventText(event(it)).title }
        assertEquals(kinds.size, titles.toSet().size)
        assertNotEquals(true, titles.contains(R.string.event_other_title))
    }

    @Test
    fun `every decoy the device runs has a plain name`() {
        val decoys = listOf("ftp", "http", "mysql", "rdp", "redis", "smb", "ssh", "telnet", "vnc")
        for (decoy in decoys) {
            assertNotEquals(R.string.service_other, serviceName(decoy), decoy)
        }
    }

    @Test
    fun `decoy events name the decoy and device events do not`() {
        assertEquals(R.string.service_ssh, eventText(event("login_attempt", "ssh")).service)
        assertEquals(R.string.service_file_share, eventText(event("file_access", "smb")).service)
        assertNull(eventText(event("device_offline")).service)
        assertNull(eventText(event("port_scan")).service)
    }

    @Test
    fun `unknown kinds still produce a readable event`() {
        assertEquals(R.string.event_other_title, eventText(event("something_new")).title)
    }
}
