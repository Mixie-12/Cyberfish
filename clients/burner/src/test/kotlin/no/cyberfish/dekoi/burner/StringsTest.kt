package no.cyberfish.dekoi.burner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.Properties
import kotlin.io.path.extension
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StringsTest {
    private fun load(file: String): Map<String, String> {
        val properties = Properties()
        // Properties.load(InputStream) would read Latin-1; ResourceBundle reads UTF-8.
        checkNotNull(javaClass.getResourceAsStream(file)).reader(Charsets.UTF_8).use(properties::load)
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    private val english = load("strings.properties")

    // Gradle runs the tests in the burner directory.
    private val sources: String =
        Files.walk(Path.of("src/main/kotlin")).use { paths ->
            paths.toList().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        }

    @Test
    fun `every text the window asks for exists, and every text is asked for`() {
        val asked = Regex("""text\("(\w+)"""").findAll(sources).map { it.groupValues[1] }.toSet()
        val literals = Regex(""""(\w+)"""").findAll(sources).map { it.groupValues[1] }.toSet()

        assertTrue(asked.isNotEmpty())
        assertEquals(emptySet(), asked - english.keys, "missing")
        assertEquals(emptySet(), english.keys - literals, "unused")
    }

    // A placeholder the other language lacks would fail only when that text is shown.
    private fun placeholders(text: String): List<String> =
        Regex("%[^a-zA-Z%]*[a-zA-Z%]")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    @Test
    fun `every text exists in English and Bokmål, with the same placeholders`() {
        val bokmal = load("strings_nb.properties")

        assertEquals(english.keys.sorted(), bokmal.keys.sorted())
        for ((key, text) in english) assertEquals(placeholders(text), placeholders(bokmal.getValue(key)), key)
    }

    @Test
    fun `a Norwegian system gets Bokmål and every other system English`() {
        for (tag in listOf("nb-NO", "no-NO", "nn-NO", "nb")) {
            assertEquals("Tilbake", strings(language(Locale.forLanguageTag(tag))).getString("action_back"), tag)
        }
        for (tag in listOf("en-US", "en-GB", "sv-SE", "da-DK", "de-DE", "und")) {
            assertEquals("Back", strings(language(Locale.forLanguageTag(tag))).getString("action_back"), tag)
        }
        // Read as UTF-8, as the file is written.
        assertEquals("Wi-Fi «%s»", strings(Locale.forLanguageTag("nb")).getString("erase_wifi"))
    }
}
