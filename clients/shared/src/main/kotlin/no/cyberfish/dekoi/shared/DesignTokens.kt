package no.cyberfish.dekoi.shared

/**
 * The DeKoi palette as ARGB values, shared by the app and DeKoi Setup so both
 * look like one product. Each app wraps them in its own Compose Color type.
 *
 * Restrained on purpose: neutral surfaces, one accent, and one calm colour per
 * protection state.
 */
object DesignTokens {
    const val ACCENT = 0xFFE8743B

    const val PROTECTED = 0xFF2E9E6B
    const val ATTENTION = 0xFFC77A12
    const val OFFLINE = 0xFF8A8F98
    const val SETTING_UP = 0xFF3B7DD8

    object Light {
        const val BACKGROUND = 0xFFF5F5F7
        const val SURFACE = 0xFFFFFFFF
        const val TEXT = 0xFF1D1D1F
        const val SECONDARY_TEXT = 0xFF6E6E73
        const val DIVIDER = 0xFFE5E5EA
    }

    object Dark {
        const val BACKGROUND = 0xFF000000
        const val SURFACE = 0xFF1C1C1E
        const val TEXT = 0xFFF5F5F7
        const val SECONDARY_TEXT = 0xFF98989D
        const val DIVIDER = 0xFF38383A
    }
}
