package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    private val now = Instant.parse("2026-11-07T12:30:00Z")

    private fun event(
        kind: String,
        ago: Duration,
        device: String = "d",
    ) = Event(id = 1, deviceId = device, occurredAt = now.minus(ago), kind = kind)

    @Test
    fun `events fall on their local calendar day, oldest day first`() {
        val events =
            listOf(
                event("connection", Duration.ofHours(1)),
                event("connection", Duration.ofHours(13)),
                event("connection", Duration.ofDays(9)),
            )
        val days = eventsPerDay(events, LocalDate.of(2026, 11, 7), ZoneOffset.UTC, 3)
        assertEquals(listOf(0, 1, 1), days.map { it.size })
    }

    @Test
    fun `an hour with any time offline is offline, and hours before registration are unknown`() {
        val device =
            Device(
                id = "d",
                name = "DeKoi",
                online = true,
                registeredAt = now.minus(Duration.ofHours(5)),
                createdAt = now.minus(Duration.ofDays(1)),
            )
        val events =
            listOf(
                event("device_offline", Duration.ofMinutes(150)),
                event("device_online", Duration.ofMinutes(140)),
                event("device_offline", Duration.ofMinutes(10), device = "other"),
            )
        assertEquals(
            listOf(Uptime.UNKNOWN, Uptime.ONLINE, Uptime.ONLINE, Uptime.OFFLINE, Uptime.ONLINE, Uptime.ONLINE),
            hourlyUptime(device, events, now, 6),
        )
    }

    @Test
    fun `a DeKoi that is still offline is offline up to now`() {
        val device =
            Device(id = "d", name = "DeKoi", registeredAt = now.minus(Duration.ofDays(1)), createdAt = now.minus(Duration.ofDays(1)))
        val events = listOf(event("device_offline", Duration.ofMinutes(90)))
        assertEquals(listOf(Uptime.ONLINE, Uptime.OFFLINE, Uptime.OFFLINE), hourlyUptime(device, events, now, 3))
    }
}
