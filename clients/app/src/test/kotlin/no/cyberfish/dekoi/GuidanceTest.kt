package no.cyberfish.dekoi

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `how to disconnect depends on what the device is`() {
        assertEquals(R.string.step_disconnect_power, disconnectStep(HostKind.TV))
        assertEquals(R.string.step_disconnect_wifi, disconnectStep(HostKind.PHONE))
        assertEquals(R.string.step_disconnect_network_gear, disconnectStep(HostKind.SWITCH))
        assertEquals(R.string.step_disconnect_unknown, disconnectStep(HostKind.UNKNOWN))
    }

    @Test
    fun `impersonation asks for a router restart, intrusion for new account passwords`() {
        assertTrue(R.string.step_restart_router in steps("arp_spoof", HostKind.UNKNOWN))
        assertTrue(R.string.step_change_account_passwords in steps("login_attempt", HostKind.UNKNOWN))
    }

    @Test
    fun `DeKoi going offline is about DeKoi, and coming back needs nothing`() {
        assertEquals(
            listOf(R.string.step_check_power, R.string.step_check_cable, R.string.step_wait_reconnect),
            steps("device_offline", HostKind.UNKNOWN),
        )
        assertTrue(steps("device_online", HostKind.UNKNOWN).isEmpty())
    }

    @Test
    fun `every kind and severity has words`() {
        assertEquals(
            HostKind.entries.size,
            HostKind.entries
                .map(::kindName)
                .toSet()
                .size,
        )
        assertEquals(
            HostKind.entries.size,
            HostKind.entries
                .map(::looksLike)
                .toSet()
                .size,
        )
        assertEquals(
            Severity.entries.size,
            Severity.entries
                .map(::severityLabel)
                .toSet()
                .size,
        )
    }
}
