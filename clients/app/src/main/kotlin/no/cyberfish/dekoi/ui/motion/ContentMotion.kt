package no.cyberfish.dekoi.ui.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.expandSpring
import kotlin.math.abs

/**
 * Content that changes in place, such as the status hero going from
 * protected to attention: the old content fades out, the new one fades and
 * scales in (fade through, as between tabs), and the size follows the
 * expand spring. Nothing moves when animations are off.
 *
 * ```
 * AnimatedStatus(protection) { StatusHero(it, ...) }
 * ```
 */
@Composable
fun <S> AnimatedStatus(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable AnimatedContentScope.(S) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState,
        modifier,
        transitionSpec = { statusChange(reduceMotion) },
        contentAlignment = contentAlignment,
        label = "status",
        content = content,
    )
}

internal fun statusChange(reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
    } else {
        ContentTransform(
            fadeThroughIn(gesture = false),
            fadeThroughOut(gesture = false),
            sizeTransform = SizeTransform(clip = false) { _, _ -> expandSpring(IntSize.VisibilityThreshold) },
        )
    }

/**
 * An item that appears in or leaves a list that is not lazy, such as a
 * "Needs attention" card: it opens and closes with the expand spring and
 * fades as [visibleState] moves to its target; one that starts visible is
 * simply there. Rows of a LazyColumn use `Modifier.animateItem()`, whose
 * default springs are the same. Nothing moves when animations are off.
 *
 * ```
 * val shown = remember { MutableTransitionState(false) }.apply { targetState = true }
 * ExpandingVisibility(shown) { AttentionCard(...) }
 * ```
 */
@Composable
fun ExpandingVisibility(
    visibleState: MutableTransitionState<Boolean>,
    modifier: Modifier = Modifier,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedVisibility(
        visibleState,
        modifier,
        enter = if (reduceMotion) EnterTransition.None else expand,
        exit = if (reduceMotion) ExitTransition.None else shrink,
        label = "item",
        content = content,
    )
}

private val expand = expandVertically(expandSpring(IntSize.VisibilityThreshold)) + fadeIn(expandSpring())

private val shrink = shrinkVertically(expandSpring(IntSize.VisibilityThreshold)) + fadeOut(expandSpring())

/**
 * Of the items with [keys] on screen, the key of the one whose middle is
 * nearest the middle of the screen, or null when none shows. A screen with
 * several drawings that can move lets only that one move: one loop on
 * screen, and none off it (docs/design.md, principle 5).
 */
fun LazyListState.centredKey(keys: Collection<Any>): Any? {
    val layout = layoutInfo
    val middle = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo
        .filter { it.key in keys }
        .minByOrNull { abs(it.offset + it.size / 2 - middle) }
        ?.key
}
