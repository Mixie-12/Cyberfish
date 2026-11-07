package no.cyberfish.dekoi

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MonitorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a device row carries its monitors and images`() {
        val device =
            json.decodeFromString<Device>(
                """
                {"id": "d", "name": "DeKoi 3NFP", "created_at": "2026-11-01T10:00:00+00:00",
                 "monitors": [
                   {"name": "ids", "state": "ok", "detail": {"rules": 48213, "ruleset_date": "2026-11-03"}},
                   {"name": "inventory", "state": "starting", "detail": {"hosts": 12}}
                 ],
                 "images": [{"name": "agent", "version": "0.3.0", "updated_at": "2026-11-07T10:00:00+00:00"}]}
                """,
            )
        val (ids, inventory) = device.monitors
        assertEquals(MonitorKind.IDS, ids.kind)
        assertEquals(MonitorState.OK, ids.state)
        assertEquals(48213, ids.detail.rules)
        assertEquals(LocalDate.of(2026, 11, 3), ids.detail.rulesetDate)
        assertEquals(MonitorState.STARTING, inventory.state)
        assertEquals(12, inventory.detail.hosts)
        assertEquals(Instant.parse("2026-11-07T10:00:00Z"), device.images.single().updatedAt)
    }

    @Test
    fun `a device from older software has no monitors or images`() {
        val device = json.decodeFromString<Device>("""{"id": "d", "name": "DeKoi", "created_at": "2026-11-01T10:00:00+00:00"}""")
        assertEquals(emptyList(), device.monitors)
        assertEquals(emptyList(), device.images)
    }

    @Test
    fun `values from newer software do not pass for working`() {
        assertEquals(MonitorKind.OTHER, MonitorKind.of("radar"))
        assertEquals(MonitorState.UNKNOWN, MonitorState.of("degraded"))
        assertEquals(MonitorState.UNKNOWN, MonitorState.of(null))
        assertEquals(MonitorKind.ARP_WATCH, MonitorKind.of("arp_watch"))
    }

    @Test
    fun `dates in an unexpected format count as not reported`() {
        assertNull(MonitorDetail(rulesetDateText = "3 Nov").rulesetDate)
        assertEquals(LocalDate.of(2026, 11, 3), MonitorDetail(rulesetDateText = "2026-11-03T00:00:00Z").rulesetDate)
        assertNull(ImageVersion("agent", "0.3.0", "2026-11-07 10:00").updatedAt)
    }
}
