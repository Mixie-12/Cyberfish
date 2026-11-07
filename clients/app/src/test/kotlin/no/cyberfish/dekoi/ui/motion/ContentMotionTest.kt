package no.cyberfish.dekoi.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ContentMotionTest {
    @Test
    fun `a status swaps in place without moving when animations are off`() {
        val change = statusChange(reduceMotion = true)
        assertEquals(EnterTransition.None, change.targetContentEnter)
        assertEquals(ExitTransition.None, change.initialContentExit)
        assertNull(change.sizeTransform)
    }

    @Test
    fun `a status fades through as tabs do`() {
        val change = statusChange(reduceMotion = false)
        assertEquals(fadeThroughIn(gesture = false), change.targetContentEnter)
        assertEquals(fadeThroughOut(gesture = false), change.initialContentExit)
        assertNotNull(change.sizeTransform)
    }
}
