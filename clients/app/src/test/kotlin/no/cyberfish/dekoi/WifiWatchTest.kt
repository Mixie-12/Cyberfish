package no.cyberfish.dekoi

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WifiWatchTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun device(
        wifiName: String? = "Kari's Wi-Fi",
        watch: String? = null,
        registeredAt: Instant = Instant.EPOCH,
    ) = Device(id = "d", name = "DeKoi", createdAt = Instant.EPOCH, registeredAt = registeredAt, wifiName = wifiName, wifiWatchName = watch)

    private fun textFor(device: Device) = wifiWatchText(device, now)

    @Test
    fun `a name is stored trimmed and an empty one turns the watch off`() {
        assertEquals("Kari's Wi-Fi", WifiName.of("  Kari's Wi-Fi "))
        assertNull(WifiName.of(""))
        assertNull(WifiName.of("   "))
    }

    @Test
    fun `a name fits in 32 bytes without control characters, as the backend requires`() {
        assertTrue(WifiName.fits("a".repeat(32)))
        assertFalse(WifiName.fits("a".repeat(33)))
        assertTrue(WifiName.fits("æ".repeat(16)))
        assertFalse(WifiName.fits("æ".repeat(17)), "17 characters but 34 bytes")
        assertFalse(WifiName.fits("Home\nNet"))
        assertFalse(WifiName.fits("Home\u0000"))
    }

    @Test
    fun `every state the device reports has its own sentence`() {
        val states = listOf("no_country", "no_radio", "uplink", "learning", "watching")
        val texts = states.map { textFor(device(watch = it)) }
        assertEquals(states.size, texts.toSet().size)
        assertFalse(texts.contains(R.string.wifi_watch_starting))
    }

    @Test
    fun `without a name the owner is asked for one, whatever the device reported`() {
        assertEquals(R.string.wifi_watch_add, textFor(device(wifiName = null, watch = "watching")))
    }

    @Test
    fun `a name the device has not picked up yet, or an unknown state, reads as starting`() {
        assertEquals(R.string.wifi_watch_starting, textFor(device(watch = "no_name")))
        assertEquals(R.string.wifi_watch_starting, textFor(device(watch = "orbiting")))
        assertNull(WifiWatch.of("orbiting"))
    }

    @Test
    fun `no state at all waits for an update, unless the DeKoi has only just started`() {
        assertEquals(R.string.wifi_watch_update, textFor(device(watch = null)))
        assertEquals(R.string.wifi_watch_starting, textFor(device(watch = null, registeredAt = now.minusSeconds(60))))
    }

    private fun heard(
        id: Long,
        kind: String,
        bssid: String,
        hoursAgo: Long,
        deviceId: String = "d",
    ) = Event(
        id = id,
        deviceId = deviceId,
        occurredAt = now.minusSeconds(hoursAgo * 3600),
        kind = kind,
        srcMac = bssid,
        severityName = if (kind == "evil_twin") "critical" else "warning",
    )

    @Test
    fun `Wi-Fi Guard lists each access point it heard once, by its newest report, newest first`() {
        val events =
            listOf(
                heard(1, "new_access_point", "02:00:00:00:00:02", hoursAgo = 30),
                heard(2, "evil_twin", "02:00:00:00:00:66", hoursAgo = 50),
                heard(3, "evil_twin", "02:00:00:00:00:66", hoursAgo = 2),
                heard(4, "evil_twin", "02:00:00:00:00:77", hoursAgo = 1, deviceId = "other"),
                Event(id = 5, deviceId = "d", occurredAt = now, kind = "port_scan", srcMac = "02:00:00:00:00:02"),
            )
        assertEquals(listOf(3L, 1L), heardAccessPoints("d", events).map { it.id })
    }

    @Test
    fun `Wi-Fi Guard leads with a recent copy over a new access point, and with nothing older than a day`() {
        val copy = heard(1, "evil_twin", "02:00:00:00:00:66", hoursAgo = 3)
        val newer = heard(2, "new_access_point", "02:00:00:00:00:02", hoursAgo = 1)
        assertEquals(copy, wifiFinding(listOf(newer, copy), emptySet(), now))
        assertEquals(newer, wifiFinding(listOf(newer), emptySet(), now))
        assertNull(wifiFinding(listOf(heard(3, "evil_twin", "02:00:00:00:00:66", hoursAgo = 25)), emptySet(), now))
        assertNull(wifiFinding(listOf(copy), setOf("02:00:00:00:00:66"), now))
    }

    @Test
    fun `a stronger signal is drawn nearer the DeKoi, and one without a signal at the edge`() {
        assertEquals(0f, signalReach(-30))
        assertEquals(0f, signalReach(-10))
        assertEquals(0.5f, signalReach(-60))
        assertEquals(1f, signalReach(-90))
        assertEquals(1f, signalReach(-100))
        assertEquals(1f, signalReach(null))
    }
}
