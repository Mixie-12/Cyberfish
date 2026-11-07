/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 * Modified 2026 by CyberFish; see the class comment.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package no.cyberfish.dekoi

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper
import me.jahnen.libaums.core.driver.scsi.commands.CommandBlockWrapper.Direction
import me.jahnen.libaums.core.driver.scsi.commands.CommandStatusWrapper
import me.jahnen.libaums.core.driver.scsi.commands.ScsiInquiry
import me.jahnen.libaums.core.driver.scsi.commands.ScsiInquiryResponse
import me.jahnen.libaums.core.driver.scsi.commands.ScsiRead10
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacity
import me.jahnen.libaums.core.driver.scsi.commands.ScsiReadCapacityResponse
import me.jahnen.libaums.core.driver.scsi.commands.ScsiTestUnitReady
import me.jahnen.libaums.core.driver.scsi.commands.ScsiWrite10
import me.jahnen.libaums.core.driver.scsi.commands.sense.NotReadyTryAgain
import me.jahnen.libaums.core.driver.scsi.commands.sense.ScsiRequestSense
import me.jahnen.libaums.core.driver.scsi.commands.sense.ScsiRequestSenseResponse
import me.jahnen.libaums.core.driver.scsi.commands.sense.SenseException
import me.jahnen.libaums.core.driver.scsi.commands.sense.UnitAttention
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.IOException
import java.nio.ByteBuffer

/**
 * libaums 0.10.0's ScsiBlockDevice with the upstream fixes d78996b and
 * fae7261, which no release has (ADR 0029). In 0.10.0 every REQUEST SENSE
 * throws IllegalArgumentException, so a reader that reports anything, an
 * empty slot or a card just put in, closes the app. Also changed: reads and
 * writes go in commands of at most 240 blocks, a retried
 * command starts from the buffer it was given, a transfer that moves nothing
 * ends the data phase instead of looping, the status is read even after
 * short data, and the InitRequired handling, which libaums never triggers,
 * is gone.
 *
 * Delete this file and use BlockDeviceDriverFactory once a libaums release
 * passes ScsiBlockDeviceTest.
 */
