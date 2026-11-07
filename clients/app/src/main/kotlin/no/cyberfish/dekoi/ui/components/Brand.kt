package no.cyberfish.dekoi.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.DesignTokens
import kotlin.math.max

// Michroma's capitals are 0.75 em tall.
private const val CAP_HEIGHT = 0.75f
private const val LOCKUP_GAP = 0.6f

/** The guide's limit: the glow version only for large and hero use, from 48 dp tall. */
private val GLOW_FROM = 48.dp

/**
 * The CyberFish mark, `><>`, as the app icon draws it: the outline from
 * Images/cyberfish-app-icon.svg, filled with a thin blue edge, glowing from
 * 48 dp tall and flat below. [height] is the height of its ink. A logo, so
 * it keeps its size at any font scale. Decorative: name the product in text
 * beside it.
 */
@Composable
fun FishMark(
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val pathData = stringResource(R.string.mark_path)
    val outline = remember(pathData) { PathParser().parsePathString(pathData).toPath() }
    val bounds = remember(outline) { outline.getBounds() }
    val glow = height >= GLOW_FROM
    Spacer(
        modifier.size(height * (bounds.width / bounds.height), height).drawWithCache {
            // Drawn in pixels, so that the blurs are in pixels too.
            val unit = size.height / bounds.height
            val mark =
                Path().apply {
                    addPath(outline, -bounds.topLeft)
                    transform(Matrix().apply { scale(unit, unit) })
                }
            val blurs = if (glow) DesignTokens.MARK_GLOW.map { blurPaint(it.sigma * unit, Color(it.color)) } else emptyList()
            val edge = Stroke(DesignTokens.MARK_EDGE * unit, join = StrokeJoin.Round)
            onDrawBehind {
                blurs.forEach { drawContext.canvas.drawPath(mark, it) }
                drawPath(mark, Color(DesignTokens.MARK_FILL))
                drawPath(mark, Color(DesignTokens.BLUE), style = edge)
            }
        },
    )
}

/**
 * A paint that draws the mark and its blur beneath it; the mark's fill then
 * covers all but the blur. Android blurs a shadow of radius r with a
 * Gaussian of sigma 0.57735 r + 0.5 px (hwui, Blur::convertRadiusToSigma).
 */
private fun blurPaint(
    sigma: Float,
    color: Color,
) = Paint().also {
    it.color = color
    it.nativePaint.setShadowLayer(max(0f, (sigma - 0.5f) / 0.57735f), 0f, 0f, color.toArgb())
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
