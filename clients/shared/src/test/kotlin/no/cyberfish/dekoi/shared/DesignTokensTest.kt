package no.cyberfish.dekoi.shared

import kotlin.math.pow
import kotlin.test.Test

// WCAG 2.2 contrast: text needs 4.5:1 against every background it sits on.
class DesignTokensTest {
    private val backgrounds = listOf(DesignTokens.BACKGROUND, DesignTokens.NAVY)
    private val tones = listOf(DesignTokens.CRITICAL, DesignTokens.WARNING, DesignTokens.INFO, DesignTokens.SAFE)

    @Test
    fun `text colours read on the background and on navy`() {
        val text = listOf(DesignTokens.WHITE, DesignTokens.TEXT, DesignTokens.MID_GREY, DesignTokens.BLUE, DesignTokens.OFFLINE)
        for (color in text) {
            for (background in backgrounds) assertReadable(color, background)
        }
    }

    @Test
    fun `severity colours read on the background, on navy and on their container`() {
        for (tone in tones) {
            for (background in backgrounds + tone.container) assertReadable(tone.color, background)
            assertReadable(tone.onContainer, tone.container)
        }
    }

    private fun assertReadable(
        color: Long,
        background: Long,
    ) {
        val ratio = contrast(color, background)
        assert(ratio >= 4.5) { "%08X on %08X is %.2f:1".format(color, background, ratio) }
    }

    private fun contrast(
        a: Long,
        b: Long,
    ): Double {
        val (light, dark) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (light + 0.05) / (dark + 0.05)
    }

    private fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = (argb shr shift and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }
}
