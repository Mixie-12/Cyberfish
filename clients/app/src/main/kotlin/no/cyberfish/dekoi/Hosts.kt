package no.cyberfish.dekoi

/** The longest name and room the backend stores for a host, in characters (ADR 0013). */
const val LABEL_MAX = 40
const val ROOM_MAX = 32

/**
 * What the owner typed, as the backend accepts it: no control characters,
 * at most [max] characters, without cutting a character in half.
 */
fun ownerText(
    typed: String,
    max: Int,
): String {
    val kept = typed.filterNot { it.isISOControl() }
    val end = kept.offsetByCodePoints(0, minOf(max, kept.codePointCount(0, kept.length)))
    return kept.substring(0, end)
}

/** [ownerText] when saving: surrounding spaces dropped, and nothing left means none. */
fun savedOwnerText(
    typed: String,
    max: Int,
): String? = ownerText(typed, max).trim().ifEmpty { null }
