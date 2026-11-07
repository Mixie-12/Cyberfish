package no.cyberfish.dekoi.burner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The files from the repository's `device/` directory, bundled at build time
 * (see build.gradle.kts). They are the single source of truth for what goes
 * on a card.
 */
object Device {
    fun read(path: String): String {
        val stream = checkNotNull(Device::class.java.getResourceAsStream("/device/$path")) { "device/$path is not bundled" }
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    val userDataTemplate: String get() = read("cloud-init/user-data.yaml")

    /** The pinned Raspberry Pi OS image from device/image.json. */
    val image: Image by lazy {
        val json = Json.parseToJsonElement(read("image.json")).jsonObject
        Image(
            url = json.getValue("url").jsonPrimitive.content,
            extractSha256 = json.getValue("extract_sha256").jsonPrimitive.content,
        )
    }
}

data class Image(
    val url: String,
    /** SHA-256 of the uncompressed image; Imager checks this one for a single .img.xz. */
    val extractSha256: String,
) {
    init {
        require(url.startsWith("https://")) { "The image must be downloaded over HTTPS" }
        require(extractSha256.matches(Regex("[0-9a-f]{64}"))) { "Not a SHA-256: $extractSha256" }
    }
}
