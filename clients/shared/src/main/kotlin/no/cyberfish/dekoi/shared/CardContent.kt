package no.cyberfish.dekoi.shared

/**
 * What goes on a DeKoi card, from the repository's `device/` directory at
 * build time (see build.gradle.kts): the pinned image, the pinned installer
 * bundle and the files the cloud-init template includes.
 */
object CardContent {
    val image: Image = pinnedImage
    val installer: Installer = pinnedInstaller

    fun read(path: String): String {
        val stream = checkNotNull(CardContent::class.java.getResourceAsStream("/device/$path")) { "device/$path is not bundled" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val userDataTemplate: String get() = read("cloud-init/user-data.yaml")

    /** The card's user-data for [code], with every host file included. */
    fun userData(code: ClaimCode): String = CloudInit.userData(userDataTemplate, ::read, code)
}

/** A Raspberry Pi OS image as published in Raspberry Pi's OS list. */
data class Image(
    val url: String,
    /** SHA-256 of the .img.xz file as downloaded. */
    val downloadSha256: String,
    val downloadSize: Long,
    /** SHA-256 of the uncompressed image; Imager checks this one for a single .img.xz. */
    val extractSha256: String,
    val extractSize: Long,
) {
    init {
        require(url.startsWith("https://")) { "The image must be downloaded over HTTPS" }
        require(downloadSha256.matches(SHA256) && extractSha256.matches(SHA256)) { "Not a SHA-256" }
        require(downloadSize > 0 && extractSize > 0) { "Sizes must be positive" }
        // Cards have 512-byte sectors and the image is written sector by sector.
        require(extractSize % SECTOR == 0L) { "The image is not a whole number of sectors" }
    }

    companion object {
        const val SECTOR = 512
    }
}

/**
 * The installer bundle the phone puts on a card (ADR 0031): a zip, pushed
 * to ghcr.io as the one layer of an OCI artifact and fetched by digest.
 */
data class Installer(
    /** The repository on ghcr.io, such as mixie-12/dekoi-installer. */
    val repository: String,
    /** SHA-256 of the zip, which is also its blob digest. */
    val sha256: String,
    val size: Long,
) {
    val digest: String get() = "sha256:$sha256"

    init {
        require(sha256.matches(SHA256)) { "Not a SHA-256" }
        require(size > 0) { "The size must be positive" }
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")
