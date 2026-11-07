package no.cyberfish.dekoi

import no.cyberfish.dekoi.shared.ClaimCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    @Test
    fun `setup ticks off as the device reports in`() {
        fun done(device: Device?) = SetupStep.entries.map { device.hasDone(it) }
        assertEquals(listOf(true, false, false, false), done(null))
        val claimed = Device(id = "d", name = "DeKoi 7F3K", createdAt = now)
        assertEquals(listOf(true, false, false, false), done(claimed))
        assertEquals(listOf(true, true, false, false), done(claimed.copy(registeredAt = now)))
        assertEquals(listOf(true, true, true, false), done(claimed.copy(registeredAt = now, lastSeenAt = now)))
        assertEquals(listOf(true, true, true, true), done(claimed.copy(registeredAt = now, lastSeenAt = now, services = listOf("ssh"))))
    }

    @Test
    fun `a DeKoi is set up once it watches the network`() {
        val online = Device(id = "d", name = "DeKoi 7F3K", createdAt = now, registeredAt = now, lastSeenAt = now)
        assertFalse(online.isSetUp)
        assertTrue(online.copy(services = listOf("ssh")).isSetUp)
    }

    @Test
    fun `the code field keeps letters and digits in upper case, up to a full code`() {
        assertEquals("7F3K", typedCode("7f-3k "))
        assertEquals("A".repeat(ClaimCode.LENGTH), typedCode("a".repeat(40)))
        assertEquals("", typedCode("\u0000-- \n"))
    }

    @Test
    fun `a pasted QR payload becomes its code`() {
        val code = "C0000000000000000000000001"
        assertEquals(code, typedCode("${ClaimCode.QR_PREFIX}$code"))
        assertEquals(code, typedCode("c000-0000-0000-0000-0000-0000-01"))
    }

    @Test
    fun `cursor positions survive the spaces between groups`() {
        // "ABCDEFGHIJ" is shown as "ABCD EFGH IJ".
        assertEquals(listOf(0, 1, 4, 6, 9, 12), listOf(0, 1, 4, 5, 8, 10).map(::groupedOffset))
        assertEquals(listOf(0, 4, 4, 5, 8, 10), listOf(0, 4, 5, 6, 10, 12).map { ungroupedOffset(it, 10) })
        for (offset in 0..10) assertEquals(offset, ungroupedOffset(groupedOffset(offset), 10))
    }
}
