package no.cyberfish.dekoi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VocabularyTest {
    @Test
    fun `backend values map to their enum`() {
        assertEquals(Severity.CRITICAL, Severity.of("critical"))
        assertEquals(HostKind.ACCESS_POINT, HostKind.of("access_point"))
        assertEquals(HostOs.FIRE_OS, HostOs.of("fire_os"))
    }

    @Test
    fun `values from a newer backend do not break the app`() {
        assertEquals(Severity.WARNING, Severity.of("catastrophic"))
        assertEquals(HostKind.UNKNOWN, HostKind.of("hologram"))
        assertEquals(HostOs.OTHER, HostOs.of("plan9"))
    }

    @Test
    fun `missing values have safe defaults`() {
        assertEquals(Severity.WARNING, Severity.of(null))
        assertEquals(HostKind.UNKNOWN, HostKind.of(null))
        assertNull(HostOs.of(null))
    }
}
