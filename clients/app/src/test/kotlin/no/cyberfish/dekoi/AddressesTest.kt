package no.cyberfish.dekoi

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddressesTest {
    private fun network(cidr: String) = Network("d", cidr, "interface", null, Instant.EPOCH, Instant.EPOCH)

    @Test
    fun `an address inside the prefix is contained`() {
        assertTrue(network("192.168.1.0/24").contains("192.168.1.23"))
        assertTrue(network("10.0.0.0/9").contains("10.127.255.1"))
        assertTrue(network("2001:db8::/32").contains("2001:db8:1::5"))
    }

    @Test
    fun `an address outside the prefix or of the other family is not`() {
        assertFalse(network("192.168.1.0/24").contains("192.168.2.23"))
        assertFalse(network("10.0.0.0/9").contains("10.128.0.1"))
        assertFalse(network("192.168.1.0/24").contains("2001:db8::1"))
    }

    @Test
    fun `malformed values are never contained`() {
        assertFalse(network("192.168.1.0/24").contains("192.168.1"))
        assertFalse(network("192.168.1.0/24").contains("192.168.1.300"))
        assertFalse(network("192.168.1.0").contains("192.168.1.1"))
        assertFalse(network("192.168.1.0/24").contains("router.local"))
    }
}
