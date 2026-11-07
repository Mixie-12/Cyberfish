package no.cyberfish.dekoi.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ClaimCodeTest {
    @Test
    fun `generated codes have the backend format and survive the QR round trip`() {
        repeat(100) {
            val code = ClaimCode.generate()
            assert(Regex("^[0-9A-HJKMNP-TV-Z]{26}$").matches(code.value)) { code.value }
            assertEquals(code, ClaimCode.parse(code.qrPayload))
            assertEquals(code, ClaimCode.parse(code.grouped))
        }
    }

    @Test
    fun `generated codes differ`() {
        assertNotEquals(ClaimCode.generate(), ClaimCode.generate())
    }

    @Test
    fun `typed codes are normalised`() {
        val code = ClaimCode.parse(" c000-0000-0000-0000-0000-0000-01 ")
        assertNotNull(code)
        assertEquals("C0000000000000000000000001", code.value)
        assertEquals(code, ClaimCode.parse("CO0O0OOOOOOOOOOOOOOOOOOOOI"))
        assertEquals(code, ClaimCode.parse("C000000000000000000000000l"))
    }

    @Test
    fun `anything else is rejected`() {
        assertNull(ClaimCode.parse(""))
        assertNull(ClaimCode.parse("C000000000000000000000001"))
        assertNull(ClaimCode.parse("C00000000000000000000000001"))
        assertNull(ClaimCode.parse("U0000000000000000000000001"))
        assertNull(ClaimCode.parse("DEKOI2:C0000000000000000000000001"))
        assertNull(ClaimCode.parse("https://example.com/C0000000000000000000000001"))
    }

    @Test
    fun `short name matches the reference computed outside Kotlin`() {
        assertEquals("DeKoi 3NFP", ClaimCode.parse("C0000000000000000000000001")?.shortName)
        assertEquals("DeKoi YQV9", ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS")?.shortName)
    }
}
