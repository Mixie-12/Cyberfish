package no.cyberfish.dekoi.burner

import java.nio.file.Files
import java.nio.file.Path
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
}
