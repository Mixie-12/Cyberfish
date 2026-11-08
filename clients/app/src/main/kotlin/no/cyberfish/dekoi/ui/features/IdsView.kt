package no.cyberfish.dekoi.ui.features

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import java.text.NumberFormat
import kotlin.math.abs

private const val FLOW_MS = 3000
private const val PACKETS = 9

// The still frame puts a packet in the gate, being checked.
private const val STILL = 0f
private const val LANE = 0.62f
private const val FAINT = 0.35f
private const val PASSED = 0.7f
private const val GLOW_ALPHA = 0.5f
private val SCENE = 112.dp
private val GATE = 22.dp
private val GATE_WIDTH = 7.dp
private val PACKET = 3.dp
private val HELD = 5.dp
private val GLOW = 12.dp
private val STROKE = 1.5.dp
private val LABEL_GAP = 6.dp

/**
 * Intrusion detection at a glance: traffic as a row of packets that pass a
 * gate holding [rules] attack patterns, each lit as it is checked and blue
 * once it has passed. The newest alert of the last day, [alert], waits at
 * the gate in the colour of its severity and holds still. While [on] it is
 * blue, grey otherwise; while [playing], the packets flow.
 */
@Composable
fun IdsView(
    rules: Int?,
    alert: Event?,
    on: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSeverityColors.current
    val blue = if (on) MaterialTheme.colorScheme.primary else colors.offline
    val grey = colors.offline
    val held = alert?.let { colors.of(it.severity).color }
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val count = rules?.let { NumberFormat.getIntegerInstance().format(it) }
    val flow = if (playing && on && !LocalReduceMotion.current) flow() else null
    Canvas(
        modifier
            .fillMaxWidth()
            .height(SCENE)
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        val lane = size.height * LANE
        val gate = Offset(size.width / 2, lane)
        drawLine(grey.copy(alpha = FAINT), Offset(0f, lane), Offset(size.width, lane), STROKE.toPx())
        drawGate(gate, blue)
        count?.let {
            val text = measurer.measure(it, label)
            drawText(text, topLeft = Offset(gate.x - text.size.width / 2f, lane - GATE.toPx() - LABEL_GAP.toPx() - text.size.height))
        }
        val spacing = size.width / PACKETS
        val t = flow?.value ?: STILL
        for (i in 0..PACKETS) {
            val x = (i + t) * spacing - spacing / 2
            val from = x - gate.x
            val near = (1 - abs(from) / (spacing / 2)).coerceAtLeast(0f)
            val color =
                when {
                    near > 0f -> blue
                    from < 0 -> grey
                    else -> blue.copy(alpha = PASSED)
                }
            val at = Offset(x, lane)
            if (near > 0f) drawGlow(at, blue, GLOW_ALPHA * near)
            drawCircle(color, PACKET.toPx(), at)
        }
        held?.let {
            drawGlow(gate, it, GLOW_ALPHA)
            drawCircle(it, HELD.toPx(), gate)
            drawCircle(it, HELD.toPx() * 2, gate, style = Stroke(STROKE.toPx()))
        }
    }
}

@Composable
private fun flow(): State<Float> =
    rememberInfiniteTransition(label = "ids")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(FLOW_MS, easing = LinearEasing)), label = "ids")

/** The gate: two upright brackets across the lane, with a soft glow between them. */
private fun DrawScope.drawGate(
    at: Offset,
    color: Color,
) {
    val half = GATE.toPx()
    val side = GATE_WIDTH.toPx()
    drawGlow(at, color, GLOW_ALPHA / 2)
    listOf(-1f, 1f).forEach { dx ->
        val x = at.x + dx * side
        drawLine(color, Offset(x, at.y - half), Offset(x, at.y + half), STROKE.toPx())
        drawLine(color, Offset(x, at.y - half), Offset(x - dx * side / 2, at.y - half), STROKE.toPx())
        drawLine(color, Offset(x, at.y + half), Offset(x - dx * side / 2, at.y + half), STROKE.toPx())
    }
}

private fun DrawScope.drawGlow(
    at: Offset,
    color: Color,
    alpha: Float,
) {
    val radius = GLOW.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), at, radius), radius, at)
}
