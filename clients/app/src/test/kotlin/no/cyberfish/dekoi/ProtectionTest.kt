package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtectionTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun device(
        registered: Boolean = true,
        online: Boolean = true,
        seenAgo: Duration? = Duration.ofMinutes(1),
        createdAgo: Duration = Duration.ofDays(1),
    ) = Device(
        id = "d",
        name = "DeKoi 3NFP",
        online = online,
        registeredAt = if (registered) now.minus(createdAgo) else null,
        lastSeenAt = seenAgo?.let { now.minus(it) },
        createdAt = now.minus(createdAgo),
    )

    private fun event(
        kind: String,
        ago: Duration = Duration.ofMinutes(5),
        mac: String? = "3c:22:fb:00:00:01",
    ) = Event(id = 1, deviceId = "d", occurredAt = now.minus(ago), kind = kind, srcMac = mac)

    @Test
    fun `no device asks the owner to add one`() {
        assertEquals(Protection.NO_DEVICE, protection(emptyList(), emptyList(), emptySet(), now))
    }

    @Test
    fun `a healthy device with no activity is protected`() {
        val events = listOf(event("device_online"), event("login_attempt", ago = Duration.ofDays(2)))
        assertEquals(Protection.PROTECTED, protection(listOf(device()), events, emptySet(), now))
    }

    @Test
    fun `recent activity on a decoy needs attention`() {
        for (kind in listOf("login_attempt", "connection", "port_scan", "host_scan", "arp_spoof", "ids_alert", "file_access")) {
            assertEquals(Protection.ATTENTION, protection(listOf(device()), listOf(event(kind)), emptySet(), now), kind)
        }
    }

    @Test
    fun `a new device on the network is information, not an attack`() {
        val joined = event("new_host").copy(severityName = "info")
        assertEquals(Protection.PROTECTED, protection(listOf(device()), listOf(joined), emptySet(), now))
        assertEquals(0, alertBadgeCount(listOf(joined), emptySet()))
    }

    @Test
    fun `activity from a known device does not`() {
        val events = listOf(event("login_attempt"))
        assertEquals(Protection.PROTECTED, protection(listOf(device()), events, setOf("3c:22:fb:00:00:01"), now))
    }

    @Test
    fun `activity from an unidentified source always counts`() {
        val events = listOf(event("port_scan", mac = null))
        assertEquals(Protection.ATTENTION, protection(listOf(device()), events, setOf("3c:22:fb:00:00:01"), now))
    }

    @Test
    fun `silence beyond the offline limit is offline even before the backend notices`() {
        val silent = device(online = true, seenAgo = OFFLINE_AFTER.plusMinutes(1))
        assertEquals(Protection.OFFLINE, protection(listOf(silent), emptyList(), emptySet(), now))
        assertEquals(Protection.OFFLINE, protection(listOf(device(online = false)), emptyList(), emptySet(), now))
    }

    @Test
    fun `a device that has not reported yet is setting up and gets help after a while`() {
        val fresh = device(registered = false, seenAgo = null, createdAgo = Duration.ofMinutes(5))
        val stuck = device(registered = false, seenAgo = null, createdAgo = Duration.ofMinutes(25))
        assertEquals(Protection.SETTING_UP, protection(listOf(fresh), emptyList(), emptySet(), now))
        assertEquals(false, fresh.needsSetupHelp(now))
        assertEquals(true, stuck.needsSetupHelp(now))
    }

    @Test
    fun `attention outranks offline, which outranks setting up`() {
        val devices = listOf(device(online = false), device(registered = false, seenAgo = null))
        assertEquals(Protection.OFFLINE, protection(devices, emptyList(), emptySet(), now))
        assertEquals(Protection.ATTENTION, protection(devices, listOf(event("connection")), emptySet(), now))
    }

    @Test
    fun `the most serious alert sets the colour of attention`() {
        val events =
            listOf(Severity.INFO, Severity.CRITICAL, Severity.WARNING).map {
                event("connection").copy(severityName = it.name.lowercase())
            }
        assertEquals(Severity.CRITICAL, highestSeverity(events))
        assertNull(highestSeverity(emptyList()))
    }
}
