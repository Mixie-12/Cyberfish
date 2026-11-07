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
}
