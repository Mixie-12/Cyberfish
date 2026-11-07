package no.cyberfish.dekoi

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class HostNameTest {
    private val strings =
        mapOf(
            R.string.network_private to "Private address",
            R.string.host_maker_kind to "%1\$s %2\$s",
            R.string.kind_tv to "TV",
            R.string.kind_unknown to "Unknown device",
        )

    private fun name(
        hostname: String? = null,
        model: String? = null,
        vendor: String? = null,
        kind: String? = null,
        private: Boolean = false,
    ): String {
        val seen = Instant.parse("2026-11-07T12:00:00Z")
        val host =
            NetworkHost(
                deviceId = "d",
                mac = "3c:22:fb:00:00:01",
                ip = "192.168.1.23",
                vendor = vendor,
                isPrivateMac = private,
                firstSeenAt = seen,
                lastSeenAt = seen,
                hostname = hostname,
                model = model,
                kindName = kind,
            )
        return hostDisplayName(host) { strings.getValue(it) }
    }

    @Test
    fun `an announced name wins, then the model`() {
        assertEquals("living-room", name(hostname = "living-room", model = "OLED55C3", vendor = "LG", kind = "tv"))
        assertEquals("OLED55C3", name(hostname = " ", model = "OLED55C3", vendor = "LG", kind = "tv"))
    }

    @Test
    fun `without a name, the maker and the kind`() {
        assertEquals("LG TV", name(vendor = "LG", kind = "tv"))
        assertEquals("LG", name(vendor = "LG"))
        assertEquals("TV", name(kind = "tv"))
    }

    @Test
    fun `a private address and a host DeKoi knows nothing about`() {
        assertEquals("Private address", name(private = true))
        assertEquals("Unknown device", name())
    }
}
