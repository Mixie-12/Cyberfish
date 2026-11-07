package no.cyberfish.dekoi.shared

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The code that pairs a DeKoi with an account (docs/architecture.md, "Pairing").
 *
 * 26 Crockford base32 characters in canonical upper case, 130 random bits.
 * The backend stores only its hash and accepts each code once per side.
 */
@JvmInline
value class ClaimCode private constructor(
    val value: String,
) {
    /** What the QR code holds. */
    val qrPayload: String get() = QR_PREFIX + value

    /** The code in groups of four, for reading and typing. */
    val grouped: String get() = value.chunked(4).joinToString("-")

    /**
     * A short, stable name derived from the code, for example "DeKoi 7F3K".
     * DeKoi Setup shows it next to the QR code and the app uses it as the
     * device's first name, so both screens show the same name.
     */
    val shortName: String
        get() {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))
            val bits =
                (digest[0].toInt() and 0xFF shl 12) or (digest[1].toInt() and 0xFF shl 4) or
                    (digest[2].toInt() and 0xFF ushr 4)
            val suffix = (3 downTo 0).map { ALPHABET[(bits ushr (it * 5)) and 31] }.joinToString("")
            return "DeKoi $suffix"
        }

    companion object {
        const val QR_PREFIX = "DEKOI1:"
        const val LENGTH = 26
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

        fun generate(random: SecureRandom = SecureRandom()): ClaimCode =
            ClaimCode(String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }))

        /**
         * Reads a scanned QR payload or a typed code. Accepts lower case,
         * spaces and dashes, and the Crockford look-alikes I, L (one) and O
         * (zero). Returns null for anything else.
         */
        fun parse(input: String): ClaimCode? {
            val code =
                input
                    .trim()
                    .removePrefix(QR_PREFIX)
                    .uppercase()
                    .filterNot { it == '-' || it.isWhitespace() }
                    .map {
                        when (it) {
                            'I', 'L' -> '1'
                            'O' -> '0'
                            else -> it
                        }
                    }.joinToString("")
            return if (code.length == LENGTH && code.all { it in ALPHABET }) ClaimCode(code) else null
        }
    }
}
