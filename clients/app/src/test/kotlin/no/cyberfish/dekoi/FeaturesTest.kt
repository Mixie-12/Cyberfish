package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturesTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")
    private val longAgo = now.minus(Duration.ofDays(30))

    /** Every monitor of ADR 0013 in [state]. */
    private fun allMonitors(state: String = "ok") =
        MonitorKind.entries.filter { it != MonitorKind.OTHER }.map { Monitor(it.name.lowercase(), state) }

    /** Online, set up long ago, on current software unless told otherwise. */
    private fun device(
        monitors: List<Monitor> = allMonitors(),
        services: List<String> = listOf("http", "smb"),
        online: Boolean = true,
        registeredAt: Instant? = longAgo,
        lastSeenAt: Instant? = now.minusSeconds(60),
        wifiName: String? = "Kari's Wi-Fi",
        wifiWatch: String? = "watching",
        publicIp: String? = "203.0.113.7",
        images: List<ImageVersion> = emptyList(),
    ) = Device(
        id = "d",
        name = "DeKoi",
        services = services,
        online = online,
        registeredAt = registeredAt,
        lastSeenAt = lastSeenAt,
        createdAt = longAgo,
        monitors = monitors,
        images = images,
        wifiName = wifiName,
        wifiWatchName = wifiWatch,
        publicIp = publicIp,
    )

    /** What software from before ADR 0013 and 0015 reports: decoys, nothing else. */
    private fun olderDevice() = device(monitors = emptyList(), wifiWatch = null, publicIp = null)

    private fun host(
        firstSeen: Instant = longAgo,
        deviceId: String = "d",
        mac: String = "3c:22:fb:00:00:01",
    ) = NetworkHost(deviceId = deviceId, mac = mac, ip = "192.168.1.20", firstSeenAt = firstSeen, lastSeenAt = now)

    private fun Device.state(
        feature: Feature,
        hosts: List<NetworkHost> = listOf(host()),
        weeklySummary: Boolean? = true,
    ) = featureState(feature, hosts, weeklySummary, now)

    @Test
    fun `on current software that reports every check, every feature is on`() {
        Feature.entries.forEach { assertEquals(FeatureState.ON, device().state(it), it.name) }
    }

    @Test
    fun `software that does not report a feature waits for the next update, never claims it is on`() {
        val older = olderDevice()
        val waiting =
            listOf(
                Feature.IDS,
                Feature.SCANS,
                Feature.ARP_GUARD,
                Feature.ROUTER_GUARD,
                Feature.NAME_TRAP,
                Feature.WIFI_GUARD,
                Feature.INTERNET_HEALTH,
                Feature.OPEN_PORTS,
            )
        waiting.forEach { assertEquals(FeatureState.WAITING_FOR_UPDATE, older.state(it), it.name) }
    }

    @Test
    fun `older software still shows the decoys it runs and the devices it lists`() {
        val older = olderDevice()
        assertEquals(FeatureState.ON, older.state(Feature.DECOY))
        assertEquals(FeatureState.ON, older.state(Feature.NETWORK_MAP))
        assertEquals(FeatureState.WAITING_FOR_UPDATE, older.copy(services = emptyList()).state(Feature.DECOY))
        assertEquals(FeatureState.WAITING_FOR_UPDATE, older.state(Feature.NETWORK_MAP, hosts = emptyList()))
        assertEquals(FeatureState.WAITING_FOR_UPDATE, older.state(Feature.NETWORK_MAP, hosts = listOf(host(deviceId = "other"))))
    }

    @Test
    fun `a monitor the DeKoi does not report waits for the update, even when the others are on`() {
        val noIds = device(monitors = allMonitors().filter { it.kind != MonitorKind.IDS })
        assertEquals(FeatureState.WAITING_FOR_UPDATE, noIds.state(Feature.IDS))
        assertEquals(FeatureState.ON, noIds.state(Feature.ARP_GUARD))
    }

    @Test
    fun `a feature built on two monitors shows the more concerning of them`() {
        val sweepsFailing =
            device(
                monitors =
                    allMonitors().map {
                        if (it.kind ==
                            MonitorKind.ARP_WATCH
                        ) {
                            Monitor("arp_watch", "failed")
                        } else {
                            it
                        }
                    },
            )
        assertEquals(FeatureState.FAILED, sweepsFailing.state(Feature.SCANS))
        assertEquals(FeatureState.FAILED, sweepsFailing.state(Feature.ARP_GUARD))
        assertEquals(FeatureState.ON, sweepsFailing.state(Feature.ROUTER_GUARD))
        val dhcpStarting =
            device(
                monitors =
                    allMonitors().map {
                        if (it.kind ==
                            MonitorKind.DHCP_WATCH
                        ) {
                            Monitor("dhcp_watch", "starting")
                        } else {
                            it
                        }
                    },
            )
        assertEquals(FeatureState.STARTING, dhcpStarting.state(Feature.ROUTER_GUARD))
    }

    @Test
    fun `monitor states map one to one, and a state from newer software is not known`() {
        mapOf(
            "starting" to FeatureState.STARTING,
            "off" to FeatureState.OFF,
            "failed" to FeatureState.FAILED,
            "hibernating" to FeatureState.UNKNOWN,
        ).forEach { (reported, expected) -> assertEquals(expected, device(monitors = allMonitors(reported)).state(Feature.IDS), reported) }
    }

    @Test
    fun `an offline DeKoi pauses everything it runs, and one setting up is starting`() {
        val offline = device(online = false)
        val settingUp = device(registeredAt = null, lastSeenAt = null)
        Feature.entries.filter { it != Feature.WEEKLY_SUMMARY }.forEach {
            assertEquals(FeatureState.PAUSED, offline.state(it), it.name)
            assertEquals(FeatureState.STARTING, settingUp.state(it), it.name)
        }
    }

    @Test
    fun `the weekly summary is the owner's setting, sent whether or not the DeKoi is online`() {
        val offline = device(online = false)
        assertEquals(FeatureState.ON, offline.state(Feature.WEEKLY_SUMMARY, weeklySummary = true))
        assertEquals(FeatureState.OFF, offline.state(Feature.WEEKLY_SUMMARY, weeklySummary = false))
        assertEquals(FeatureState.UNKNOWN, offline.state(Feature.WEEKLY_SUMMARY, weeklySummary = null))
    }

    @Test
    fun `Wi-Fi Guard asks for the Wi-Fi name first, whatever the DeKoi reports or whether it is online`() {
        assertEquals(FeatureState.NEEDS_WIFI_NAME, device(wifiName = null).state(Feature.WIFI_GUARD))
        assertEquals(FeatureState.NEEDS_WIFI_NAME, device(wifiName = null, online = false).state(Feature.WIFI_GUARD))
        assertEquals(FeatureState.NEEDS_WIFI_NAME, olderDevice().copy(wifiName = null).state(Feature.WIFI_GUARD))
    }

    @Test
    fun `Wi-Fi Guard shows the state the DeKoi reports`() {
        mapOf(
            "watching" to FeatureState.ON,
            "learning" to FeatureState.LEARNING,
            "no_name" to FeatureState.STARTING,
            "no_country" to FeatureState.OFF,
            "no_radio" to FeatureState.OFF,
            "uplink" to FeatureState.OFF,
            "orbiting" to FeatureState.UNKNOWN,
        ).forEach { (reported, expected) -> assertEquals(expected, device(wifiWatch = reported).state(Feature.WIFI_GUARD), reported) }
    }

    @Test
    fun `a check that reports by itself is starting for a while after the DeKoi started or updated`() {
        val justStarted = olderDevice().copy(registeredAt = now.minus(Duration.ofMinutes(20)))
        val justUpdated = olderDevice().copy(images = listOf(ImageVersion("agent", "2.0", now.minus(Duration.ofMinutes(30)).toString())))
        val updatedLongAgo = olderDevice().copy(images = listOf(ImageVersion("agent", "1.0", now.minus(FIRST_REPORT).toString())))
        listOf(Feature.WIFI_GUARD, Feature.INTERNET_HEALTH, Feature.OPEN_PORTS).forEach {
            assertEquals(FeatureState.STARTING, justStarted.state(it), it.name)
            assertEquals(FeatureState.STARTING, justUpdated.state(it), it.name)
            assertEquals(FeatureState.WAITING_FOR_UPDATE, updatedLongAgo.state(it), it.name)
        }
    }

    @Test
    fun `new devices are learnt for a day after the first device was listed`() {
        assertEquals(
            FeatureState.LEARNING,
            device().state(Feature.NEW_DEVICES, hosts = listOf(host(firstSeen = now.minus(Duration.ofHours(3))))),
        )
        val day = now.minus(NEW_DEVICE_LEARNING)
        assertEquals(FeatureState.ON, device().state(Feature.NEW_DEVICES, hosts = listOf(host(firstSeen = day), host(now, mac = "new"))))
        assertEquals(FeatureState.ON, device().state(Feature.NEW_DEVICES, hosts = emptyList()))
        assertEquals(FeatureState.FAILED, device(monitors = allMonitors("failed")).state(Feature.NEW_DEVICES, hosts = listOf(host(now))))
        assertTrue(FeatureState.LEARNING.isOn)
    }

    @Test
    fun `last found is the newest event of the feature's kinds from this DeKoi`() {
        fun event(
            id: Long,
            kind: String,
            hoursAgo: Long,
            deviceId: String = "d",
        ) = Event(id = id, deviceId = deviceId, occurredAt = now.minus(Duration.ofHours(hoursAgo)), kind = kind)
        val events =
            listOf(
                event(1, "login_attempt", 5),
                event(2, "file_access", 2),
                event(3, "connection", 1, deviceId = "other"),
                event(4, "port_scan", 0),
            )
        assertEquals(2L, lastFound(Feature.DECOY, "d", events)?.id)
        assertEquals(4L, lastFound(Feature.SCANS, "d", events)?.id)
        assertNull(lastFound(Feature.WIFI_GUARD, "d", events))
        assertNull(lastFound(Feature.NETWORK_MAP, "d", events))
    }

    @Test
    fun `each alert kind belongs to one feature at most`() {
        val kinds = Feature.entries.flatMap { it.kinds }
        assertEquals(kinds.size, kinds.toSet().size)
    }
}
