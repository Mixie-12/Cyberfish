package no.cyberfish.dekoi.burner

import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.Network
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
        val image = CardContent.image.copy(url = "https://downloads.raspberrypi.com/x.img.xz", extractSha256 = "a".repeat(64))
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
    fun `Imager is started elevated by a constant script with the values in the environment`() {
        val hostilePath = Path.of("C:/Users/O'Brien \u2019\$(x) & y/rpi-imager.exe")
        val builder = Imager.elevatedStart(hostilePath, listOf("--cli", "\\\\.\\PhysicalDrive2"))

        val script =
            "\$ErrorActionPreference = 'Stop'; " +
                "\$p = Start-Process -FilePath \$env:DEKOI_IMAGER -ArgumentList \$env:DEKOI_IMAGER_ARGS -Verb RunAs -Wait -PassThru; " +
                "exit \$p.ExitCode"
        assertEquals(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), builder.command())
        assertEquals(hostilePath.toString(), builder.environment()["DEKOI_IMAGER"])
        assertEquals("\"--cli\" \"\\\\.\\PhysicalDrive2\"", builder.environment()["DEKOI_IMAGER_ARGS"])
    }

    @Test
    fun `Imager is found only where its installer puts it`() {
        val programFiles = createTempDirectory("programs")
        assertNull(Imager.windowsExecutable(programFiles.toString()))
        assertNull(Imager.windowsExecutable(null))
        val exe = Files.createDirectories(programFiles.resolve("Raspberry Pi Ltd/Imager")).resolve("rpi-imager.exe")
        Files.writeString(exe, "")
        assertEquals(exe, Imager.windowsExecutable(programFiles.toString()))
        programFiles.toFile().deleteRecursively()
    }

    private fun quotedArguments(builder: ProcessBuilder): List<String> =
        Regex("\"([^\"]*)\"")
            .findAll(checkNotNull(builder.environment()["DEKOI_IMAGER_ARGS"]))
            .map { it.groupValues[1] }
            .toList()

    private fun List<String>.after(flag: String): Path = Path.of(this[indexOf(flag) + 1])

    @Test
    fun `a card write renders the files, passes them to Imager and removes them`() {
        val code = ClaimCode.generate()
        val drive = Drive("\\\\.\\PhysicalDrive2", "SDXC Card", 63864569856)
        var seen: List<String> = emptyList()
        val result =
            writeCard(Path.of("C:/imager.exe"), drive, Network.WiFi("Home", "password", "NO"), code) { builder ->
                seen = quotedArguments(builder)
                assertContains(Files.readString(seen.after("--cloudinit-userdata")), "content: \"${code.value}\"")
                assertContains(Files.readString(seen.after("--cloudinit-networkconfig")), "regulatory-domain: \"NO\"")
                0
            }

        assertEquals(WriteResult.Written(code), result)
        assertEquals(drive.device, seen.last())
        assertFalse(Files.exists(seen.after("--cloudinit-userdata").parent))
    }

    @Test
    fun `a failed write returns the end of Imager's log`() {
        val result =
            writeCard(Path.of("C:/imager.exe"), Drive("d", "l", 1), Network.Cable, ClaimCode.generate()) { builder ->
                val log = quotedArguments(builder).after("--log-file")
                Files.writeString(log, "x".repeat(40_000) + "Error: Download corrupt. SHA256 does not match")
                1
            }

        val failed = assertIs<WriteResult.Failed>(result)
        assertEquals(1, failed.exitCode)
        assertTrue(failed.log.endsWith("Error: Download corrupt. SHA256 does not match"))
        assertTrue(failed.log.length <= 32 * 1024)
    }
}
