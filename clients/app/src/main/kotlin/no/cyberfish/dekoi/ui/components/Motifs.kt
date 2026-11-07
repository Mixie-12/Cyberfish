package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label

private const val HUD_ALPHA = 0.15f

private const val HORIZON_GLOW_ALPHA = 0.35f
private const val SEA_LINES = 7
private const val SEA_ALPHA = 0.3f
private const val SHIMMER_ALPHA = 0.2f
private const val SHIMMER_MS = 16_000
private val HORIZON_GLOW = 16.dp
private val HORIZON_LINE = 1.5.dp

/**
 * The HUD frame that marks a hero area: one thin blue line at 15 % with
 * small 45-degree cut corners, and nothing else on it. Content that moves,
 * such as the network map, stays inside it.
 */
@Composable
fun HudFrame(
    modifier: Modifier = Modifier,
    contentPadding: Dp = Dimens.spaceL,
    content: @Composable BoxScope.() -> Unit,
) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = HUD_ALPHA)
    val shape = CutCornerShape(Dimens.cut)
    Box(modifier.clip(shape).border(Dimens.hairline, line, shape).padding(contentPadding), content = content)
}

/**
 * The horizon: a glowing blue line along the top, with its glow above it,
 * and faint lines below it that spread out towards the bottom like a dark
 * sea. A shimmer drifts along the line once every 16 s; still when
 * animations are off, or when [still] because something else on screen
 * moves (one idle loop per screen). Decorative.
 */
@Composable
fun Horizon(
    modifier: Modifier = Modifier,
    still: Boolean = false,
) {
    val blue = MaterialTheme.colorScheme.primary
    val shimmer = if (still || LocalReduceMotion.current) null else shimmer()
    Canvas(modifier.clearAndSetSemantics { }) {
        val y = HORIZON_GLOW.toPx()
        drawHorizon(blue, y)
        drawSea(blue, y)
        shimmer?.let { drawShimmer(y, it.value) }
    }
}

@Composable
private fun shimmer(): State<Float> =
    rememberInfiniteTransition(label = "horizon")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing)), label = "horizon")

/** Strongest in the middle, gone at the ends, as a line of light over the sea. */
private fun fading(color: Color) = listOf(Color.Transparent, color, Color.Transparent)

private fun DrawScope.drawHorizon(
    blue: Color,
    y: Float,
) {
    val glow = HORIZON_GLOW.toPx()
    val middle = Offset(size.width / 2, y)
    // A round glow stretched along the line: widest in the middle, gone at the ends.
    scale(scaleX = size.width / (2 * glow), scaleY = 1f, pivot = middle) {
        drawCircle(Brush.radialGradient(listOf(blue.copy(alpha = HORIZON_GLOW_ALPHA), Color.Transparent), middle, glow), glow, middle)
    }
    drawLine(Brush.horizontalGradient(fading(blue)), Offset(0f, y), Offset(size.width, y), HORIZON_LINE.toPx())
}

// Gaps grow with the square of the distance, as a surface seen in
// perspective, and the lines fade towards the bottom.
private fun DrawScope.drawSea(
    blue: Color,
    y: Float,
) {
    for (i in 1..SEA_LINES) {
        val t = i.toFloat() / (SEA_LINES + 1)
        val lineY = y + (size.height - y) * t * t
        val line = Brush.horizontalGradient(fading(blue.copy(alpha = SEA_ALPHA * (1 - t))))
        drawLine(line, Offset(0f, lineY), Offset(size.width, lineY), Dimens.hairline.toPx())
    }
}

private fun DrawScope.drawShimmer(
    y: Float,
    progress: Float,
) {
    val band = size.width / 4
    val x = -band + (size.width + 2 * band) * progress
    val shine = Brush.horizontalGradient(fading(Color(DesignTokens.WHITE).copy(alpha = SHIMMER_ALPHA)), startX = x - band, endX = x + band)
    drawLine(shine, Offset(0f, y), Offset(size.width, y), HORIZON_LINE.toPx())
}

/**
 * A numbered section: its [SectionLabel] and a hairline to the edge, then
 * its content. Space and the line set it apart, not a card.
 */
@Composable
fun Section(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            SectionLabel(number, title)
            HorizontalDivider(Modifier.weight(1f))
        }
        content()
    }
}

/**
 * A HUD label such as "01 / NETWORK": the number in blue, the text in mid
 * grey, small, upper case and widely tracked. A heading; TalkBack reads
 * the text without the number.
 */
@Composable
fun SectionLabel(
    number: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    val blue = MaterialTheme.colorScheme.primary
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = blue)) { append(number.toString().padStart(2, '0')) }
            append(" / ")
            append(text.headingCase())
        },
        modifier.clearAndSetSemantics {
            heading()
            contentDescription = text
        },
        style = MaterialTheme.typography.label,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
