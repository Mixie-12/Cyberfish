package no.cyberfish.dekoi.burner

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WriteTest {
    @Test
    fun `only writable card and USB disks without a system are offered`() {
        // The first entry is the real output on the development machine; the
        // others follow the same format for the cases a card reader produces.
        val getDisk =
            """
            [{"Number":0,"FriendlyName":"WDC WDS200T2B0C-00PXH0","Size":2000398934016,"BusType":"NVMe","IsSystem":true,"IsBoot":true,"IsOffline":false,"IsReadOnly":false},
             {"Number":1,"FriendlyName":"Generic STORAGE DEVICE","Size":31914983424,"BusType":"USB","IsSystem":false,"IsBoot":false,"IsOffline":false,"IsReadOnly":false},
             {"Number":2,"FriendlyName":"SDXC Card","Size":63864569856,"BusType":"SD","IsSystem":false,"IsBoot":false,"IsOffline":false,"IsReadOnly":false},
             {"Number":3,"FriendlyName":"USB boot stick","Size":15376000000,"BusType":"USB","IsSystem":false,"IsBoot":true,"IsOffline":false,"IsReadOnly":false},
             {"Number":4,"FriendlyName":"Locked card","Size":7948206080,"BusType":"SD","IsSystem":false,"IsBoot":false,"IsOffline":false,"IsReadOnly":true},
             {"Number":5,"FriendlyName":"Empty reader","Size":0,"BusType":"USB","IsSystem":false,"IsBoot":false,"IsOffline":false,"IsReadOnly":false},
             {"Number":6,"FriendlyName":"Data disk","Size":1000204886016,"BusType":"SATA","IsSystem":false,"IsBoot":false,"IsOffline":false,"IsReadOnly":false},
             {"Number":7,"FriendlyName":"Offline USB","Size":31914983424,"BusType":"USB","IsSystem":false,"IsBoot":false,"IsOffline":true,"IsReadOnly":false},
             {"Number":8,"FriendlyName":"eMMC","Size":31914983424,"BusType":"MMC","IsSystem":true,"IsBoot":false,"IsOffline":false,"IsReadOnly":false}]
            """.trimIndent()

        val expected =
            listOf(
                Drive("\\\\.\\PhysicalDrive1", "Generic STORAGE DEVICE", 31914983424),
                Drive("\\\\.\\PhysicalDrive2", "SDXC Card", 63864569856),
            )
        assertEquals(expected, WindowsDrives.parse(getDisk))
    }

    @Test
    fun `a machine without cards lists none`() {
        val systemOnly =
            """[{"Number":0,"FriendlyName":"WDC","Size":2,"BusType":"NVMe","IsSystem":true,"IsBoot":true,"IsOffline":false,"IsReadOnly":false}]"""
        assertEquals(emptyList(), WindowsDrives.parse(systemOnly))
        assertEquals(emptyList(), WindowsDrives.parse("[]"))
    }

    @Test
    fun `a disk with a missing field fails instead of being offered`() {
        val noSystemFlag =
            """[{"Number":1,"FriendlyName":"x","Size":1,"BusType":"USB","IsBoot":false,"IsOffline":false,"IsReadOnly":false}]"""
        assertFailsWith<NoSuchElementException> { WindowsDrives.parse(noSystemFlag) }
    }

    @Test
    fun `Imager gets the pinned image, its uncompressed hash and the rendered files`() {
        val image = Image("https://downloads.raspberrypi.com/x.img.xz", "a".repeat(64))
        val drive = Drive("\\\\.\\PhysicalDrive2", "SDXC Card", 63864569856)

        val arguments =
            Imager.arguments(image, Path.of("C:/t/user-data"), Path.of("C:/t/network-config"), Path.of("C:/t/imager.log"), drive)

        val expected =
            listOf(
                "--cli",
                "--debug",
                "--log-file",
                Path.of("C:/t/imager.log").toString(),
                "--sha256",
                "a".repeat(64),
                "--cloudinit-userdata",
                Path.of("C:/t/user-data").toString(),
                "--cloudinit-networkconfig",
                Path.of("C:/t/network-config").toString(),
                "https://downloads.raspberrypi.com/x.img.xz",
                "\\\\.\\PhysicalDrive2",
            )
        assertEquals(expected, arguments)
        assertFalse("--enable-writing-system-drives" in arguments)
    }

    @Test
    fun `the Windows command line quotes every argument`() {
        val line = Imager.windowsCommandLine(listOf("--cli", "C:\\Users\\A B & C%x%\\user-data", "\\\\.\\PhysicalDrive2"))
        assertEquals("\"--cli\" \"C:\\Users\\A B & C%x%\\user-data\" \"\\\\.\\PhysicalDrive2\"", line)
        assertFailsWith<IllegalArgumentException> { Imager.windowsCommandLine(listOf("a\"b")) }
        assertFailsWith<IllegalArgumentException> { Imager.windowsCommandLine(listOf("C:\\dir\\")) }
    }

    @Test
    fun `an image without HTTPS or a real hash is refused`() {
        assertFailsWith<IllegalArgumentException> { Image("http://x/y.img.xz", "a".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { Image("https://x/y.img.xz", "A".repeat(64)) }
    }
}
