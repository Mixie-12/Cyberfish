package no.cyberfish.dekoi

import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import no.cyberfish.dekoi.shared.Installer
import no.cyberfish.dekoi.shared.Network
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** A download that counts what is read from it. */
private class Download(
    bytes: ByteArray,
) : InputStream() {
    private val input = ByteArrayInputStream(bytes)
    var read = 0L

    override fun read(): Int = input.read().also { if (it >= 0) read++ }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = input.read(buffer, offset, length).also { if (it > 0) read += it }
}

class CardInstallerTest {
    // Two and a half chunks, so a download spans several reads.
    private val bundle = Random(7).nextBytes(1024 * 1024 * 5 / 2)
    private val installer = Installer("mixie-12/dekoi-installer", sha256(bundle), bundle.size.toLong())

    private fun receive(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        receive(ByteArrayInputStream(bytes), output, installer) {}
        return output.toByteArray()
    }

    @Test
    fun aDownloadThatMatchesThePinIsKept() {
        var progress = 0L
        val output = ByteArrayOutputStream()
        receive(ByteArrayInputStream(bundle), output, installer) { progress = it }

        assertContentEquals(bundle, output.toByteArray())
        assertEquals(installer.size, progress)
    }

    @Test
    fun aShortLongOrChangedDownloadIsRefused() {
        val changed = bundle.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        for (download in listOf(bundle.copyOf(bundle.size - 1), bundle + 0.toByte(), changed, ByteArray(0))) {
            val error = assertFailsWith<WriteFailed> { receive(download) }
            assertEquals(WriteFailure.DOWNLOAD_DAMAGED, error.failure)
        }
    }

    @Test
    fun anEndlessDownloadIsReadOnlyAChunkPastThePin() {
        val endless = Download(ByteArray(installer.size.toInt() * 4))

        assertFailsWith<WriteFailed> { receive(endless, ByteArrayOutputStream(), installer) {} }
        assertTrue(endless.read <= installer.size + 1024 * 1024, "read ${endless.read}")
    }

    @Test
    fun aCopyHashesWhatItCopies() {
        val output = ByteArrayOutputStream()

        assertEquals(Copied(bundle.size.toLong(), sha256(bundle)), copy(ByteArrayInputStream(bundle), output))
        assertContentEquals(bundle, output.toByteArray())
        assertEquals(Copied(bundle.size.toLong(), sha256(bundle)), copy(ByteArrayInputStream(bundle), Discard))
        assertEquals(Copied(0, sha256(ByteArray(0))), copy(ByteArrayInputStream(ByteArray(0)), Discard))
    }

    @Test
    fun theBundleGoesOnlyIntoTheCard() {
        val files = listOf("config.txt", "kernel8.img", "overlays/vc4-kms-v3d.dtbo", "dekoi/user-data")
        assertNull(refusedPath(files))

        for (outside in listOf("../config.txt", "/config.txt", "overlays/../../x", "./config.txt", "overlays//x", "")) {
            assertEquals(outside, refusedPath(files + outside), outside)
        }
        for (notFat in listOf("con:fig", "a\\b", "what?", "a*", "x\u0001", "\"x\"", "<x>", "a|b", "x".repeat(256))) {
            assertEquals(notFat, refusedPath(files + notFat), notFat)
        }
        assertEquals("Config.TXT", refusedPath(files + "Config.TXT"), "the same file on FAT")
        assertNull(refusedPath(listOf("é".repeat(127))), "254 bytes")
    }

    @Test
    fun theDekoiFilesCarryTheCodeAndTheCable() {
        val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))
        val files = dekoiFiles(code, "dekoi-1762531200000")

        assertEquals(listOf("dekoi/user-data", "dekoi/network-config", "dekoi/meta-data"), files.keys.toList())
        assertEquals(CardContent.userData(code), files["dekoi/user-data"])
        assertTrue(code.value in files.getValue("dekoi/user-data"))
        assertEquals(CloudInit.networkConfig(Network.Cable), files["dekoi/network-config"])
        assertEquals("instance-id: dekoi-1762531200000\n", files["dekoi/meta-data"])
        assertNull(refusedPath(files.keys.toList()))
    }

    @Test
    fun everyErrorOfAStepBecomesItsFailure() {
        val errors = listOf(FileNotFoundException("gone"), IOException("io"), SecurityException("revoked"), IllegalArgumentException("bad"))
        for (error in errors) {
            val failed = assertFailsWith<WriteFailed> { failingWith(WriteFailure.CARD_ERROR) { throw error } }
            assertEquals(WriteFailure.CARD_ERROR, failed.failure)
            assertSame(error, failed.cause)
        }
        val inner = WriteFailed(WriteFailure.NOT_VERIFIED)
        assertSame(inner, assertFailsWith<WriteFailed> { failingWith(WriteFailure.CARD_ERROR) { throw inner } })
    }

    @Test
    fun `the technical detail names every cause, as plain text`() {
        val error =
            WriteFailed(
                WriteFailure.CARD_ERROR,
                IOException("A file on the card could not be read", IOException("errno 5\u0007 EIO")),
            )
        assertEquals(
            "WriteFailed: CARD_ERROR\nIOException: A file on the card could not be read\nIOException: errno 5 EIO",
            technicalDetail(error),
        )
    }
}
