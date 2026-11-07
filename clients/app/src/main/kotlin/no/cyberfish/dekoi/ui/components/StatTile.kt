package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import java.text.NumberFormat

private const val ROLL_MS = 300

/** A tappable summary tile: a label, a [value] such as a [RollingNumber], an optional detail line and chart. */
@Composable
fun StatTile(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    chart: (@Composable () -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            value()
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            chart?.invoke()
        }
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
