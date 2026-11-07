package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InsightsTest {
    private val now = Instant.parse("2026-11-07T12:30:00Z")
    private val thisHour = Instant.parse("2026-11-07T12:00:00Z")

    private fun sample(
        hoursAgo: Long,
        target: Probe = Probe.INTERNET,
        medianMs: Double? = 20.0,
        lost: Int = 0,
        outageMinutes: Int = 0,
    ) = HealthSample(
        deviceId = "d",
        hour = thisHour.minus(Duration.ofHours(hoursAgo)),
        target = target.wire,
        probes = 60,
        lost = lost,
        medianMs = medianMs,
        worstMs = medianMs,
        outageMinutes = outageMinutes,
    )

    @Test
    fun `a probe is summed up by its typical latency and the share lost`() {
        val samples =
            listOf(
                sample(3, medianMs = 30.0, lost = 6),
                sample(2, medianMs = 10.4),
                sample(1, medianMs = 20.0),
                sample(1, Probe.GATEWAY, medianMs = 1.0),
            )
        val summary = probeSummary(samples, Probe.INTERNET)
        assertEquals(listOf(30, 10, 20), summary.medians, "oldest first")
        assertEquals(20, summary.typicalMs)
        assertEquals(6 * 100.0 / 180, summary.lostPercent)
        assertNull(probeSummary(samples, Probe.RESOLVER).lostPercent, "not measured")
        val silent = probeSummary(listOf(sample(1, medianMs = null, lost = 60)), Probe.INTERNET)
        assertNull(silent.typicalMs)
        assertEquals(100.0, silent.lostPercent)
    }

    @Test
    fun `the uptime strip covers finished hours and leaves unmeasured ones unknown`() {
        val hours = internetUptime(listOf(sample(3), sample(2, outageMinutes = 4), sample(0)), now, 4)
        assertEquals(listOf(Uptime.UNKNOWN, Uptime.ONLINE, Uptime.OFFLINE, Uptime.UNKNOWN), hours)
        assertEquals(50.0, uptimePercent(hours))
        assertNull(uptimePercent(listOf(Uptime.UNKNOWN)))
    }

    @Test
    fun `outages in consecutive hours are one, newest first, and say when the router was down`() {
        val found =
            outages(
                listOf(
                    sample(9, outageMinutes = 2),
                    sample(5, outageMinutes = 30),
                    sample(4, outageMinutes = 60),
                    sample(3, outageMinutes = 5),
                    sample(4, Probe.GATEWAY, outageMinutes = 60),
                    sample(1, outageMinutes = 0, lost = 1),
                ),
            )
        assertEquals(2, found.size)
        val (latest, earlier) = found
        assertEquals(thisHour.minus(Duration.ofHours(5)), latest.start)
        assertEquals(thisHour.minus(Duration.ofHours(2)), latest.end)
        assertEquals(95, latest.minutes)
        assertEquals(true, latest.atHome)
        assertEquals(Outage(thisHour.minus(Duration.ofHours(9)), thisHour.minus(Duration.ofHours(8)), 2, false), earlier)
    }

    @Test
    fun `dns check states map from the backend and unknown ones are not guessed`() {
        assertEquals(DnsCheck.MISMATCH, DnsCheck.of("mismatch"))
        assertNull(DnsCheck.of(null))
        assertNull(DnsCheck.of("sideways"))
    }
}
