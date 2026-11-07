package no.cyberfish.dekoi.burner

import no.cyberfish.dekoi.shared.ClaimCode
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** How the DeKoi reaches the home network. */
sealed interface Network {
    data object Cable : Network

    /**
     * A WPA2 or WPA3 personal network. Construction fails for values that
     * cannot be a real network, so nothing invalid reaches the card.
     */
    data class WiFi(
        val ssid: String,
        val password: String,
        val country: String,
    ) : Network {
        init {
            require(ssid.toByteArray(Charsets.UTF_8).size in 1..32) { "The network name must be 1 to 32 bytes" }
            require(ssid.codePoints().allMatch(::isPrintable)) { "The network name contains a control character" }
            // IEEE 802.11i: a passphrase is 8 to 63 printable ASCII characters.
            require(password.length in 8..63 && password.all { it in ' '..'~' }) {
                "The password must be 8 to 63 letters, digits, spaces or symbols"
            }
            require(country.length == 2 && country.all { it in 'A'..'Z' }) { "The country must be a two-letter code" }
        }
    }
}

/**
 * Renders the cloud-init files DeKoi Setup gives to Raspberry Pi Imager.
 * Pure: the caller supplies the template and the files it includes.
 */
object CloudInit {
    private val include = Regex("""^( +)content: !file (\S+)$""")

    /**
     * Replaces each `content: !file PATH` line of [template] by the file
     * [readFile] returns for PATH, as a YAML literal block, and the claim code
     * placeholder by [claimCode].
     */
    fun userData(
        template: String,
        readFile: (String) -> String,
        claimCode: ClaimCode,
    ): String {
        check('\r' !in template) { "The template has CR line endings" }
        check(template.contains(CLAIM_CODE_PLACEHOLDER)) { "The template has no claim code placeholder" }
        return template.lines().joinToString("\n") { line ->
            val match = include.matchEntire(line)
            if (match == null) {
                val comment = line.trimStart().startsWith("#")
                check(comment || "!file" !in line) { "Include not in the form \"content: !file PATH\": $line" }
                // The code is 26 Crockford base32 characters, so it needs no quoting.
                if (comment) line else line.replace(CLAIM_CODE_PLACEHOLDER, claimCode.value)
            } else {
                val (indent, path) = match.destructured
                literalBlock(indent, path, readFile(path))
            }
        }
    }

    /**
     * netplan version 2, in the shape Raspberry Pi Imager 2.0 writes: the
     * cable always works, and Wi-Fi is added when chosen. The Wi-Fi password
     * is stored as the derived WPA key, as Imager does, so the passphrase
     * itself is not on the card.
     */
    fun networkConfig(network: Network): String {
        val cable =
            """
            |network:
            |  version: 2
            |  ethernets:
            |    eth0:
            |      dhcp4: true
            |      dhcp6: true
            |      optional: true
            |
            """.trimMargin()
        return when (network) {
            Network.Cable -> {
                cable
            }

            is Network.WiFi -> {
                cable +
                    """
                    |  wifis:
                    |    wlan0:
                    |      dhcp4: true
                    |      regulatory-domain: "${network.country}"
                    |      access-points:
                    |        ${quoted(network.ssid)}:
                    |          password: "${wpaKey(network.ssid, network.password)}"
                    |      optional: true
                    |
                    """.trimMargin()
            }
        }
    }

    /**
     * A YAML double-quoted scalar. Only backslash and double quote need an
     * escape there; [Network.WiFi] already rejects every character that
     * could end the line or that YAML treats as a line break.
     */
    fun quoted(value: String): String {
        require(value.codePoints().allMatch(::isPrintable)) { "Control characters cannot be written" }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    /** The WPA pre-shared key: PBKDF2-HMAC-SHA1, SSID as salt, 4096 rounds, 256 bits. */
    fun wpaKey(
        ssid: String,
        password: String,
    ): String {
        val spec = PBEKeySpec(password.toCharArray(), ssid.toByteArray(Charsets.UTF_8), 4096, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        return key.joinToString("") { "%02x".format(it) }
    }

    private fun literalBlock(
        indent: String,
        path: String,
        content: String,
    ): String {
        // These limits keep a plain literal block exact: it ends the content
        // with one newline, and a leading space would need an indentation
        // indicator.
        require(content.endsWith("\n") && !content.endsWith("\n\n")) { "$path must end with exactly one newline" }
        require(!content.startsWith(" ") && !content.startsWith("\n")) { "$path must not start with a blank" }
        require('\r' !in content) { "$path has CR line endings" }
        val body =
            content.removeSuffix("\n").lines().joinToString("\n") { line ->
                if (line.isEmpty()) "" else "$indent  $line"
            }
        return "${indent}content: |\n$body"
    }

    private const val CLAIM_CODE_PLACEHOLDER = "__CLAIM_CODE__"
}

// What PyYAML, which cloud-init uses, accepts in a double-quoted scalar without
// an escape, minus everything it treats as a line break.
private fun isPrintable(codePoint: Int): Boolean =
    codePoint in 0x20..0x7E ||
        (codePoint in 0xA0..0xFFFD && codePoint !in 0xD800..0xDFFF && codePoint != 0x2028 && codePoint != 0x2029) ||
        codePoint in 0x10000..0x10FFFF
