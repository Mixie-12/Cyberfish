package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label
import java.text.NumberFormat

private const val ROLL_MS = 300
private val ACCENT = 24.dp

/**
 * A tappable summary tile, open rather than boxed: a hairline along the
 * top with a short blue lead, a section label, a [value] such as a
 * [RollingNumber], an optional detail line and chart.
 */
@Composable
fun StatTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    chart: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    Column(
        modifier
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                val width = Dimens.hairline.toPx()
                val y = width / 2
                val accent = ACCENT.toPx().coerceAtMost(size.width)
                val start = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - accent
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), width)
                drawLine(blue, Offset(start, y), Offset(start + accent, y), width)
            }.padding(top = Dimens.spaceM, bottom = Dimens.spaceS),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Text(label.headingCase(), style = MaterialTheme.typography.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        value()
        detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        chart?.invoke()
    }
}

/** A count in tabular figures that rolls up or down when it changes. */
@Composable
fun RollingNumber(
    value: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayMedium,
) {
    val format = NumberFormat.getIntegerInstance()
    if (LocalReduceMotion.current) {
        Text(format.format(value), modifier, style = style)
        return
    }
    AnimatedContent(
        targetState = value,
        modifier = modifier,
        transitionSpec = {
            val direction = if (targetState > initialState) 1 else -1
            (slideInVertically(tween(ROLL_MS)) { it * direction } + fadeIn(tween(ROLL_MS))) togetherWith
                (slideOutVertically(tween(ROLL_MS)) { -it * direction } + fadeOut(tween(ROLL_MS)))
        },
        label = "number",
    ) { Text(format.format(it), style = style) }
}
