package no.cyberfish.dekoi

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.sense.DataProtect
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.Image
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A card that reports 8 GB and keeps the first [stored] bytes in memory; it fails beyond them. */
private class FakeCard(
    stored: Int,
    override val blockSize: Int = 512,
    override val blocks: Long = 8_000_000_000L / 512,
) : BlockDeviceDriver {
    val bytes = ByteArray(stored) { 0x5A }
    var failWrites: IOException? = null

    override fun init() = Unit

    override fun read(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        buffer.put(bytes, range(deviceOffset, buffer), buffer.remaining())
    }

    override fun write(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) {
        failWrites?.let { throw it }
        buffer.get(bytes, range(deviceOffset, buffer), buffer.remaining())
    }

    private fun range(
        block: Long,
        buffer: ByteBuffer,
    ): Int {
        check(buffer.remaining() % blockSize == 0) { "Not whole blocks" }
        val start = block * blockSize
        check(start + buffer.remaining() <= bytes.size) { "Beyond the stored part" }
        return start.toInt()
    }
}

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private fun xz(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    XZOutputStream(out, LZMA2Options(0)).use { it.write(bytes) }
    return out.toByteArray()
}

// Two and a half chunks of random bytes, so xz stores them and finds damage
// only at the end, with a partition table signature where Raspberry Pi OS
// has one. Compressed once for all tests.
private val raw =
    Random(7).nextBytes(CHUNK * 5 / 2).also {
        it[510] = 0x55
        it[511] = 0xAA.toByte()
    }
private val compressed = xz(raw)
private val image =
    CardContent.image.copy(
        downloadSha256 = sha256(compressed),
        downloadSize = compressed.size.toLong(),
        extractSha256 = sha256(raw),
        extractSize = raw.size.toLong(),
    )

class CardImageTest {
    private fun write(
        card: FakeCard,
        download: InputStream = ByteArrayInputStream(compressed),
        expected: Image = image,
    ) = writeImage(download, expected, card) { _, _ -> }

    private fun FakeCard.head() = bytes.copyOf(CHUNK)

    @Test
    fun theImageIsStreamedOntoTheCardAndVerified() {
        val card = FakeCard(raw.size + CHUNK)
        var progress = 0L to 0L

        writeImage(ByteArrayInputStream(compressed), image, card) { downloaded, written -> progress = downloaded to written }
        var checked = 0L
        verifyImage(card, image) { checked = it }

        assertContentEquals(raw, card.bytes.copyOf(raw.size))
        assertEquals(compressed.size.toLong() to raw.size.toLong(), progress)
        assertEquals(raw.size.toLong(), checked)
        // Nothing past the image is touched.
        assertTrue(card.bytes.drop(raw.size).all { it == 0x5A.toByte() })
    }

    @Test
    fun aDownloadWithTheWrongHashLeavesNoPartitionTable() {
        val card = FakeCard(raw.size)

        val error = assertFailsWith<WriteFailed> { write(card, expected = image.copy(downloadSha256 = "0".repeat(64))) }

        assertEquals(WriteFailure.DOWNLOAD_DAMAGED, error.failure)
        assertTrue(card.head().all { it == 0.toByte() })
        assertContentEquals(raw.copyOfRange(CHUNK, raw.size), card.bytes.copyOfRange(CHUNK, raw.size))
    }

    @Test
    fun aDownloadOfTheWrongSizeFails() {
        val error = assertFailsWith<WriteFailed> { write(FakeCard(raw.size), expected = image.copy(downloadSize = image.downloadSize + 1)) }
        assertEquals(WriteFailure.DOWNLOAD_DAMAGED, error.failure)
    }

    @Test
    fun anImageLargerThanPinnedStopsBeforeWritingPastIt() {
        val shorter = image.copy(extractSize = raw.size.toLong() - CHUNK)
        val card = FakeCard(raw.size)

        val error = assertFailsWith<WriteFailed> { write(card, expected = shorter) }

        assertEquals(WriteFailure.DOWNLOAD_DAMAGED, error.failure)
        assertTrue(card.bytes.copyOfRange(shorter.extractSize.toInt(), raw.size).all { it == 0x5A.toByte() })
    }

    @Test
    fun corruptedCompressedDataIsADamagedDownload() {
        val corrupted = compressed.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        val card = FakeCard(raw.size)

        val error = assertFailsWith<IOException> { write(card, ByteArrayInputStream(corrupted)) }

        assertEquals(WriteFailure.DOWNLOAD_DAMAGED, downloadFailure(error))
        assertTrue(card.head().all { it == 0.toByte() })
    }

    @Test
    fun aLostConnectionIsANetworkFailure() {
        val dropping =
            object : InputStream() {
                private val source = ByteArrayInputStream(compressed, 0, compressed.size / 2)

                override fun read(): Int = source.read().takeIf { it >= 0 } ?: throw IOException("Connection reset")
            }
        val card = FakeCard(raw.size)

        val error = assertFailsWith<IOException> { write(card, dropping) }

        assertEquals(WriteFailure.NETWORK, downloadFailure(error))
        assertTrue(card.head().all { it == 0.toByte() })
    }

    @Test
    fun cardErrorsAreNotTakenForNetworkErrors() {
        val card = FakeCard(raw.size).apply { failWrites = IOException("Pipe") }
        assertEquals(WriteFailure.CARD_ERROR, assertFailsWith<WriteFailed> { write(card) }.failure)

        val locked = FakeCard(raw.size).apply { failWrites = DataProtect(null) }
        assertEquals(WriteFailure.LOCKED, assertFailsWith<WriteFailed> { write(locked) }.failure)
    }

    @Test
    fun aCardThatDoesNotKeepTheImageFailsVerification() {
        val card = FakeCard(raw.size)
        write(card)
        card.bytes[raw.size - 1] = (card.bytes[raw.size - 1] + 1).toByte()

        val error = assertFailsWith<WriteFailed> { verifyImage(card, image) {} }

        assertEquals(WriteFailure.NOT_VERIFIED, error.failure)
    }

    @Test
    fun onlyCardsOf8To256GbWithRoomForTheImageAreWritten() {
        val pinned = CardContent.image
        assertTrue(suitableCard(512, 7_948_206_080, pinned), "a real 8 GB card")
        assertTrue(suitableCard(512, 255_869_321_216, pinned), "a real 256 GB card")
        assertFalse(suitableCard(512, 3_965_190_144, pinned), "a 4 GB card")
        assertFalse(suitableCard(512, 512_110_190_592, pinned), "a 512 GB card")
        assertFalse(suitableCard(4096, 31_914_983_424, pinned), "a disk with 4 KB sectors")
        assertFalse(suitableCard(512, 7_948_206_080, pinned.copy(extractSize = 8_000_000_000)), "an image larger than the card")
        assertFailsWith<IllegalArgumentException> { write(FakeCard(raw.size, blocks = 4_000_000_000L / 512)) }
    }

    @Test
    fun `the technical detail names every cause, as plain text`() {
        val error =
            WriteFailed(
                WriteFailure.CARD_ERROR,
                IOException("The reader did not complete a command", IOException("errno 22\u0007 Invalid")),
            )
        assertEquals(
            "WriteFailed: CARD_ERROR\nIOException: The reader did not complete a command\nIOException: errno 22 Invalid",
            technicalDetail(error),
        )
    }
}
