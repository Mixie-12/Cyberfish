package no.cyberfish.dekoi.ui.motion

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

class HapticsTest {
    private class Recorder : HapticFeedback {
        val played = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            played += hapticFeedbackType
        }
    }

    private fun Haptics.playAll() {
        toggle(on = true)
        toggle(on = false)
        confirm()
        critical()
        arrival()
    }

    @Test
    fun `each moment plays its platform feedback`() {
        val recorder = Recorder()
        Haptics(recorder, enabled = true).playAll()
        val expected =
            listOf(
                HapticFeedbackType.ToggleOn,
                HapticFeedbackType.ToggleOff,
                HapticFeedbackType.Confirm,
                HapticFeedbackType.LongPress,
                HapticFeedbackType.ContextClick,
            )
        assertEquals(expected, recorder.played)
    }

    @Test
    fun `nothing plays when animations are off`() {
        val recorder = Recorder()
        Haptics(recorder, enabled = false).playAll()
        assertEquals(emptyList(), recorder.played)
    }
}
