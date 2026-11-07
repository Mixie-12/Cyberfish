package no.cyberfish.dekoi.burner

import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import no.cyberfish.dekoi.shared.Network
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bundled device/ files with the shared renderer (CloudInitTest in shared covers the renderer itself). */
class DeviceTest {
    private val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))

    @Test
    fun `the real template renders every host file`() {
        val rendered = CloudInit.userData(Device.userDataTemplate, Device::read, code)

        assertTrue(rendered.startsWith("#cloud-config\n"))
        assertFalse(Regex("(?m)^ +content: !file").containsMatchIn(rendered))
        assertEquals(1, rendered.windowed(code.value.length).count { it == code.value })
        assertContains(rendered, "content: \"${code.value}\"")
        assertContains(rendered, "      -----BEGIN PUBLIC KEY-----\n")
    }

    @Test
    fun `the pinned image comes from device image_json`() {
        assertTrue(Device.image.url.startsWith("https://downloads.raspberrypi.com/"))
        assertEquals(64, Device.image.extractSha256.length)
    }

    /** CI validates these with cloud-init itself (.github/workflows/clients.yml). */
    @Test
    fun `write rendered samples for the schema check`() {
        val dir = File(checkNotNull(System.getProperty("dekoi.renderedDir")))
        dir.mkdirs()
        File(dir, "user-data").writeText(CloudInit.userData(Device.userDataTemplate, Device::read, code))
        File(dir, "network-config-cable").writeText(CloudInit.networkConfig(Network.Cable))
        val hostile = Network.WiFi("Ho\"me: \\ #[x] {y} &*!|>'%@`", "p\"a:s s\\w#ord", "NO")
        File(dir, "network-config-wifi").writeText(CloudInit.networkConfig(hostile))
        File(dir, "wifi-ssid").writeText(hostile.ssid)
    }
}
