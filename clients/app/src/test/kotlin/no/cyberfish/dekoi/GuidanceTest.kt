package no.cyberfish.dekoi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceTest {
    private val deviceKinds =
        listOf(
            "login_attempt",
            "connection",
            "port_scan",
            "host_scan",
            "arp_spoof",
            "rogue_dhcp",
            "rogue_router",
            "name_poisoning",
            "ids_alert",
        )

    @Test
    fun `every alert about another device starts with finding it and then disconnecting it`() {
        for (kind in deviceKinds) {
            for (source in HostKind.entries) {
                val steps = steps(kind, source)
                assertEquals(findStep(source), steps[0], "$kind from $source")
                assertEquals(disconnectStep(source), steps[1], "$kind from $source")
            }
        }
    }

    @Test
    fun `how to disconnect follows the table in the design, one row per group of kinds`() {
        val table =
            mapOf(
                R.string.step_disconnect_personal to listOf(HostKind.PHONE, HostKind.TABLET, HostKind.WEARABLE),
                R.string.step_disconnect_computer to listOf(HostKind.COMPUTER),
                R.string.step_disconnect_power to
                    listOf(HostKind.TV, HostKind.MEDIA_PLAYER, HostKind.SPEAKER, HostKind.GAME_CONSOLE, HostKind.PRINTER),
                R.string.step_disconnect_smart_home to listOf(HostKind.CAMERA, HostKind.SMART_HOME),
                R.string.step_disconnect_storage to listOf(HostKind.STORAGE, HostKind.SERVER),
                R.string.step_disconnect_network_gear to listOf(HostKind.ROUTER, HostKind.ACCESS_POINT, HostKind.SWITCH),
                R.string.step_disconnect_unknown to listOf(HostKind.UNKNOWN),
            )
        assertEquals(HostKind.entries.toSet(), table.values.flatten().toSet())
        for ((step, kinds) in table) {
            for (kind in kinds) assertEquals(step, disconnectStep(kind).text, "$kind")
        }
    }

    @Test
    fun `only the steps for an unknown device refer to its hardware address`() {
        for (kind in HostKind.entries) {
            val unknown = kind == HostKind.UNKNOWN
            assertEquals(unknown, findStep(kind).usesHardwareAddress, "$kind")
            assertEquals(unknown, disconnectStep(kind).usesHardwareAddress, "$kind")
        }
    }

    @Test
    fun `impersonation asks for a router restart, intrusion for new account passwords`() {
        assertEquals(
            listOf(R.string.step_restart_router, R.string.step_change_wifi_password),
            steps("arp_spoof", HostKind.UNKNOWN).drop(2).map { it.text },
        )
        assertEquals(
            listOf(R.string.step_change_wifi_password, R.string.step_change_account_passwords),
            steps("login_attempt", HostKind.PHONE).drop(2).map { it.text },
        )
    }

    @Test
    fun `DeKoi going offline is about DeKoi, and coming back needs nothing`() {
        assertEquals(
            listOf(R.string.step_check_power, R.string.step_check_cable, R.string.step_wait_reconnect),
            steps("device_offline", HostKind.UNKNOWN).map { it.text },
        )
        assertTrue(steps("device_online", HostKind.UNKNOWN).isEmpty())
    }

    @Test
    fun `trusting a source of a network-wide attack asks first`() {
        val confirmed = deviceKinds.filter(::trustNeedsConfirmation).toSet()
        assertEquals(setOf("arp_spoof", "rogue_dhcp", "rogue_router", "name_poisoning"), confirmed)
    }

    @Test
    fun `sign-in attempts, opened bait files and impersonation are very likely attacks`() {
        val reasons =
            mapOf(
                "login_attempt" to R.string.threat_sign_in,
                "file_access" to R.string.threat_file,
                "arp_spoof" to R.string.threat_impersonation,
                "rogue_dhcp" to R.string.threat_impersonation,
                "name_poisoning" to R.string.threat_impersonation,
                "dns_hijack" to R.string.threat_dns,
                "evil_twin" to R.string.threat_wifi_copy,
            )
        for ((kind, reason) in reasons) {
            assertEquals(Threat(ThreatLevel.LIKELY, reason), threat(setOf(kind), knownSource = false), kind)
        }
        assertEquals(R.string.threat_sign_in, threat(setOf("connection", "login_attempt"), knownSource = false).reason)
    }

    @Test
    fun `scans and decoy connections from an unknown device are possible attacks`() {
        for (kind in listOf("connection", "port_scan", "host_scan", "rogue_router", "ids_alert", "something_new")) {
            assertEquals(ThreatLevel.POSSIBLE, threat(setOf(kind), knownSource = false).level, kind)
        }
    }

    @Test
    fun `known devices and DeKoi's own status are probably harmless`() {
        assertEquals(Threat(ThreatLevel.UNLIKELY, R.string.threat_known), threat(setOf("login_attempt"), knownSource = true))
        assertEquals(ThreatLevel.UNLIKELY, threat(setOf("device_online"), knownSource = false).level)
        assertEquals(ThreatLevel.UNLIKELY, threat(setOf("device_offline", "device_online"), knownSource = false).level)
        assertFalse(threat(setOf("device_offline", "port_scan"), knownSource = false).level == ThreatLevel.UNLIKELY)
    }

    @Test
    fun `every kind, severity and threat level has words`() {
        for (words in listOf(::kindName, ::looksLike, ::subject)) {
            assertEquals(
                HostKind.entries.size,
                HostKind.entries
                    .map(words)
                    .toSet()
                    .size,
            )
        }
        assertEquals(
            Severity.entries.size,
            Severity.entries
                .map(::severityLabel)
                .toSet()
                .size,
        )
        assertEquals(
            ThreatLevel.entries.size,
            ThreatLevel.entries
                .map(::threatLabel)
                .toSet()
                .size,
        )
    }
}
