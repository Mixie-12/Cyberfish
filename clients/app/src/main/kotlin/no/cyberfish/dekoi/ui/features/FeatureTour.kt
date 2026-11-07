package no.cyberfish.dekoi.ui.features

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import no.cyberfish.dekoi.Feature
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.RaisedSurface
import no.cyberfish.dekoi.ui.components.SectionLabel
import no.cyberfish.dekoi.ui.label

/** Time to read one caption before the tour moves on. */
private const val STEP_MS = 5000L

/** Longer than this, as the accessibility settings ask with TalkBack on, and the owner moves on with Next. */
private const val LONGEST_STEP_MS = 60_000L

/**
 * The feature tour: card [step] is scrolled to the top and lit while this
 * caption, over the bottom of the screen, says what it does. The tour moves
 * on by itself, a line along the caption's top edge showing when, given
 * longer when the phone's accessibility settings ask for more time. It
 * waits for Next when animations are off or with TalkBack on. Skip and back
 * end it at any time. [onStep] receives the next step, or null at the end.
 */
@Composable
fun TourCaption(
    step: Int,
    listState: LazyListState,
    onStep: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val feature = Feature.entries[step]
    val still = LocalReduceMotion.current
    val readingMs =
        LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(STEP_MS, containsText = true, containsControls = true)
            ?: STEP_MS
    val timed = !still && readingMs <= LONGEST_STEP_MS
    val last = step == Feature.entries.lastIndex
    val move by rememberUpdatedState(onStep)
    val next = { move(if (last) null else step + 1) }
    val progress = remember(step) { Animatable(0f) }
    LaunchedEffect(step) {
        val card = FIRST_CARD + step
        if (still) listState.scrollToItem(card) else listState.animateScrollToItem(card)
        if (!timed) return@LaunchedEffect
        progress.animateTo(1f, tween(readingMs.toInt(), easing = LinearEasing))
        next()
    }
    BackHandler { move(null) }
    val blue = MaterialTheme.colorScheme.primary
    RaisedSurface(CutCornerShape(Dimens.cut), modifier.fillMaxWidth().padding(horizontal = Dimens.gutter, vertical = Dimens.spaceL)) {
        Column(
            Modifier
                .drawBehind {
                    if (!timed) return@drawBehind
                    val width = Dimens.hairline.toPx() * 2
                    drawLine(blue, Offset(0f, width / 2), Offset(size.width * progress.value, width / 2), width)
                }.padding(start = Dimens.spaceL, end = Dimens.spaceS, top = Dimens.spaceL, bottom = Dimens.spaceXs),
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
        ) {
            Row(Modifier.padding(end = Dimens.spaceS), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(step + 1, stringResource(words(feature).name), Modifier.weight(1f))
                Text(
                    stringResource(R.string.features_tour_count, step + 1, Feature.entries.size),
                    style = MaterialTheme.typography.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(words(feature).tour),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = Dimens.spaceS).semantics { liveRegion = LiveRegionMode.Polite },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = { move(null) }) { Text(stringResource(R.string.features_tour_skip)) }
                TextButton(onClick = next) {
                    Text(stringResource(if (last) R.string.features_tour_done else R.string.features_tour_next))
                }
            }
        }
    }
}
