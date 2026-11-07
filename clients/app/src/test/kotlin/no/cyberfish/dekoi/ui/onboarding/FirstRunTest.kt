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
        val marker = File(folder.root, "onboarding_seen")
        assertTrue(firstTime(marker))
        assertFalse(firstTime(marker))
        assertFalse(firstTime(marker))
    }

    @Test
    fun `storage that cannot hold the marker skips the tour`() {
        val marker = File(folder.root, "missing/onboarding_seen")
        assertFalse(firstTime(marker))
        assertFalse(firstTime(marker))
    }
}
