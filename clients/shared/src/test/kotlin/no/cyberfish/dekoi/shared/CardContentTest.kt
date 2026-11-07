package no.cyberfish.dekoi.shared

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bundled device/ files with the renderer (CloudInitTest covers the renderer itself). */
class CardContentTest {
    private val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))

    @Test
    fun `the real template renders every host file`() {
        val rendered = CardContent.userData(code)

        assertTrue(rendered.startsWith("#cloud-config\n"))
        assertFalse(Regex("(?m)^ +content: !file").containsMatchIn(rendered))
        assertEquals(1, rendered.windowed(code.value.length).count { it == code.value })
        assertContains(rendered, "content: \"${code.value}\"")
        assertContains(rendered, "      -----BEGIN PUBLIC KEY-----\n")
    }

    @Test
    fun `the pinned image comes from device image_json`() {
        val image = CardContent.image
        assertTrue(image.url.startsWith("https://downloads.raspberrypi.com/"))
        assertTrue(image.url.endsWith(".img.xz"))
        assertTrue(image.downloadSize in 1 until image.extractSize)
    }

    @Test
    fun `an image without HTTPS, a real hash or whole sectors is refused`() {
        val image = CardContent.image
        assertFailsWith<IllegalArgumentException> { image.copy(url = "http://x/y.img.xz") }
        assertFailsWith<IllegalArgumentException> { image.copy(downloadSha256 = "A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { image.copy(extractSha256 = "a".repeat(63)) }
        assertFailsWith<IllegalArgumentException> { image.copy(downloadSize = 0) }
        assertFailsWith<IllegalArgumentException> { image.copy(extractSize = image.extractSize + 1) }
    }

    /** CI validates these with cloud-init itself (.github/workflows/clients.yml). */
    @Test
    fun `write rendered samples for the schema check`() {
        val dir = File(checkNotNull(System.getProperty("dekoi.renderedDir")))
        dir.mkdirs()
        File(dir, "user-data").writeText(CardContent.userData(code))
        File(dir, "network-config-cable").writeText(CloudInit.networkConfig(Network.Cable))
        val hostile = Network.WiFi("Ho\"me: \\ #[x] {y} &*!|>'%@`", "p\"a:s s\\w#ord", "NO")
        File(dir, "network-config-wifi").writeText(CloudInit.networkConfig(hostile))
        File(dir, "wifi-ssid").writeText(hostile.ssid)
    }
}
