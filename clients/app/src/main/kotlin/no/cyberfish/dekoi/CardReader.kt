package no.cyberfish.dekoi

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.driver.scsi.commands.sense.MediaNotInserted
import me.jahnen.libaums.core.driver.scsi.commands.sense.UnitAttention
import me.jahnen.libaums.core.fs.FileSystem
import me.jahnen.libaums.core.fs.UsbFile
import me.jahnen.libaums.core.partition.Partition
import me.jahnen.libaums.core.partition.PartitionTypes
import me.jahnen.libaums.core.partition.mbr.MasterBootRecord
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.nio.ByteBuffer
import kotlin.coroutines.resume

// USB mass storage, SCSI transparent command set, bulk-only transport: what
// card readers are and the only kind libaums drives. res/xml/usb_card_readers.xml
// matches the same three values.
private const val SUBCLASS_SCSI = 6
private const val PROTOCOL_BULK_ONLY = 0x50

// USB descriptor strings come from the device; they are shown, so they are bounded.
private const val NAME_LENGTH = 64

private fun UsbDevice.massStorage(): UsbInterface? =
    (0 until interfaceCount).map(::getInterface).firstOrNull {
        it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
            it.interfaceSubclass == SUBCLASS_SCSI &&
            it.interfaceProtocol == PROTOCOL_BULK_ONLY
    }

private fun usb(context: Context): UsbManager = checkNotNull(context.getSystemService(UsbManager::class.java))

/** USB card readers connected to the phone. The phone's own storage is never a USB device. */
fun cardReaders(context: Context): List<Reader> =
    usb(context).deviceList.values.filter { it.massStorage() != null }.map { device ->
        val name =
            listOfNotNull(device.manufacturerName, device.productName)
                .joinToString(" ")
                .filterNot(Char::isISOControl)
                .trim()
                .take(NAME_LENGTH)
        Reader(device.deviceName, name.ifEmpty { context.getString(R.string.write_reader_unnamed) })
    }

/**
 * Asks Android's USB permission prompt whether DeKoi may use [reader]; the
 * owner sees the reader's name there. True when allowed.
 */
suspend fun allowReader(
    context: Context,
    reader: Reader,
): Boolean {
    val usb = usb(context)
    val device = usb.deviceList[reader.id] ?: return false
    if (usb.hasPermission(device)) return true
    return suspendCancellableCoroutine { continuation ->
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    received: Context,
                    intent: Intent,
                ) {
                    context.unregisterReceiver(this)
                    // The PendingIntent is immutable, so the system cannot add the
                    // answer to it; UsbManager has it.
                    continuation.resume(usb.hasPermission(device))
                }
            }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED)
        continuation.invokeOnCancellation { context.unregisterReceiver(receiver) }
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        usb.requestPermission(device, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    }
}

private const val ACTION_USB_PERMISSION = "no.cyberfish.dekoi.USB_PERMISSION"

// Bulk-only transport class request "Get Max LUN" (USB MSC BOT 1.0, 3.2),
// whose answer is 0 to 15.
private const val GET_MAX_LUN_TYPE = 0xA1
private const val GET_MAX_LUN = 0xFE
private const val MAX_LUN = 15

/**
 * Opens the card in [reader], which the owner allowed. Throws [WriteFailed]
 * when there is no card, or more than one: with several cards it would not
 * be clear which one the owner confirmed.
 */
fun openCard(
    context: Context,
    reader: Reader,
): OpenCard {
    val usb = usb(context)
    val device = usb.deviceList[reader.id] ?: throw WriteFailed(WriteFailure.CARD_ERROR)
    val storage = device.massStorage() ?: throw WriteFailed(WriteFailure.CARD_ERROR)
    val bulk = (0 until storage.endpointCount).map(storage::getEndpoint).filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
    val output = bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: throw WriteFailed(WriteFailure.CARD_ERROR)
    val input = bulk.singleOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: throw WriteFailed(WriteFailure.CARD_ERROR)
    val connection = onCard { UsbCommunicationFactory.createUsbCommunication(usb, device, storage, output, input) }
    var opened: OpenCard? = null
    try {
        val maxLun = ByteArray(1)
        // A reader with one slot may refuse the request; then maxLun stays 0.
        onCard { connection.controlTransfer(GET_MAX_LUN_TYPE, GET_MAX_LUN, 0, storage.id, maxLun, 1) }
        val luns = maxLun[0].toInt()
        if (luns !in 0..MAX_LUN) throw WriteFailed(WriteFailure.CARD_ERROR)
        val cards =
            (0..luns).mapNotNull { lun ->
                onCard { inserted(BlockDeviceDriverFactory.createBlockDevice(connection, lun.toByte())) }
            }
        val card = cards.singleOrNull() ?: throw WriteFailed(if (cards.isEmpty()) WriteFailure.NO_CARD else WriteFailure.SEVERAL_CARDS)
        opened = OpenCard(reader.name, card, connection)
        return opened
    } finally {
        if (opened == null) connection.close()
    }
}

/** The card in [device], or null when its slot is empty. */
private fun inserted(device: BlockDeviceDriver): BlockDeviceDriver? =
    try {
        initialise(device)
        device
    } catch (_: MediaNotInserted) {
        null
    }

// A reader reports a card put in since its last command once, as unit
// attention; libaums 0.10.0 does not retry it. A second one is an error.
private fun initialise(device: BlockDeviceDriver) =
    try {
        device.init()
    } catch (_: UnitAttention) {
        device.init()
    }

/** A text file from the card's boot partition, such as cmdline.txt. */
fun readBootFile(
    card: BlockDeviceDriver,
    name: String,
): String = onCard { bootPartition(card).rootDirectory.search(name)?.let(::readFile) } ?: throw WriteFailed(WriteFailure.NOT_VERIFIED)

/**
 * Writes [files] into the card's boot partition and reads them back. Runs
 * only after the image is verified, so the partition table and the FAT32
 * file system are Raspberry Pi OS's own.
 */
fun writeBootFiles(
    card: BlockDeviceDriver,
    files: Map<String, String>,
) = onCard {
    val root = bootPartition(card).rootDirectory
    for ((name, content) in files) {
        val file = root.search(name) ?: root.createFile(name)
        val bytes = content.toByteArray(Charsets.UTF_8)
        file.length = bytes.size.toLong()
        file.write(0, ByteBuffer.wrap(bytes))
        file.close()
    }
    // A fresh file system instance reads what reached the card, not a cache.
    val check = bootPartition(card).rootDirectory
    for ((name, content) in files) {
        val written = check.search(name)?.let(::readFile)
        if (written != content) throw WriteFailed(WriteFailure.NOT_VERIFIED)
    }
}

private fun readFile(file: UsbFile): String {
    val buffer = ByteBuffer.allocate(file.length.toInt())
    file.read(0, buffer)
    return String(buffer.array(), Charsets.UTF_8)
}

private fun bootPartition(card: BlockDeviceDriver): FileSystem {
    val sector = ByteBuffer.allocate(card.blockSize)
    card.read(0, sector)
    val boot = MasterBootRecord.read(sector)?.partitionTableEntries?.firstOrNull()
    if (boot?.partitionType != PartitionTypes.FAT32) throw WriteFailed(WriteFailure.NOT_VERIFIED)
    return Partition.createPartition(boot, card)?.fileSystem ?: throw WriteFailed(WriteFailure.NOT_VERIFIED)
}
