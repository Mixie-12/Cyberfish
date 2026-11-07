package no.cyberfish.dekoi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.markFont

private const val MARK = "><>"

// Where the ink of '>' and '<' sits in JetBrains Mono ExtraBold, in em:
// from 0.049 above the baseline, 0.56 tall (the font's glyf table).
private const val MARK_INK_BOTTOM = 0.049f
private const val MARK_INK_HEIGHT = 0.56f
private const val MARK_TRACKING = -0.06f
private const val MARK_STROKE = 0.08f
private const val MARK_GLOW = 0.25f
private const val MARK_GLOW_ALPHA = 0.6f

// Michroma's capitals are 0.75 em tall.
private const val CAP_HEIGHT = 0.75f
private const val LOCKUP_GAP = 0.6f

/** The guide's limit: the glow version only for large and hero use, from 48 dp tall. */
private val GLOW_FROM = 48.dp

private val markStyle = TextStyle(fontFamily = markFont, fontWeight = FontWeight.ExtraBold, letterSpacing = MARK_TRACKING.em)

/**
 * The CyberFish mark, `><>` in JetBrains Mono ExtraBold: white with a blue
 * stroke, glowing from 48 dp tall and flat below. [height] is the height of
 * its ink. A logo, so it keeps its size at any font scale. Decorative: name
 * the product in text beside it.
 */
@Composable
fun FishMark(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val blue = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layout =
        remember(height, density) {
            val fontSize = with(density) { (height / MARK_INK_HEIGHT).toSp() }
            measurer.measure(MARK, markStyle.copy(fontSize = fontSize))
        }
    val width = with(density) { layout.size.width.toDp() }
    val glow = height >= GLOW_FROM
    Canvas(modifier.size(width, height)) {
        val em = height.toPx() / MARK_INK_HEIGHT
        // Move the ink's top to the top of the canvas.
        translate(top = (MARK_INK_BOTTOM + MARK_INK_HEIGHT) * em - layout.firstBaseline) {
            val shadow = if (glow) Shadow(blue.copy(alpha = MARK_GLOW_ALPHA), Offset.Zero, MARK_GLOW * em) else null
            // The stroke goes under the fill, so only its outer half shows.
            drawText(layout, blue, shadow = shadow, drawStyle = Stroke(MARK_STROKE * em))
            drawText(layout, Color(DesignTokens.WHITE))
        }
    }
}

/**
 * The DeKoi lockup: the mark at the cap height of "DeKoi" in Michroma,
 * 0.6 em before it. [height] is the height of the capitals and the mark. A
 * logo, so it keeps its size at any font scale. TalkBack reads "DeKoi".
 */
@Composable
fun Wordmark(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val fontSize = with(LocalDensity.current) { (height / CAP_HEIGHT).toSp() }
    Row(modifier) {
        FishMark(height, Modifier.alignBy { it.measuredHeight })
        Spacer(Modifier.width(height / CAP_HEIGHT * LOCKUP_GAP))
        Text(
            stringResource(R.string.app_name),
            Modifier.alignByBaseline(),
            style = MaterialTheme.typography.displayMedium.copy(fontSize = fontSize, lineHeight = TextUnit.Unspecified),
        )
    }
}
