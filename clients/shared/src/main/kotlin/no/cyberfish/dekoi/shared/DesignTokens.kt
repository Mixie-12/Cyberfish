package no.cyberfish.dekoi.shared

/**
 * The CyberFish palette as ARGB values (UX/UI/CyberFish-aesthetic.md, ADR
 * 0020), shared by the app and DeKoi Setup so both look like one product.
 * Dark only. Each app wraps them in its own Compose Color type.
 */
object DesignTokens {
    /** Page background. */
    const val BACKGROUND = 0xFF060A14

    /** Navy horizon: glows, gradients, fog, and surfaces that float. */
    const val NAVY = 0xFF0B1636

    /** CyberFish blue: lines, glows, labels and highlights. Never a large fill. */
    const val BLUE = 0xFF2EA8FF

    /** Headings and the wordmark. */
    const val WHITE = 0xFFF2F4FA

    /** Body text. */
    const val TEXT = 0xFFC9D1E3

    /** Secondary text. */
    const val MID_GREY = 0xFF8A94AB

    /** Decoration only: at 2.6:1 on the background it is too faint for text. */
    const val DIM_GREY = 0xFF4A5368

    /** Dividers and thin lines. */
    const val HAIRLINE = 0xFF1B2234

    /** A DeKoi or device that stopped checking in. */
    const val OFFLINE = MID_GREY

    /**
     * A severity colour, a quiet background for it (the colour at 14 % over
     * the background) and a readable text colour on that background.
     */
    class Tone(
        val color: Long,
        val container: Long,
        val onContainer: Long,
    )

    // The only colours besides the palette. Each reads at 4.5:1 or more on
    // the background, on navy and on its own container (DesignTokensTest).
    val CRITICAL = Tone(0xFFFF6B66, 0xFF29181F, 0xFFFFB4B0)
    val WARNING = Tone(0xFFFFB547, 0xFF29221B, 0xFFFFD99E)
    val INFO = Tone(0xFFA9B8D4, 0xFF1D222F, 0xFFD5DDEC)
    val SAFE = Tone(0xFF4ED18B, 0xFF102625, 0xFFA6EBC2)
}
