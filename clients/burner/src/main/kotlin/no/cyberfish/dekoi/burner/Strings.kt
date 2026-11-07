package no.cyberfish.dekoi.burner

import java.util.Locale
import java.util.ResourceBundle

/** Bokmål on a Norwegian system, whether it says nb, no or nn (there is no Nynorsk text); English otherwise. */
internal fun language(system: Locale): Locale = if (system.language in norwegian) Locale.forLanguageTag("nb") else Locale.ENGLISH

private val norwegian = setOf("nb", "no", "nn")

/**
 * strings.properties in English, which is also what a missing Bokmål text
 * falls back to, and strings_nb.properties in Bokmål. The system locale never
 * takes part in the lookup, so the language is the one [language] chose.
 */
internal fun strings(language: Locale): ResourceBundle =
    ResourceBundle.getBundle(
        "no.cyberfish.dekoi.burner.strings",
        language,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )

// The Windows display language, which can differ from the regional format.
internal val uiLocale: Locale = language(Locale.getDefault(Locale.Category.DISPLAY))

private val uiStrings = strings(uiLocale)

/** The text for [key] in the window's language, with [args] put in. */
internal fun text(
    key: String,
    vararg args: Any,
): String = uiStrings.getString(key).format(uiLocale, *args)
