package no.cyberfish.dekoi

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.activity.result.ActivityResult
import androidx.core.net.toUri
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile

// A volume's description can come from a card reader's USB descriptors; it is shown, so it is bounded.
private const val NAME_LENGTH = 64
private val MOUNTED = setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY)

private fun volumes(context: Context): List<StorageVolume> =
    checkNotNull(context.getSystemService(StorageManager::class.java)).storageVolumes

/**
 * The removable cards Android has mounted. A volume without a UUID is left
 * out: its root folder could not be told from any other folder.
 */
fun removableCards(context: Context): List<Card> =
    volumes(context).filter { it.isRemovable && it.state in MOUNTED }.mapNotNull { volume ->
        val uuid = volume.uuid ?: return@mapNotNull null
        // API 29 has no getDirectory; Android mounts a removable volume at /storage/<UUID>.
        val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) volume.directory else File("/storage", uuid)
        Card(
            uuid,
            volume
                .getDescription(context)
                .filterNot(Char::isISOControl)
                .trim()
                .take(NAME_LENGTH),
            directory?.totalSpace ?: 0,
            volume.state == Environment.MEDIA_MOUNTED_READ_ONLY,
        )
    }

/** Android's picker, opened on [card]; null when the card is gone. */
fun pickerIntent(
    context: Context,
    card: Card,
): Intent? = volumes(context).firstOrNull { it.uuid == card.uuid }?.createOpenDocumentTreeIntent()

/** The root folder of [card] from the picker's [result], or null when the owner gave nothing or another folder. */
fun cardTree(
    result: ActivityResult,
    card: Card,
): String? {
    val tree = result.data?.data?.takeIf { result.resultCode == Activity.RESULT_OK && DocumentsContract.isTreeUri(it) } ?: return null
    return tree.toString().takeIf { isCardRoot(tree.authority, DocumentsContract.getTreeDocumentId(tree), card) }
}

/**
 * Prepares the card the owner confirmed (ADR 0031) and returns the claim
 * code now on it. Nothing on the card changes until the bundle matches its
 * pin; then the card is erased, the bundle and the dekoi/ files are copied,
 * and every file is read back.
 */
fun prepareCard(
    context: Context,
    confirmed: WriteState.Confirm,
    onProgress: (WriteState.Writing) -> Unit,
): ClaimCode {
    val installer = CardContent.installer
    val bundle = File(context.cacheDir, "installer.zip")
    try {
        download(installer, bundle) { onProgress(WriteState.Writing(downloaded = it)) }
        // From here on an error is the card's, unless the bundle itself is damaged.
        return failingWith(WriteFailure.CARD_ERROR) {
            failingWith(WriteFailure.DOWNLOAD_DAMAGED) { ZipFile(bundle) }.use { zip ->
                copyToCard(CardFolder(context.contentResolver, confirmed.tree.toUri()), zip) { written, checked, total ->
                    onProgress(WriteState.Writing(installer.size, written, checked, total))
                }
            }
        }
    } finally {
        bundle.delete()
    }
}

private fun copyToCard(
    card: CardFolder,
    zip: ZipFile,
    onProgress: (written: Long, checked: Long, total: Long) -> Unit,
): ClaimCode {
    val code = ClaimCode.generate()
    val dekoi = dekoiFiles(code, CloudInit.instanceId(System.currentTimeMillis())).mapValues { it.value.toByteArray() }
    val entries = failingWith(WriteFailure.DOWNLOAD_DAMAGED) { zip.entries().toList().filterNot { it.isDirectory } }
    val sources: List<Pair<String, () -> InputStream>> =
        entries.map { entry -> entry.name to { zip.getInputStream(entry) } } +
            dekoi.map { (path, bytes) -> path to { bytes.inputStream() } }
    val refused = refusedPath(sources.map { it.first })
    if (refused != null) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED, IOException("No place on the card for $refused"))
    val total = entries.sumOf { it.size } + dekoi.values.sumOf { it.size.toLong() }
    card.erase()
    var written = 0L
    val files =
        sources.map { (path, open) ->
            val before = written
            open().use { input ->
                card.write(path, input) {
                    written = before + it
                    onProgress(written, 0, total)
                }
            }
        }
    var checked = 0L
    for (file in files) {
        val before = checked
        val back =
            card.read(file.uri) {
                checked = before + it
                onProgress(written, checked, total)
            }
        if (back != file.copied) throw WriteFailed(WriteFailure.NOT_VERIFIED)
    }
    return code
}

/** A file written to the card, and what was written. */
private class Written(
    val uri: Uri,
    val copied: Copied,
)

// With this type the provider keeps a name as given and adds no extension (FileUtils.splitFileName).
private const val FILE_TYPE = "application/octet-stream"

/** The card's root folder through Android's storage provider, as the owner granted it in the picker. */
private class CardFolder(
    private val resolver: ContentResolver,
    private val tree: Uri,
) {
    private val root = DocumentsContract.getTreeDocumentId(tree)

    // By lower-case path: FAT does not tell upper from lower case.
    private val folders = mutableMapOf("" to document(root))

    private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** Deletes everything in the root folder; the provider deletes a folder with what is in it. */
    fun erase() {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
        val cursor =
            resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?: throw IOException("The card's files could not be listed")
        val ids = cursor.use { buildList { while (it.moveToNext()) add(it.getString(0)) } }
        for (id in ids) {
            if (!DocumentsContract.deleteDocument(resolver, document(id))) throw IOException("A file on the card could not be deleted")
        }
    }

    /** Writes [input] to [path], making its folders, and syncs it to the card. */
    fun write(
        path: String,
        input: InputStream,
        onCopied: (Long) -> Unit,
    ): Written {
        val file = create(folder(path.substringBeforeLast('/', "")), FILE_TYPE, path.substringAfterLast('/'))
        val descriptor = resolver.openFileDescriptor(file, "w") ?: throw IOException("$path could not be opened")
        val copied =
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                copy(input, output, onCopied = onCopied).also { output.fd.sync() }
            }
        return Written(file, copied)
    }

    /** Reads [file] back through the provider and hashes it. */
    fun read(
        file: Uri,
        onCopied: (Long) -> Unit,
    ): Copied =
        resolver.openInputStream(file)?.use { copy(it, Discard, onCopied = onCopied) }
            ?: throw IOException("A file on the card could not be read")

    private fun folder(path: String): Uri =
        folders.getOrPut(path.lowercase(Locale.ROOT)) {
            create(folder(path.substringBeforeLast('/', "")), Document.MIME_TYPE_DIR, path.substringAfterLast('/'))
        }

    private fun create(
        parent: Uri,
        type: String,
        name: String,
    ): Uri = DocumentsContract.createDocument(resolver, parent, type, name) ?: throw IOException("$name could not be created")
}
