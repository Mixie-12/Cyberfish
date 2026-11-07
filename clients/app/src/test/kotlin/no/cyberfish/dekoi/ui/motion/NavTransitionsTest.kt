package no.cyberfish.dekoi.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NavTransitionsTest {
    private val transitions = NavTransitions(distance = 90, reduceMotion = false) { _, _ -> false }

    @Test
    fun `nothing moves when animations are off, not even between tabs`() {
        assertEquals(ScreenChange.INSTANT, screenChange(reduceMotion = true, changesTab = false))
        assertEquals(ScreenChange.INSTANT, screenChange(reduceMotion = true, changesTab = true))
    }

    @Test
    fun `tabs fade through and pushes slide`() {
        assertEquals(ScreenChange.FADE_THROUGH, screenChange(reduceMotion = false, changesTab = true))
        assertEquals(ScreenChange.SHARED_AXIS, screenChange(reduceMotion = false, changesTab = false))
    }

    @Test
    fun `an instant change has no enter or exit in any direction`() {
        Direction.entries.forEach {
            assertEquals(EnterTransition.None, transitions.enter(ScreenChange.INSTANT, it))
            assertEquals(ExitTransition.None, transitions.exit(ScreenChange.INSTANT, it))
        }
    }

    // Slides hold a lambda, so only the fade through compares by value.
    @Test
    fun `tabs fade through the same way both ways, and a back gesture without a delay`() {
        val change = ScreenChange.FADE_THROUGH
        assertEquals(transitions.enter(change, Direction.FORWARD), transitions.enter(change, Direction.BACK))
        assertEquals(transitions.exit(change, Direction.FORWARD), transitions.exit(change, Direction.BACK))
        assertNotEquals(transitions.enter(change, Direction.BACK), transitions.enter(change, Direction.BACK_GESTURE))
        assertNotEquals(transitions.exit(change, Direction.BACK), transitions.exit(change, Direction.BACK_GESTURE))
    }
}
