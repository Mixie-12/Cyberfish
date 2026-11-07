package no.cyberfish.dekoi.shared

/**
 * The DeKoi palette as ARGB values (docs/design.md, "Tokens"), shared by the
 * app and DeKoi Setup so both look like one product. Each app wraps them in
 * its own Compose Color type.
 */
object DesignTokens {
    /** Koi orange, for the logo and onboarding art only; interactive elements use tint (ADR 0011). */
    const val BRAND = 0xFFE8743B

    /** A colour with a quiet background for it and a readable text colour on that background. */
    class Tone(
        val color: Long,
        val container: Long,
        val onContainer: Long,
    )

    class Palette(
        val background: Long,
        val surface: Long,
        /** Floating elements. Light mode gives them a shadow; dark mode, where shadows do not show, this border. */
        val raised: Long,
        val raisedBorder: Long?,
        val text: Long,
        val textSecondary: Long,
        /** Decoration only: too faint for text that must be read. */
        val textTertiary: Long,
        val separator: Long,
        val tint: Tone,
        val onTint: Long,
        val critical: Tone,
        val warning: Tone,
        val info: Tone,
        val safe: Tone,
        val offline: Long,
    )

    val Light =
        Palette(
            background = 0xFFF2F3F5,
            surface = 0xFFFFFFFF,
            raised = 0xFFFFFFFF,
            raisedBorder = null,
            text = 0xFF15171C,
            textSecondary = 0xFF5B616D,
            textTertiary = 0xFF8A909B,
            separator = 0xFFDADDE2,
            tint = Tone(0xFF0A63D1, 0xFFE5EFFC, 0xFF0A4FA8),
            onTint = 0xFFFFFFFF,
            critical = Tone(0xFFC4282B, 0xFFFDECEC, 0xFF8E1B1E),
            warning = Tone(0xFFA85A00, 0xFFFFF2DD, 0xFF7A4100),
            info = Tone(0xFF4E5D73, 0xFFEBEFF5, 0xFF36445A),
            safe = Tone(0xFF1B7F47, 0xFFE6F4EC, 0xFF145C34),
            offline = 0xFF6B7280,
        )

    val Dark =
        Palette(
            background = 0xFF0E1013,
            surface = 0xFF181A1F,
            raised = 0xFF22252B,
            raisedBorder = 0xFF2E323A,
            text = 0xFFF3F4F6,
            textSecondary = 0xFFA3A9B4,
            textTertiary = 0xFF737A86,
            separator = 0xFF2E323A,
            tint = Tone(0xFF5AA2FF, 0xFF16273F, 0xFFB8D6FF),
            onTint = 0xFF0E1013,
            critical = Tone(0xFFFF6B66, 0xFF3A1A1B, 0xFFFFB4B0),
            warning = Tone(0xFFFFB34D, 0xFF3A2A12, 0xFFFFD49A),
            info = Tone(0xFF9EACC0, 0xFF232A35, 0xFFC9D3E1),
            safe = Tone(0xFF4ED18B, 0xFF15301F, 0xFFA6EBC2),
            offline = 0xFF9AA1AD,
        )
}
