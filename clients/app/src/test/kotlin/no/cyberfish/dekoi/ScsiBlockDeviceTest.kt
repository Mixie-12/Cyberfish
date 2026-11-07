package no.cyberfish.dekoi

import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import me.jahnen.libaums.core.usb.UsbCommunication
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private const val INQUIRY = 0x12
private const val TEST_UNIT_READY = 0x00
private const val READ_CAPACITY = 0x25
private const val REQUEST_SENSE = 0x03
private const val READ_10 = 0x28
private const val WRITE_10 = 0x2A

private const val NOT_READY = 0x02
private const val ILLEGAL_REQUEST = 0x05
private const val UNIT_ATTENTION = 0x06
private const val DATA_PROTECT = 0x07
private const val BECOMING_READY = 0x04
private const val WRITE_PROTECTED = 0x27
private const val MEDIUM_CHANGED = 0x28
private const val MEDIUM_NOT_PRESENT = 0x3A
private const val INVALID_FIELD = 0x24

private const val BLOCK = 512

/**
 * A USB card reader as the phone sees it: SCSI commands over bulk-only
 * transport (USB MSC BOT 1.0). Its sense data is the 18 bytes asked for,
 * and its additional length can say it has more, as real readers do. It
 * refuses reads and writes of more than [maxBlocks] blocks in one command.
 */
private class SimulatedReader(
    private val card: ByteArray?,
    private var unitAttentions: Int = 0,
    private var becomingReady: Int = 0,
    private val locked: Boolean = false,
    private val additionalSenseLength: Int = 10,
    private val maxBlocks: Int = Int.MAX_VALUE,
) : UsbCommunication {
    private val toHost = ArrayDeque<ByteBuffer>()
    private var write: Write? = null
    private var senseKey = 0
    private var senseCode = 0
    private var senseQualifier = 0

    private class Write(
        val tag: Int,
        var offset: Int,
        var remaining: Int,
        val refused: Boolean,
    )

    override fun bulkOutTransfer(src: ByteBuffer): Int {
        val count = src.remaining()
        val pending = write
        if (pending == null) command(src) else data(pending, src)
        return count
    }

    // As AndroidUsbCommunication: the bytes land at the position, which moves past them.
    override fun bulkInTransfer(dest: ByteBuffer): Int {
        val next = toHost.first()
        val count = minOf(next.remaining(), dest.remaining())
        dest.put(next.array(), next.position(), count)
        next.position(next.position() + count)
        if (!next.hasRemaining()) toHost.removeFirst()
        return count
    }

    private fun command(src: ByteBuffer) {
        val cbw = ByteArray(src.remaining()).also(src::get)
        val little = ByteBuffer.wrap(cbw).order(ByteOrder.LITTLE_ENDIAN)
        val big = ByteBuffer.wrap(cbw).order(ByteOrder.BIG_ENDIAN)
        check(cbw.size == 31 && little.getInt(0) == 0x43425355) { "Not a command block wrapper" }
        val tag = little.getInt(4)
        val length = little.getInt(8)
        val offset = big.getInt(17) * BLOCK
        val blocks = big.getShort(22).toInt()
        val bytes = blocks * BLOCK
        when (cbw[15].toInt() and 0xFF) {
            INQUIRY -> {
                answer(tag, ByteArray(36))
            }

            TEST_UNIT_READY -> {
                status(tag, ready())
            }

            READ_CAPACITY -> {
                if (ready()) answer(tag, capacity()) else status(tag, false)
            }

            REQUEST_SENSE -> {
                answer(tag, sense(length))
            }

            READ_10 -> {
                if (blocks > maxBlocks) refuse(tag, bytes) else answer(tag, checkNotNull(card).copyOfRange(offset, offset + bytes))
            }

            WRITE_10 -> {
                write = Write(tag, offset, bytes, refused = blocks > maxBlocks)
            }

            else -> {
                error("Command not simulated")
            }
        }
    }

    private fun data(
        pending: Write,
        src: ByteBuffer,
    ) {
        val count = src.remaining()
        val kept = !locked && !pending.refused
        if (kept) src.get(checkNotNull(card), pending.offset, count) else src.position(src.limit())
        pending.offset += count
        pending.remaining -= count
        if (pending.remaining > 0) return
        write = null
        when {
            pending.refused -> fail(ILLEGAL_REQUEST, INVALID_FIELD)
            locked -> fail(DATA_PROTECT, WRITE_PROTECTED)
        }
        status(pending.tag, kept)
    }

    // The data phase still runs, as the host asked for it; the status says the command failed.
    private fun refuse(
        tag: Int,
        bytes: Int,
    ) {
        toHost += ByteBuffer.wrap(ByteArray(bytes))
        fail(ILLEGAL_REQUEST, INVALID_FIELD)
        status(tag, false)
    }

    private fun ready(): Boolean {
        when {
            card == null -> {
                fail(NOT_READY, MEDIUM_NOT_PRESENT)
            }

            unitAttentions > 0 -> {
                unitAttentions--
                fail(UNIT_ATTENTION, MEDIUM_CHANGED)
            }

            becomingReady > 0 -> {
                becomingReady--
                fail(NOT_READY, BECOMING_READY, qualifier = 0x01)
            }

            else -> {
                return true
            }
        }
        return false
    }

    private fun fail(
        key: Int,
        code: Int,
        qualifier: Int = 0,
    ) {
        senseKey = key
        senseCode = code
        senseQualifier = qualifier
    }

    private fun capacity(): ByteArray =
        ByteBuffer
            .allocate(8)
            .putInt(checkNotNull(card).size / BLOCK - 1)
            .putInt(BLOCK)
            .array()

    // Fixed-format sense data; reading it clears it.
    private fun sense(allocation: Int): ByteArray {
        val data = ByteArray(8 + additionalSenseLength)
        data[0] = 0x70
        data[2] = senseKey.toByte()
        data[7] = additionalSenseLength.toByte()
        data[12] = senseCode.toByte()
        data[13] = senseQualifier.toByte()
        fail(0, 0)
        return data.copyOf(minOf(allocation, data.size))
    }

    private fun answer(
        tag: Int,
        data: ByteArray,
    ) {
        toHost += ByteBuffer.wrap(data)
        status(tag, true)
    }

    private fun status(
        tag: Int,
        passed: Boolean,
    ) {
        val csw = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        csw
            .putInt(0x53425355)
            .putInt(tag)
            .putInt(0)
            .put((if (passed) 0 else 1).toByte())
        csw.flip()
        toHost += csw
    }

    override val inEndpoint: UsbEndpoint get() = error("Not simulated")
    override val outEndpoint: UsbEndpoint get() = error("Not simulated")
    override val usbInterface: UsbInterface get() = error("Not simulated")

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray,
        length: Int,
    ): Int = error("Not simulated")

    override fun resetDevice() = error("Not simulated")

    override fun clearFeatureHalt(endpoint: UsbEndpoint) = error("Not simulated")

    override fun close() = Unit
}

