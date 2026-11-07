package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.expandSpring
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label

private val INDICATOR = 2.dp

/**
 * Equal segments over a hairline, the chosen one marked by a blue line
 * that slides under it. Lines rather than a raised box (ADR 0020). Each
 * segment is a 48 dp tall tab.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Dimens.touchTarget)
            .drawBehind { drawBottomLine(hairline, Dimens.hairline.toPx()) },
    ) {
        val segment = maxWidth / options.size
        val spec = if (LocalReduceMotion.current) snap() else expandSpring(1.dp)
        val offset by animateDpAsState(segment * selected, spec, label = "segment")
        Box(
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .drawBehind { drawBottomLine(blue, INDICATOR.toPx()) },
        )
        Row(Modifier.fillMaxSize().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val isSelected = index == selected
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(isSelected, onClick = { onSelect(index) }, role = Role.Tab),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label.headingCase(),
                        style = MaterialTheme.typography.label,
                        color = if (isSelected) Color(DesignTokens.WHITE) else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawBottomLine(
    color: Color,
    width: Float,
) {
    val y = size.height - width / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), width)
}
