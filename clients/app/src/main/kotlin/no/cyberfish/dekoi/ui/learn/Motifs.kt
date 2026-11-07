package no.cyberfish.dekoi.ui.learn

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import java.util.Locale

// Stand-ins for HudFrame, Horizon and SectionLabel (ui/components) and
// headingCase (ui/Theme.kt), which the brand work adds in parallel. Same
// names and parameters: once those land, delete this file and import them.

private val CUT = 8.dp
private const val HUD_ALPHA = 0.15f
private val HORIZON_Y = 16.dp
private val HORIZON_LINE = 1.5.dp
private val HORIZON_GLOW = 8.dp
private const val GLOW_ALPHA = 0.25f
private const val SEA_LINES = 5
private const val SEA_ALPHA = 0.3f
private const val LABEL_TRACKING = 0.13f

/** The HUD frame around a hero: one thin blue line with 45-degree cut corners. */
@Composable
internal fun HudFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val line = MaterialTheme.colorScheme.primary.copy(alpha = HUD_ALPHA)
    Box(modifier.border(Dimens.hairline, line, CutCornerShape(CUT)).padding(Dimens.spaceL), content = content)
}

/**
 * The horizon: a glowing line with faint lines below it that spread out
 * towards the bottom. Still, so the drawing above it stays the one loop
 * on screen. Decorative.
 */
@Composable
internal fun Horizon(modifier: Modifier = Modifier) {
    val blue = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val y = HORIZON_Y.toPx()
        drawLine(Brush.horizontalGradient(fading(blue.copy(alpha = GLOW_ALPHA))), Offset(0f, y), Offset(size.width, y), HORIZON_GLOW.toPx())
        drawLine(Brush.horizontalGradient(fading(blue)), Offset(0f, y), Offset(size.width, y), HORIZON_LINE.toPx())
        for (i in 1..SEA_LINES) {
            val t = i.toFloat() / (SEA_LINES + 1)
            val lineY = y + (size.height - y) * t * t
            val line = Brush.horizontalGradient(fading(blue.copy(alpha = SEA_ALPHA * (1 - t))))
            drawLine(line, Offset(0f, lineY), Offset(size.width, lineY), Dimens.hairline.toPx())
        }
    }
}

private fun fading(color: Color) = listOf(Color.Transparent, color, Color.Transparent)

/** A HUD label such as "01 / WHAT IT IS": a heading; screen readers hear the text without the number. */
@Composable
internal fun SectionLabel(
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
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = LABEL_TRACKING.em),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Upper case for headings and labels, keeping the product name as it is always written. */
@Composable
@ReadOnlyComposable
internal fun String.headingCase(): String {
    val name = stringResource(R.string.app_name)
    return split(name).joinToString(name) { it.uppercase(Locale.getDefault()) }
}
