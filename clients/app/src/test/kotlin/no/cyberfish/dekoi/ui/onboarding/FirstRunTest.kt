package no.cyberfish.dekoi.ui.onboarding

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the tour is shown the first time only`() {
        val marker = tourMarker(folder.root)
        assertTrue(firstTime(marker))
        assertFalse(firstTime(marker))
        assertFalse(firstTime(marker))
    }

    @Test
    fun `an owner who saw the first tour sees this one once`() {
        assertTrue(File(folder.root, "onboarding_seen").createNewFile())
        assertTrue(firstTime(tourMarker(folder.root)))
        assertFalse(firstTime(tourMarker(folder.root)))
    }

    @Test
    fun `storage that cannot hold the marker skips the tour`() {
        val marker = tourMarker(File(folder.root, "missing"))
        assertFalse(firstTime(marker))
        assertFalse(firstTime(marker))
    }
}