internal class ScsiBlockDevice(
    private val usb: UsbCommunication,
    private val lun: Byte,
) : BlockDeviceDriver {
    private val commandBuffer = ByteBuffer.allocate(COMMAND_SIZE)
    private val statusBuffer = ByteBuffer.allocate(CommandStatusWrapper.SIZE)
    private val status = CommandStatusWrapper()
    private val writeCommand = ScsiWrite10(lun = lun)
    private val readCommand = ScsiRead10(lun = lun)
    private var tag = 1

    override var blockSize = 0
        private set

    // As libaums 0.10.0: READ CAPACITY(10)'s last block address, one less than the count.
    override var blocks = 0L
        private set

    // A reader reports a card put in since its last command once, as unit
    // attention, and then works. During a write the same report means the
    // card may have changed, so only starting retries it.
    override fun init() =
        try {
            start()
        } catch (_: UnitAttention) {
            start()
        }

    private fun start() {
        val response = ByteBuffer.allocate(INQUIRY_SIZE)
        transfer(ScsiInquiry(INQUIRY_SIZE.toByte(), lun = lun), response)
        response.clear()
        val inquiry = ScsiInquiryResponse.read(response)
        if (inquiry.peripheralQualifier.toInt() != 0 || inquiry.peripheralDeviceType.toInt() != 0) {
            throw IOException("Not a direct-access block device")
        }
        transfer(ScsiTestUnitReady(lun = lun), ByteBuffer.allocate(0))
        response.clear()
        transfer(ScsiReadCapacity(lun = lun), response)
        response.clear()
        val capacity = ScsiReadCapacityResponse.read(response)
        blockSize = capacity.blockLength
        blocks = capacity.logicalBlockAddress.toLong()
    }

    @Synchronized
    override fun read(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) = inPieces(deviceOffset, buffer) { block, piece ->
        readCommand.init(block.toInt(), piece.remaining(), blockSize)
        transfer(readCommand, piece)
    }

    @Synchronized
    override fun write(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) = inPieces(deviceOffset, buffer) { block, piece ->
        writeCommand.init(block.toInt(), piece.remaining(), blockSize)
        transfer(writeCommand, piece)
    }

    /**
     * Runs [command] on [buffer] in pieces of at most [MAX_BLOCKS] blocks,
     * as Linux's usb-storage does for every USB mass storage device: "Some
     * devices are known to choke with anything larger"
     * (drivers/usb/storage/scsiglue.c). libaums sends any length as one
     * command and one transfer. Leaves the position at the limit.
     */
    private inline fun inPieces(
        deviceOffset: Long,
        buffer: ByteBuffer,
        command: (block: Long, piece: ByteBuffer) -> Unit,
    ) {
        require(buffer.remaining() % blockSize == 0) { "Not whole blocks" }
        var block = deviceOffset
        while (buffer.hasRemaining()) {
            val bytes = minOf(buffer.remaining(), MAX_BLOCKS * blockSize)
            // A duplicate keeps the array offsets, which libaums' transfers use.
            val piece = buffer.duplicate()
            piece.limit(buffer.position() + bytes)
            command(block, piece)
            buffer.position(buffer.position() + bytes)
            block += bytes / blockSize
        }
    }

    /**
     * Runs [command], again while the reader says to try again. Sense data
     * that reports an error throws its SenseException.
     */
    private fun transfer(
        command: CommandBlockWrapper,
        buffer: ByteBuffer,
    ) {
        val start = buffer.position()
        val end = buffer.limit()
        var last: IOException? = null
        repeat(ATTEMPTS) {
            buffer.limit(end)
            buffer.position(start)
            try {
                if (send(command, buffer)) return
                requestSense()
                // Sense data that is not an error: done, unless the data has to come again.
                if (command.direction == Direction.NONE) return
            } catch (error: NotReadyTryAgain) {
                last = error
            } catch (error: SenseException) {
                throw error
            } catch (error: PipeException) {
                reset()
                last = error
            } catch (error: IOException) {
                last = error
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
        throw IOException("The reader did not complete a command", last)
    }

    /** Asks why the last command failed; throws the SenseException for an error. */
    private fun requestSense() {
        val response = ByteBuffer.allocate(SENSE_SIZE)
        if (!send(ScsiRequestSense(SENSE_SIZE.toByte(), lun = lun), response)) throw IOException("Request sense failed")
        response.clear()
        ScsiRequestSenseResponse.read(response).checkResponseForError()
    }

    /** One command, its data and its status (USB MSC BOT 1.0, 5); true when it passed. */
    private fun send(
        command: CommandBlockWrapper,
        buffer: ByteBuffer,
    ): Boolean {
        command.dCbwTag = tag++
        commandBuffer.clear()
        commandBuffer.array().fill(0)
        command.serialize(commandBuffer)
        commandBuffer.clear()
        if (usb.bulkOutTransfer(commandBuffer) != COMMAND_SIZE) throw IOException("Command not sent")

        val start = buffer.position()
        var length = command.dCbwDataTransferLength
        buffer.limit(start + length)
        var moved = 0
        while (moved < length) {
            val count = if (command.direction == Direction.IN) usb.bulkInTransfer(buffer) else usb.bulkOutTransfer(buffer)
            if (count <= 0) break
            moved += count
            if (command.bCbwDynamicSize) {
                // The device says how much it has: never less than it sent, never more than fits.
                length = command.dynamicSizeFromPartialResponse(buffer).coerceIn(moved, buffer.capacity() - start)
                buffer.limit(start + length)
            }
        }

        statusBuffer.clear()
        if (usb.bulkInTransfer(statusBuffer) != CommandStatusWrapper.SIZE) throw IOException("No command status")
        statusBuffer.clear()
        status.read(statusBuffer)
        if (status.dCswTag != command.dCbwTag) throw IOException("Command status for another command")
        return when (val code = status.bCswStatus.toInt()) {
            CommandStatusWrapper.COMMAND_PASSED -> {
                if (moved != length) throw IOException("Data length $moved, expected $length")
                true
            }

            CommandStatusWrapper.COMMAND_FAILED -> {
                false
            }

            CommandStatusWrapper.PHASE_ERROR -> {
                reset()
                throw IOException("Phase error")
            }

            else -> {
                throw IOException("Unknown command status $code")
            }
        }
    }

    // Bulk-Only Mass Storage Reset (USB MSC BOT 1.0, 3.1), then both endpoints out of halt.
    private fun reset() {
        if (usb.controlTransfer(RESET_TYPE, RESET_REQUEST, 0, usb.usbInterface.id, ByteArray(0), 0) == -1) {
            throw IOException("Reset failed")
        }
        usb.clearFeatureHalt(usb.inEndpoint)
        usb.clearFeatureHalt(usb.outEndpoint)
    }

    private companion object {
        const val COMMAND_SIZE = 31
        const val INQUIRY_SIZE = 36

        // ScsiRequestSense requires 18.
        const val SENSE_SIZE = 18
        const val MAX_BLOCKS = 240
        const val ATTEMPTS = 6
        const val RETRY_DELAY_MS = 100L
        const val RESET_TYPE = 0x21
        const val RESET_REQUEST = 0xFF
    }
}
