package no.cyberfish.dekoi

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import no.cyberfish.dekoi.shared.Installer
import no.cyberfish.dekoi.shared.Network
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/** Bytes moved by [copy], and their SHA-256. */
data class Copied(
    val bytes: Long,
    val sha256: String,
)

private const val CHUNK = 1024 * 1024

/**
 * Copies [input] to [output] until the input ends or more than [limit]
 * bytes are copied, and hashes what it copies.
 */
fun copy(
    input: InputStream,
    output: OutputStream,
    limit: Long = Long.MAX_VALUE,
    onCopied: (Long) -> Unit = {},
): Copied {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(CHUNK)
    var copied = 0L
    while (copied <= limit) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        copied += read
        onCopied(copied)
    }
    return Copied(copied, digest.digest().toHexString())
}

/** Where a copy goes when only its hash is wanted. */
object Discard : OutputStream() {
    override fun write(byte: Int) = Unit

    override fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = Unit
}

/**
 * Copies a download of [installer] from [input] to [output] and throws
 * [WriteFailed] unless it has exactly the pinned size and SHA-256. Reads
 * at most a chunk beyond the pinned size.
 */
fun receive(
    input: InputStream,
    output: OutputStream,
    installer: Installer,
    onProgress: (Long) -> Unit,
) {
    val copied = copy(input, output, installer.size, onProgress)
    if (copied != Copied(installer.size, installer.sha256)) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
}

private const val REGISTRY = "https://ghcr.io"
private const val TIMEOUT_MS = 60_000

// ghcr.io answers a blob request with one redirect to its storage host.
private const val MAX_REDIRECTS = 3
private const val TOKEN_BYTES = 64 * 1024L
private val REDIRECTS = setOf(301, 302, 303, 307, 308)

/**
 * Downloads [installer] from ghcr.io into [file] and throws [WriteFailed]
 * unless it matches the pin. An anonymous pull as the OCI distribution API
 * defines it: a token for the repository, then the blob by its digest.
 */
fun download(
    installer: Installer,
    file: File,
    onProgress: (Long) -> Unit,
) = failingWith(WriteFailure.NETWORK) {
    val connection = blob(installer, pullToken(installer.repository))
    try {
        connection.body().use { input ->
            val length = connection.contentLengthLong
            if (length != -1L && length != installer.size) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
            file.outputStream().use { receive(input, it, installer, onProgress) }
        }
    } finally {
        connection.disconnect()
    }
}

private fun pullToken(repository: String): String {
    val connection = https(URL("$REGISTRY/token?scope=repository:$repository:pull"))
    try {
        val body = ByteArrayOutputStream()
        if (connection.body().use { copy(it, body, TOKEN_BYTES) }.bytes > TOKEN_BYTES) throw IOException("The token answer is too long")
        val token =
            Json
                .parseToJsonElement(body.toByteArray().decodeToString())
                .jsonObject["token"]
                ?.jsonPrimitive
                ?.content
        // It goes into a header, so nothing but visible ASCII.
        if (token.isNullOrEmpty() || token.any { it !in '!'..'~' }) throw IOException("No usable token")
        return token
    } finally {
        connection.disconnect()
    }
}

/** The blob's answer, after its redirects. The token goes to ghcr.io only, never to the host it redirects to. */
private fun blob(
    installer: Installer,
    token: String,
): HttpsURLConnection {
    var connection = https(URL("$REGISTRY/v2/${installer.repository}/blobs/${installer.digest}"))
    connection.setRequestProperty("Authorization", "Bearer $token")
    repeat(MAX_REDIRECTS) {
        if (connection.responseCode !in REDIRECTS) return connection
        val location = connection.getHeaderField("Location") ?: throw IOException("A redirect without a location")
        val next = URL(connection.url, location)
        connection.disconnect()
        connection = https(next)
    }
    connection.disconnect()
    throw IOException("Too many redirects")
}

/**
 * A request that follows no redirect by itself, so that the token goes
 * where [blob] sends it and nowhere else. HTTPS only, with no transparent
 * decompression, so the hash covers the file as published.
 */
private fun https(url: URL): HttpsURLConnection {
    val connection = url.openConnection() as? HttpsURLConnection ?: throw IOException("Not HTTPS: ${url.protocol}")
    connection.instanceFollowRedirects = false
    connection.connectTimeout = TIMEOUT_MS
    connection.readTimeout = TIMEOUT_MS
    connection.setRequestProperty("Accept-Encoding", "identity")
    return connection
}

private fun HttpsURLConnection.body(): InputStream {
    if (responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP $responseCode from ${url.host}")
    return inputStream
}

/** The files the installer carries over to Raspberry Pi OS (ADR 0031), by path on the card. */
fun dekoiFiles(
    code: ClaimCode,
    instanceId: String,
): Map<String, String> =
    mapOf(
        "dekoi/user-data" to CardContent.userData(code),
        "dekoi/network-config" to CloudInit.networkConfig(Network.Cable),
        "dekoi/meta-data" to CloudInit.metaData(instanceId),
    )

// What FAT and Android's storage provider refuse in a name (FileUtils.isValidFatFilenameChar).
private const val NOT_IN_NAMES = "\"*/:<>?\\|"
private const val NAME_BYTES = 255

private fun fatName(name: String): Boolean =
    name.isNotEmpty() &&
        name != "." &&
        name != ".." &&
        name.toByteArray(Charsets.UTF_8).size <= NAME_BYTES &&
        name.none { it.isISOControl() || it in NOT_IN_NAMES }

/**
 * The first of [paths] that cannot be written to the card as it is, or
 * null when all can. A path is relative, "/" separates its names, and no
 * name may climb out of the card, be empty, or hold what FAT does not
 * allow. No two paths may name the same file, and FAT does not tell
 * upper from lower case.
 */
fun refusedPath(paths: List<String>): String? {
    val seen = HashSet<String>()
    return paths.firstOrNull { path -> !path.split('/').all(::fatName) || !seen.add(path.lowercase(Locale.ROOT)) }
}
