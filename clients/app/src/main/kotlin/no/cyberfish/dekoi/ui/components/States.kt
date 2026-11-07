package no.cyberfish.dekoi.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.relativeTime
import java.time.Duration
import java.time.Instant

private val STALE_AFTER = Duration.ofMinutes(5)
private const val SKELETON_MS = 800
private val EMPTY_GLYPH = 48.dp

/** A placeholder block; shape a few of them like the content that is loading. Still when animations are off. */
@Composable
fun Skeleton(
    modifier: Modifier,
    shape: Shape = MaterialTheme.shapes.small,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    val fading =
        if (LocalReduceMotion.current) {
            Modifier
        } else {
            val transition = rememberInfiniteTransition(label = "skeleton")
            val alpha by transition.animateFloat(1f, 0.5f, infiniteRepeatable(tween(SKELETON_MS), RepeatMode.Reverse), label = "skeleton")
            Modifier.graphicsLayer { this.alpha = alpha }
        }
    Box(modifier.clearAndSetSemantics { }.then(fading).background(color, shape))
}

/** A failed refresh, shown above the data the app still has. Never a dialog. */
@Composable
fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = LocalSeverityColors.current.info
    Row(
        modifier
            .fillMaxWidth()
            .background(tone.container, MaterialTheme.shapes.large)
            .padding(start = Dimens.spaceL, end = Dimens.spaceXs)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = tone.color)
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = tone.onContainer,
            modifier = Modifier.weight(1f).padding(vertical = Dimens.spaceM),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again)) }
    }
}

/** Nothing to show yet: a glyph, one sentence, and the next step in [action] when there is one. */
@Composable
fun EmptyState(
    @DrawableRes glyph: Int,
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(vertical = Dimens.spaceXl, horizontal = Dimens.gutter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Icon(
            painterResource(glyph),
            contentDescription = null,
            modifier = Modifier.size(EMPTY_GLYPH),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

/** "Updated 10 minutes ago" once the data is more than five minutes old; nothing before that. */
@Composable
fun StaleLabel(
    updatedAt: Instant?,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    if (updatedAt == null || Duration.between(updatedAt, now) < STALE_AFTER) return
    Text(
        stringResource(R.string.updated_ago, relativeTime(updatedAt, now)),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
