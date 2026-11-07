package no.cyberfish.dekoi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostsTest {
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
}
