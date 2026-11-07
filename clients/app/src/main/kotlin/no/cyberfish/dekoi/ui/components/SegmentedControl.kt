package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.RaisedSurface
import no.cyberfish.dekoi.ui.expandSpring

private val INSET = 4.dp

/**
 * Equal segments on a track with the chosen one raised, as in iOS. M3's
 * segmented button is an outlined button group with check marks; the
 * design asks for this quieter look. Each segment is a 48 dp tall tab.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.small
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Dimens.touchTarget)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, shape),
    ) {
        val segment = maxWidth / options.size
        val spec = if (LocalReduceMotion.current) snap() else expandSpring(1.dp)
        val offset by animateDpAsState(segment * selected, spec, label = "segment")
        RaisedSurface(
            shape,
            Modifier
                .offset { IntOffset(offset.roundToPx(), 0) }
                .width(segment)
                .fillMaxHeight()
                .padding(INSET),
        ) {}
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
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
