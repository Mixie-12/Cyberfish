package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostsTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun host(firstSeenAgo: Duration) =
        NetworkHost(deviceId = "d", mac = "aa", ip = "192.168.1.2", firstSeenAt = now.minus(firstSeenAgo), lastSeenAt = now)

    @Test
    fun `a host is new for a week after DeKoi first sees it`() {
        assertEquals(true, host(Duration.ofDays(6)).isNew(now))
        assertEquals(false, host(Duration.ofDays(7)).isNew(now))
    }

    @Test
    fun `owner text drops control characters and stops at the limit`() {
        assertEquals("Living room", ownerText("Living\u0000 room\n", ROOM_MAX))
        assertEquals("a".repeat(LABEL_MAX), ownerText("a".repeat(LABEL_MAX + 5), LABEL_MAX))
    }

    @Test
    fun `the limit counts characters, not halves of one`() {
        // U+20000, a CJK character outside the Basic Multilingual Plane: two UTF-16 units.
        val wide = "𠀀"
        assertEquals(wide.repeat(3), ownerText(wide.repeat(5), 3))
        assertEquals("ab$wide", ownerText("ab$wide$wide", 3))
    }

    @Test
    fun `saving trims, and an empty name clears it`() {
        assertEquals("Kitchen", savedOwnerText("  Kitchen ", ROOM_MAX))
        assertNull(savedOwnerText("   ", ROOM_MAX))
    }

    @Test
    fun `suggested rooms are those in use, then common ones, each once`() {
        assertEquals(
            listOf("kitchen", "Office", "Living room", "Hallway"),
            suggestedRooms(listOf("Office", "kitchen", "Kitchen"), listOf("Living room", "Kitchen", "Hallway")),
        )
        assertEquals(listOf("Living room"), suggestedRooms(emptyList(), listOf("Living room")))
    }
}
