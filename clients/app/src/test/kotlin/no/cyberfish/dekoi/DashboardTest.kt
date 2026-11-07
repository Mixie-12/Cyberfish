package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DashboardTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun device(
        id: String,
        registered: Boolean = true,
        online: Boolean = true,
        seenAgo: Duration? = Duration.ofMinutes(1),
        services: List<String> = emptyList(),
        ip: String? = null,
    ) = Device(
        id = id,
        name = "DeKoi $id",
        online = online,
        services = services,
        localIp = ip,
        registeredAt = if (registered) now.minus(Duration.ofDays(1)) else null,
        lastSeenAt = seenAgo?.let { now.minus(it) },
        createdAt = now.minus(Duration.ofDays(1)),
    )

    private fun event(
        id: Long,
        kind: String = "connection",
        severity: Severity = Severity.WARNING,
        ago: Duration = Duration.ofMinutes(5),
        mac: String? = "3c:22:fb:00:00:01",
        ip: String? = null,
        service: String? = null,
        count: Int = 1,
        device: String = "d",
    ) = Event(
        id = id,
        deviceId = device,
        occurredAt = now.minus(ago),
        kind = kind,
        service = service,
        srcIp = ip,
        srcMac = mac,
        count = count,
        severityName = severity.name.lowercase(),
    )

    private fun host(firstSeenAgo: Duration) =
        NetworkHost(deviceId = "d", mac = "aa", ip = "192.168.1.2", firstSeenAt = now.minus(firstSeenAgo), lastSeenAt = now)

    @Test
    fun `a host is new for a week after DeKoi first sees it`() {
        assertEquals(true, host(Duration.ofDays(6)).isNew(now))
        assertEquals(false, host(Duration.ofDays(7)).isNew(now))
    }

    @Test
    fun `a DeKoi is setting up until it has registered and checked in, then online or offline`() {
        assertEquals(DekoiStatus.SETTING_UP, device("a", registered = false, seenAgo = null).status(now))
        assertEquals(DekoiStatus.SETTING_UP, device("a", seenAgo = null).status(now))
        assertEquals(DekoiStatus.ONLINE, device("a").status(now))
        assertEquals(DekoiStatus.OFFLINE, device("a", online = false).status(now))
        assertEquals(DekoiStatus.OFFLINE, device("a", seenAgo = OFFLINE_AFTER).status(now))
    }

    @Test
    fun `Home speaks for the DeKoi that is offline, then one setting up, then the first`() {
        val online = device("online")
        val settingUp = device("new", registered = false, seenAgo = null)
        val offline = device("offline", online = false)
        assertEquals(offline, dekoiToShow(listOf(online, settingUp, offline), now))
        assertEquals(settingUp, dekoiToShow(listOf(online, settingUp), now))
        assertEquals(online, dekoiToShow(listOf(online, device("second")), now))
        assertNull(dekoiToShow(emptyList(), now))
    }

    @Test
    fun `among DeKois that are online, Home speaks for one with a protection that is not on`() {
        val allOn = device("all").copy(monitors = listOf(Monitor("ids", "ok"), Monitor("arp_watch", "ok")))
        val oneOff = device("one").copy(monitors = listOf(Monitor("ids", "failed"), Monitor("arp_watch", "ok")))
        assertEquals(oneOff, dekoiToShow(listOf(allOn, oneOff), now))
        assertEquals(1, oneOff.protectionsOn)
    }

    @Test
    fun `the watch list keeps one order, and software counts as updated when its newest image did`() {
        val dekoi =
            device("a").copy(
                monitors = listOf(Monitor("inventory", "ok"), Monitor("hologram", "ok"), Monitor("decoys", "ok")),
                images =
                    listOf(
                        ImageVersion("agent", "0.3.0", "2026-11-07T09:00:00Z"),
                        ImageVersion("ids", "0.3.0", "2026-11-07T10:00:00+00:00"),
                        ImageVersion("canary", "0.3.0", "yesterday"),
                    ),
            )
        assertEquals(listOf(MonitorKind.DECOYS, MonitorKind.INVENTORY, MonitorKind.OTHER), dekoi.watchList.map { it.kind })
        assertEquals(Instant.parse("2026-11-07T10:00:00Z"), dekoi.softwareUpdatedAt)
        assertNull(device("b").softwareUpdatedAt)
    }

    @Test
    fun `older software lists only its running decoys, and nothing without them`() {
        val older = device("a", services = listOf("ssh", "ftp"))
        assertEquals(listOf(MonitorKind.DECOYS to MonitorState.OK), older.watchList.map { it.kind to it.state })
        assertEquals(emptyList(), device("b").watchList)
    }

    @Test
    fun `failing is the most concerning monitor state`() {
        val states = listOf("ok", "starting", "failed", "off", "degraded").map { Monitor("ids", it).state }
        assertEquals(MonitorState.FAILED, states.max())
        assertEquals(MonitorState.UNKNOWN, states.filter { it != MonitorState.FAILED }.max())
    }

    @Test
    fun `only decoys on DeKois that are online are running`() {
        val devices =
            listOf(
                device("a", services = listOf("ssh", "ftp")),
                device("b", online = false, services = listOf("ssh")),
                device("c", seenAgo = null, services = listOf("vnc")),
            )
        assertEquals(2, runningDecoys(devices, now))
    }

    @Test
    fun `needs attention lists recent critical and warning intrusions, most serious first`() {
        val events =
            listOf(
                event(1, mac = "01"),
                event(2, kind = "login_attempt", severity = Severity.CRITICAL, mac = "02"),
                event(3, kind = "ids_alert", severity = Severity.INFO, mac = "03"),
                event(4, kind = "device_offline", mac = null),
                event(5, mac = "05", ago = ATTENTION_WINDOW),
                event(6, mac = "06"),
            )
        assertEquals(listOf(2L, 1L), needsAttention(events, setOf("06"), now, 3).map { it.id })
    }

    @Test
    fun `needs attention shows each source once, by its most serious and newest alert`() {
        val events =
            listOf(
                event(1, mac = "01", ago = Duration.ofMinutes(1)),
                event(2, kind = "login_attempt", severity = Severity.CRITICAL, mac = "01", ago = Duration.ofHours(2)),
                event(3, kind = "login_attempt", severity = Severity.CRITICAL, mac = "01", ago = Duration.ofHours(1)),
                event(4, mac = null, ip = "192.168.1.9", ago = Duration.ofMinutes(2)),
                event(5, mac = null, ip = "192.168.1.9", ago = Duration.ofMinutes(3)),
                event(6, mac = null, ip = null, ago = Duration.ofMinutes(4)),
                event(7, mac = null, ip = null, ago = Duration.ofMinutes(5)),
            )
        assertEquals(listOf(3L, 4L, 6L, 7L), needsAttention(events, emptySet(), now, 10).map { it.id })
        assertEquals(listOf(3L, 4L), needsAttention(events, emptySet(), now, 2).map { it.id })
    }

    @Test
    fun `touches count every repeat on one decoy of one DeKoi, per day`() {
        val events =
            listOf(
                event(1, service = "ssh", count = 3, ago = Duration.ofHours(1)),
                event(2, kind = "login_attempt", service = "ssh", ago = Duration.ofHours(2)),
                event(3, service = "ssh", ago = Duration.ofDays(1)),
                event(4, service = "ftp", ago = Duration.ofHours(1)),
                event(5, service = "ssh", ago = Duration.ofHours(1), device = "other"),
                event(6, kind = "device_online", service = "ssh", ago = Duration.ofHours(1)),
                event(7, service = "ssh", ago = Duration.ofDays(3)),
            )
        assertEquals(listOf(0, 1, 4), touchesPerDay(events, "d", "ssh", LocalDate.of(2026, 11, 7), ZoneOffset.UTC, 3))
    }

    @Test
    fun `setup steps tick off as the DeKoi registers, checks in and starts its decoys`() {
        val linked = device("a", registered = false, seenAgo = null)
        assertEquals(listOf(true, false, false, false), SetupStep.entries.map { linked.hasDone(it) })
        val started = device("a", seenAgo = null)
        assertEquals(listOf(true, true, false, false), SetupStep.entries.map { started.hasDone(it) })
        val watching = device("a", services = listOf("ssh"))
        assertEquals(listOf(true, true, true, true), SetupStep.entries.map { watching.hasDone(it) })
    }

    @Test
    fun `the name field drops control characters, stops at the limit and never splits a character`() {
        assertEquals("Living room", cleanName("Living\n room\u0007"))
        assertEquals("a".repeat(MAX_DEKOI_NAME), cleanName("a".repeat(MAX_DEKOI_NAME + 5)))
        val fish = "\uD83D\uDC1F"
        assertEquals("a".repeat(MAX_DEKOI_NAME - 1), cleanName("a".repeat(MAX_DEKOI_NAME - 1) + fish))
    }

    @Test
    fun `a name is saved trimmed, and only when it is new and not empty`() {
        assertEquals("Hallway", nameToSave("  Hallway ", "DeKoi 3NFP"))
        assertNull(nameToSave("   ", "DeKoi 3NFP"))
        assertNull(nameToSave("DeKoi 3NFP ", "DeKoi 3NFP"))
    }

    @Test
    fun `the decoy is tried at the DeKoi's address, through the decoys it runs`() {
        val both = device("d", services = listOf("http", "smb", "ssh"), ip = "192.168.1.5")
        assertEquals(
            mapOf(
                DecoyEntrance.LOGIN_PAGE to "http://192.168.1.5",
                DecoyEntrance.WINDOWS_SHARE to "\\\\192.168.1.5",
                DecoyEntrance.MAC_SHARE to "smb://192.168.1.5",
            ),
            both.decoyEntrances(now),
        )
        val web = device("d", services = listOf("http", "ssh"), ip = "192.168.1.5")
        assertEquals(listOf(DecoyEntrance.LOGIN_PAGE), web.decoyEntrances(now).keys.toList())
    }

    @Test
    fun `the decoy cannot be tried while the DeKoi is offline or its address is not known`() {
        val offline = device("d", online = false, services = listOf("http", "smb"), ip = "192.168.1.5")
        assertEquals(emptyMap(), offline.decoyEntrances(now))
        val noAddress = device("d", services = listOf("http", "smb"))
        assertEquals(emptyMap(), noAddress.decoyEntrances(now))
    }
}
