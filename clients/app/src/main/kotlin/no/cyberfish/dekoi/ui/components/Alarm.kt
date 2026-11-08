package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.ui.LocalReduceMotion

private const val ALARM_PULSES = 3
private const val ALARM_MS = 3000
private const val ALARM_RINGS = 2
private const val RING_ALPHA = 0.45f
private const val GLOW_ALPHA = 0.3f
private val RING_WIDTH = 3.dp

/**
 * The alarm of a very likely attack (docs/design.md, "Alert detail"): its
 * progress for [drawAlarm], which runs through three pulses in about three
 * seconds once per visit, and is at its end at once when the alarm has
 * played or animations are off.
 */
@Composable
fun rememberAlarm(): State<Float> {
    // Once per visit: scrolling the alarm away and back does not play it again.
    var played by rememberSaveable { mutableStateOf(false) }
    val still = played || LocalReduceMotion.current
    val progress = remember { Animatable(if (still) ALARM_PULSES.toFloat() else 0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(ALARM_PULSES.toFloat(), tween(ALARM_MS, easing = LinearEasing))
        played = true
    }
    return progress.asState()
}

/**
 * Rings grow from [inner] to [outer] around [centre] and fade, two at a
 * time, while [progress] (from [rememberAlarm]) runs; a glow builds up
 * meanwhile and stays.
 */
fun DrawScope.drawAlarm(
    progress: Float,
    color: Color,
    inner: Float,
    centre: Offset = center,
    outer: Float = size.minDimension / 2,
) {
    if (progress < ALARM_PULSES) {
        for (ring in 0 until ALARM_RINGS) {
            val phase = (progress + ring.toFloat() / ALARM_RINGS) % 1f
            drawCircle(
                color.copy(alpha = RING_ALPHA * (1 - phase)),
                radius = inner + (outer - inner) * phase,
                center = centre,
                style = Stroke(RING_WIDTH.toPx()),
            )
        }
    }
    val glow = GLOW_ALPHA * (progress / ALARM_PULSES)
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), centre, outer), outer, centre)
}
