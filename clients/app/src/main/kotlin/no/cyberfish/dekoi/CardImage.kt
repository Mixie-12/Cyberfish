package no.cyberfish.dekoi

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.sense.DataProtect
import no.cyberfish.dekoi.shared.Image
import org.tukaani.xz.XZIOException
import org.tukaani.xz.XZInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

/** Why writing a card stopped; each has its own message for the owner. */
enum class WriteFailure {
    NO_CARD,
    SEVERAL_CARDS,
    DENIED,
    UNSUITABLE,
    LOCKED,
    NETWORK,
    DOWNLOAD_DAMAGED,
    CARD_ERROR,
    NOT_VERIFIED,
}

/** Logcat tag for why writing stopped: readers differ, and the cause is not shown to the owner. */
const val CARD_LOG_TAG = "DeKoiCard"

/** Writing stopped; the card is not usable and the owner is told [failure]. */
class WriteFailed(
    val failure: WriteFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

/**
 * Runs a card operation. Its errors become [WriteFailed], so that a reader
 * error is never taken for a network error. The reader is a device DeKoi
 * does not control, and libaums parses its answers with runtime exceptions
 * on its error paths (IllegalStateException for an unknown status,
 * IllegalArgumentException and BufferUnderflowException for a short or
 * oversized answer). Any of them stops the write, which leaves the card
 * without a partition table; none of them closes the app.
 */
internal inline fun <T> onCard(operation: () -> T): T =
    try {
        operation()
    } catch (error: WriteFailed) {
        throw error
    } catch (error: DataProtect) {
        throw WriteFailed(WriteFailure.LOCKED, error)
    } catch (error: IOException) {
        throw WriteFailed(WriteFailure.CARD_ERROR, error)
    } catch (error: RuntimeException) {
        throw WriteFailed(WriteFailure.CARD_ERROR, error)
    }

// The chain and length of a technical detail; libaums wraps at most three deep.
private const val DETAIL_CAUSES = 6
private const val DETAIL_LENGTH = 1000

/**
 * Why writing stopped, for the technical details under the failure: each
 * cause's type and message, outermost first, as plain text.
 */
fun technicalDetail(error: Throwable): String =
    generateSequence(error) { it.cause }
        .take(DETAIL_CAUSES)
        .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        .filter { it == '\n' || !it.isISOControl() }
        .take(DETAIL_LENGTH)

/** What a download error means for the owner: a damaged file, or a lost connection. */
fun downloadFailure(error: IOException): WriteFailure =
    when (error) {
        is WriteFailed -> error.failure
        is XZIOException -> WriteFailure.DOWNLOAD_DAMAGED
        else -> WriteFailure.NETWORK
    }

// Cards hold a few percent less than their nominal size. These bounds let
// every 8 to 256 GB card through and stop 4 GB cards and larger disks.
private const val MIN_CARD_BYTES = 7_000_000_000L
private const val MAX_CARD_BYTES = 270_000_000_000L

/**
 * True for a device DeKoi may write [image] to: 512-byte sectors, 8 to 256
 * GB, and room for the whole image.
 */
fun suitableCard(
    blockSize: Int,
    bytes: Long,
    image: Image,
): Boolean = blockSize == Image.SECTOR && bytes in MIN_CARD_BYTES..MAX_CARD_BYTES && bytes >= image.extractSize

/** Bytes written per transfer: whole sectors, and few enough for one SCSI command. */
const val CHUNK = 1024 * 1024

// The pinned image needs a 64 MiB dictionary; a larger one is not our image.
private const val XZ_MEMORY_LIMIT_KIB = 128 * 1024

/**
 * Decompresses [download] onto [card] while hashing it, and throws
 * [WriteFailed] unless the download matches [image] in size and SHA-256.
 *
 * The first chunk holds the partition table. It is zeroed first and written
 * last, only once the download is proven, so a card left by any failure
 * (a damaged download, a lost connection, an unplugged reader) has no
 * partition table and cannot start anything.
 */
fun writeImage(
    download: InputStream,
    image: Image,
    card: BlockDeviceDriver,
    onProgress: (downloaded: Long, written: Long) -> Unit,
) {
    require(suitableCard(card.blockSize, card.blocks * card.blockSize, image)) { "Unsuitable card" }
    val hashing = HashingInputStream(download)
    val xz = XZInputStream(hashing, XZ_MEMORY_LIMIT_KIB)
    val head = ByteArray(CHUNK)
    val headSize = xz.fill(head)
    if (headSize == 0 || headSize % Image.SECTOR != 0) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
    onCard { card.write(0, ByteBuffer.wrap(ByteArray(headSize))) }
    var written = headSize.toLong()
    val chunk = ByteArray(CHUNK)
    while (true) {
        onProgress(hashing.count, written)
        val size = xz.fill(chunk)
        if (size == 0) break
        if (size % Image.SECTOR != 0 || written + size > image.extractSize) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
        onCard { card.write(written / Image.SECTOR, ByteBuffer.wrap(chunk, 0, size)) }
        written += size
    }
    // XZInputStream reads to the end of its input, so the hash covers every byte.
    val proven = written == image.extractSize && hashing.count == image.downloadSize && hashing.hex() == image.downloadSha256
    if (!proven) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
    onCard { card.write(0, ByteBuffer.wrap(head, 0, headSize)) }
}

/**
 * Reads the image back from [card] and throws [WriteFailed] unless it
 * hashes to [image]'s extracted SHA-256.
 */
fun verifyImage(
    card: BlockDeviceDriver,
    image: Image,
    onProgress: (checked: Long) -> Unit,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    val chunk = ByteArray(CHUNK)
    var checked = 0L
    while (checked < image.extractSize) {
        val size = minOf(CHUNK.toLong(), image.extractSize - checked).toInt()
        onCard { card.read(checked / Image.SECTOR, ByteBuffer.wrap(chunk, 0, size)) }
        digest.update(chunk, 0, size)
        checked += size
        onProgress(checked)
    }
    if (digest.digest().toHex() != image.extractSha256) throw WriteFailed(WriteFailure.NOT_VERIFIED)
}

/** Reads until [buffer] is full or the stream ends; returns the bytes read. */
private fun InputStream.fill(buffer: ByteArray): Int {
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return filled
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Counts and hashes every byte read through it. */
private class HashingInputStream(
    input: InputStream,
) : FilterInputStream(input) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L
        private set

    override fun read(): Int {
        val byte = super.read()
        if (byte >= 0) {
            digest.update(byte.toByte())
            count++
        }
        return byte
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) {
            digest.update(buffer, offset, read)
            count += read
        }
        return read
    }

    // Skipping would leave bytes out of the hash.
    override fun skip(n: Long): Long = throw IOException("Not supported")

    override fun markSupported(): Boolean = false

    fun hex(): String = digest.digest().toHex()
}
