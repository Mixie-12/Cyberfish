package no.cyberfish.dekoi.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import no.cyberfish.dekoi.ui.LocalReduceMotion

/**
 * Touch feedback. It plays through the view, so the system's touch feedback
 * setting applies; it is also silent when animations are off.
 *
 * ```
 * val haptics = rememberHaptics()
 * InsetRow(..., onClick = { haptics.toggle(!host.trusted); onTrust(host, !host.trusted) })
 * ```
 */
class Haptics internal constructor(
    private val feedback: HapticFeedback,
    private val enabled: Boolean,
) {
    /** A switch, or a row that works as one, turned [on] or off. */
    fun toggle(on: Boolean) = play(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

    /** The owner confirmed an action, such as "I know this device". */
    fun confirm() = play(HapticFeedbackType.Confirm)

    /**
     * A firm click: a critical alert opened. The platform has no constant for
     * an alert; long press is its firm click. Screens use [CriticalAlertHaptic].
     */
    fun critical() = play(HapticFeedbackType.LongPress)

    /**
     * A light tick: an alert arrived while the app is open. The platform has
     * no constant for that either; context click is its light tick.
     */
    fun arrival() = play(HapticFeedbackType.ContextClick)

    private fun play(type: HapticFeedbackType) {
        if (enabled) feedback.performHapticFeedback(type)
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = !LocalReduceMotion.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}

/**
 * Plays [Haptics.critical] once when the screen of a [critical] alert
 * opens, also when [critical] turns true as the data arrives; not again
 * when the owner comes back to the screen from one pushed on top of it.
 */
@Composable
fun CriticalAlertHaptic(critical: Boolean) {
    val haptics = rememberHaptics()
    var played by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(critical) {
        if (critical && !played) {
            haptics.critical()
            played = true
        }
    }
}
