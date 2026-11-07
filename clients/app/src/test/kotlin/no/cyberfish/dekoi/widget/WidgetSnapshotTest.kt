package no.cyberfish.dekoi.widget

import kotlinx.serialization.json.Json
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.EventDetail
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.ui.OwnerData
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WidgetSnapshotTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val knownMac = "3c:22:fb:00:00:02"

    private val device =
        Device(
            id = "d",
            name = "DeKoi 3NFP",
            online = true,
            registeredAt = now.minus(Duration.ofDays(1)),
            lastSeenAt = now.minus(Duration.ofMinutes(1)),
            createdAt = now.minus(Duration.ofDays(1)),
        )

    private val knownHost =
        NetworkHost(deviceId = "d", mac = knownMac, ip = "192.168.1.20", trusted = true, firstSeenAt = now, lastSeenAt = now)

    // Severities as the backend derives them (private.event_severity).
    private val severities =
        mapOf(
            "login_attempt" to Severity.CRITICAL,
            "port_scan" to Severity.WARNING,
            "connection" to Severity.WARNING,
            "device_offline" to Severity.WARNING,
            "device_online" to Severity.INFO,
        )

    private fun event(
        id: Long,
        kind: String,
        ago: Duration,
        mac: String = "3c:22:fb:00:00:01",
    ) = Event(
        id = id,
        deviceId = "d",
        occurredAt = now.minus(ago),
        kind = kind,
        srcMac = mac,
        severityName = severities.getValue(kind).name.lowercase(),
    )

    private fun data(vararg events: Event) =
        OwnerData(devices = listOf(device), events = events.toList(), hosts = listOf(knownHost), updatedAt = now)

    @Test
    fun `nothing is shown before the data has loaded`() {
        assertNull(widgetSnapshot(OwnerData()))
        assertNull(widgetSnapshot(OwnerData(devices = listOf(device))))
    }

    @Test
    fun `a quiet network is protected with no alert to open`() {
        val snapshot = widgetSnapshot(data(event(1, "device_online", Duration.ofMinutes(5))))
        assertEquals(WidgetSnapshot(Protection.PROTECTED, null, 0, null, null, now), snapshot)
    }

    @Test
    fun `the badge alerts give the count and the newest gives the title and the tap`() {
        val snapshot =
            widgetSnapshot(
                data(
                    event(1, "login_attempt", Duration.ofHours(3)),
                    event(2, "port_scan", Duration.ofMinutes(20)),
                    event(3, "device_online", Duration.ofMinutes(10)),
                    event(4, "connection", Duration.ofMinutes(5), mac = knownMac),
                    event(5, "login_attempt", Duration.ofHours(30)),
                ),
            )
        assertEquals(WidgetSnapshot(Protection.ATTENTION, Severity.CRITICAL, 2, R.string.event_scan_title, 2, now), snapshot)
    }

    @Test
    fun `the title is the app's own wording whatever the event carries`() {
        val hostile =
            event(1, "login_attempt", Duration.ofMinutes(5)).copy(
                service = "ssh",
                srcIp = "192.168.1.66",
                detail = EventDetail(username = "admin\u0007; rm -rf /", command = "\u001b[2J"),
            )
        assertEquals(R.string.event_login_title, widgetSnapshot(data(hostile))?.latestTitle)
    }

    @Test
    fun `an offline device that raised an alert is offline with that alert to open`() {
        val offline = device.copy(online = false)
        val events = listOf(event(7, "device_offline", Duration.ofMinutes(40)))
        val snapshot = widgetSnapshot(OwnerData(devices = listOf(offline), events = events, updatedAt = now))
        assertEquals(WidgetSnapshot(Protection.OFFLINE, null, 1, R.string.event_offline_title, 7, now), snapshot)
    }

    @Test
    fun `a snapshot survives storage unchanged`() {
        val snapshot = WidgetSnapshot(Protection.ATTENTION, Severity.WARNING, 3, R.string.event_scan_title, 42, now)
        assertEquals(snapshot, Json.decodeFromString<WidgetSnapshot>(Json.encodeToString(snapshot)))
    }
}