class ScsiBlockDeviceTest {
    private fun reader(
        card: ByteArray? = ByteArray(64 * BLOCK),
        unitAttentions: Int = 0,
        becomingReady: Int = 0,
        locked: Boolean = false,
        additionalSenseLength: Int = 10,
        maxBlocks: Int = Int.MAX_VALUE,
    ) = ScsiBlockDevice(SimulatedReader(card, unitAttentions, becomingReady, locked, additionalSenseLength, maxBlocks), 0)

    @Test
    fun `an empty slot is no card`() {
        // libaums 0.10.0 throws IllegalArgumentException here, newLimit > capacity: (36 > 18).
        assertNull(onCard { inserted(reader(card = null)) })
    }

    @Test
    fun `a reader that has more sense data than asked for still reports an empty slot`() {
        assertNull(onCard { inserted(reader(card = null, additionalSenseLength = 28)) })
    }

    @Test
    fun `a card just put in is reported once and then starts`() {
        val card = assertNotNull(onCard { inserted(reader(unitAttentions = 1)) })
        assertEquals(BLOCK, card.blockSize)
        assertEquals(63L, card.blocks)
    }

    @Test
    fun `a reader that keeps reporting a change is a card error`() {
        val error = assertFailsWith<WriteFailed> { onCard { inserted(reader(unitAttentions = 2)) } }
        assertEquals(WriteFailure.CARD_ERROR, error.failure)
    }

    @Test
    fun `a card that is becoming ready is waited for`() {
        assertNotNull(onCard { inserted(reader(becomingReady = 3)) })
    }

    @Test
    fun `blocks written are read back`() {
        val card = assertNotNull(inserted(reader()))
        val written = Random(3).nextBytes(4 * BLOCK)
        card.write(8, ByteBuffer.wrap(written))
        val read = ByteBuffer.allocate(4 * BLOCK)
        card.read(8, read)
        assertContentEquals(written, read.array())
        assertEquals(read.limit(), read.position())
    }

    @Test
    fun `a reader that takes 240 blocks per command reads and writes a whole chunk`() {
        // libaums sends the chunk as one command of 2048 blocks, which such a reader refuses.
        val card = assertNotNull(inserted(reader(card = ByteArray(4096 * BLOCK), maxBlocks = 240)))
        val written = Random(5).nextBytes(CHUNK)
        card.write(1, ByteBuffer.wrap(written))
        val read = ByteBuffer.allocate(CHUNK)
        card.read(1, read)
        assertContentEquals(written, read.array())
        assertEquals(read.limit(), read.position())
    }

    @Test
    fun `a locked card stops the write as locked`() {
        val card = assertNotNull(inserted(reader(locked = true)))
        val error = assertFailsWith<WriteFailed> { onCard { card.write(0, ByteBuffer.allocate(BLOCK)) } }
        assertEquals(WriteFailure.LOCKED, error.failure)
    }

    @Test
    fun `any other failure on the card is a card error, not a crash`() {
        val error = assertFailsWith<WriteFailed> { onCard { ByteBuffer.allocate(18).limit(36) } }
        assertEquals(WriteFailure.CARD_ERROR, error.failure)
    }
}
