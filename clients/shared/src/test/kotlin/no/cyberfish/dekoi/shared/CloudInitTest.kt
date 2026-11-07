package no.cyberfish.dekoi.shared

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloudInitTest {
    private val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))

    @Test
    fun `includes become literal blocks at the include's indentation`() {
        val template =
            """
            |write_files:
            |  - path: /etc/a
            |    content: !file host/a
            |  - path: /etc/dekoi/claim-code
            |    content: "__CLAIM_CODE__"
            |
            """.trimMargin()
        val files = mapOf("host/a" to "first: line\n\n\tindented: tab\nlast\n")

        val rendered = CloudInit.userData(template, files::getValue, code)

        val expected =
            """
            |write_files:
            |  - path: /etc/a
            |    content: |
            |      first: line
            |
            |      	indented: tab
            |      last
            |  - path: /etc/dekoi/claim-code
            |    content: "0123456789ABCDEFGHJKMNPQRS"
            |
            """.trimMargin()
        assertEquals(expected, rendered)
    }

    @Test
    fun `the claim code is substituted only outside included files`() {
        val template = "a: \"__CLAIM_CODE__\"\n  content: !file f\n"
        val rendered = CloudInit.userData(template, { "x: __CLAIM_CODE__\n" }, code)
        assertEquals("a: \"${code.value}\"\n  content: |\n    x: __CLAIM_CODE__\n", rendered)
    }

    @Test
    fun `files that a plain literal block cannot hold exactly are refused`() {
        val template = "c: __CLAIM_CODE__\n  content: !file f\n"
        for (content in listOf("", "no newline", "two\n\n", " leading space\n", "\nblank first\n", "crlf\r\n")) {
            assertFailsWith<IllegalArgumentException>(content) { CloudInit.userData(template, { content }, code) }
        }
    }

    @Test
    fun `a malformed include or a missing placeholder fails`() {
        assertFailsWith<IllegalStateException> {
            CloudInit.userData("c: __CLAIM_CODE__\n  content: !file a b\n", { "x\n" }, code)
        }
        assertFailsWith<IllegalStateException> { CloudInit.userData("c: none\n", { "x\n" }, code) }
    }

    @Test
    fun `cable configures only the wired interface`() {
        val expected =
            """
            |network:
            |  version: 2
            |  ethernets:
            |    eth0:
            |      dhcp4: true
            |      dhcp6: true
            |      optional: true
            |
            """.trimMargin()
        assertEquals(expected, CloudInit.networkConfig(Network.Cable))
    }

    @Test
    fun `wifi adds the access point, the derived key and the country`() {
        val config = CloudInit.networkConfig(Network.WiFi("Home", "correct horse", "NO"))

        assertContains(config, "  ethernets:\n    eth0:\n")
        assertContains(config, "      regulatory-domain: \"NO\"\n")
        assertContains(config, "        \"Home\":\n          password: \"${CloudInit.wpaKey("Home", "correct horse")}\"\n")
        assertFalse("correct horse" in config)
    }

    @Test
    fun `the WPA key matches the IEEE 802_11i test vector`() {
        assertEquals("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e", CloudInit.wpaKey("IEEE", "password"))
    }

    @Test
    fun `hostile network names stay one quoted YAML scalar`() {
        val hostile =
            mapOf(
                "a\"b" to "\"a\\\"b\"",
                "back\\slash" to "\"back\\\\slash\"",
                "key: value" to "\"key: value\"",
                "\": x\n" to null,
                "- [x] {y} &z *w !t |>%@`#" to "\"- [x] {y} &z *w !t |>%@`#\"",
                "\\\"" to "\"\\\\\\\"\"",
                "Kafé ✓" to "\"Kafé ✓\"",
            )
        for ((ssid, quoted) in hostile) {
            if (quoted == null) {
                assertFailsWith<IllegalArgumentException> { Network.WiFi(ssid, "password", "NO") }
            } else {
                val config = CloudInit.networkConfig(Network.WiFi(ssid, "password", "NO"))
                assertContains(config, "\n        $quoted:\n")
            }
        }
    }

    @Test
    fun `hostile passwords never reach the file`() {
        for (password in listOf("pass\"word: x", "back\\slash\\", "#!yaml |\n", "x\u0000yyyyyyy", "a'b\"c{d}[e]")) {
            val result = runCatching { CloudInit.networkConfig(Network.WiFi("Home", password, "NO")) }
            if (password.any { it < ' ' }) {
                assertTrue(result.exceptionOrNull() is IllegalArgumentException, password)
            } else {
                val config = result.getOrThrow()
                assertFalse(password in config)
                assertContains(config, "password: \"${CloudInit.wpaKey("Home", password)}\"")
            }
        }
    }

    @Test
    fun `values no real network can have are refused`() {
        val refused =
            listOf(
                { Network.WiFi("", "password", "NO") },
                { Network.WiFi("x".repeat(33), "password", "NO") },
                { Network.WiFi("æ".repeat(17), "password", "NO") },
                { Network.WiFi("tab\there", "password", "NO") },
                { Network.WiFi("line break", "password", "NO") },
                { Network.WiFi("nel\u0085", "password", "NO") },
                { Network.WiFi("del\u007F", "password", "NO") },
                { Network.WiFi("Home", "short", "NO") },
                { Network.WiFi("Home", "x".repeat(64), "NO") },
                { Network.WiFi("Home", "pässword", "NO") },
                { Network.WiFi("Home", "password", "no") },
                { Network.WiFi("Home", "password", "NOR") },
                { Network.WiFi("Home", "password", "\"\n") },
            )
        for (build in refused) {
            assertFailsWith<IllegalArgumentException> { build() }
        }
    }
}
