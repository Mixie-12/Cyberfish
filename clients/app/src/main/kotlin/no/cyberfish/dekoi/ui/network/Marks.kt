package no.cyberfish.dekoi.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalSeverityColors

private val DOT = 10.dp
private val DOT_GAP = 2.dp
private val TAG_PADDING = 6.dp

/**
 * Online (filled) or not seen recently (hollow), on a ring of the
 * background so it stands clear of the tile it sits on. Decorative: say
 * the same in words nearby.
 */
@Composable
internal fun StatusDot(
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSeverityColors.current
    val gap = MaterialTheme.colorScheme.background
    Canvas(modifier.size(DOT)) {
        val radius = size.minDimension / 2
        val inner = radius - DOT_GAP.toPx()
        drawCircle(gap, radius)
        if (online) {
            drawCircle(colors.safe.color, inner)
        } else {
            val stroke = Dimens.hairline.toPx() * 2
            drawCircle(colors.offline, inner - stroke / 2, style = Stroke(stroke))
        }
    }
}

/** A short word in a hairline outline, such as "New" or a service label. Plain text only. */
@Composable
internal fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Text(
        text,
        modifier
            .border(Dimens.hairline, color, MaterialTheme.shapes.small)
            .padding(horizontal = TAG_PADDING, vertical = Dimens.hairline),
        color = color,
        style = style,
        maxLines = 1,
    )
}
