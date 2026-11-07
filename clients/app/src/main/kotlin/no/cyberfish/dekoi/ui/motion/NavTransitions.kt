package no.cyberfish.dekoi.ui.motion

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import no.cyberfish.dekoi.ui.LocalReduceMotion

// docs/design.md, "Tokens" and "Motion".
private const val PUSH_MS = 300
private const val FADE_THROUGH_MS = 200
private const val FADE_THROUGH_SCALE = 0.92f
private val PUSH_DISTANCE = 30.dp

/** How one change of screen moves. */
internal enum class ScreenChange { INSTANT, FADE_THROUGH, SHARED_AXIS }

/** Which way the back stack moves. A back gesture seeks the pop with the finger (predictive back). */
enum class Direction(
    internal val sign: Int,
) {
    FORWARD(1),
    BACK(-1),
    BACK_GESTURE(-1),
}

/** Nothing moves when animations are off; tab switches fade through; pushes and pops slide (shared axis X). */
internal fun screenChange(
    reduceMotion: Boolean,
    changesTab: Boolean,
): ScreenChange =
    when {
        reduceMotion -> ScreenChange.INSTANT
        changesTab -> ScreenChange.FADE_THROUGH
        else -> ScreenChange.SHARED_AXIS
    }

/**
 * The NavHost transitions. [distance] is the push distance in pixels,
 * negative when the layout reads right to left. [changesTab] tells a tab
 * switch from a push or a pop.
 */
class NavTransitions internal constructor(
    private val distance: Int,
    private val reduceMotion: Boolean,
    private val changesTab: (from: NavBackStackEntry, to: NavBackStackEntry) -> Boolean,
) {
    fun enter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        direction: Direction,
    ): EnterTransition = enter(change(scope), direction)

    fun exit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        direction: Direction,
    ): ExitTransition = exit(change(scope), direction)

    internal fun enter(
        change: ScreenChange,
        direction: Direction,
    ): EnterTransition {
        val gesture = direction == Direction.BACK_GESTURE
        return when (change) {
            ScreenChange.INSTANT -> {
                EnterTransition.None
            }

            ScreenChange.FADE_THROUGH -> {
                fadeThroughIn(gesture)
            }

            ScreenChange.SHARED_AXIS -> {
                slideInHorizontally(slide(gesture)) { distance * direction.sign } +
                    fadeIn(incoming(PUSH_MS, gesture))
            }
        }
    }

    internal fun exit(
        change: ScreenChange,
        direction: Direction,
    ): ExitTransition {
        val gesture = direction == Direction.BACK_GESTURE
        return when (change) {
            ScreenChange.INSTANT -> {
                ExitTransition.None
            }

            ScreenChange.FADE_THROUGH -> {
                fadeThroughOut(gesture)
            }

            ScreenChange.SHARED_AXIS -> {
                slideOutHorizontally(slide(gesture)) { -distance * direction.sign } +
                    fadeOut(outgoing(PUSH_MS, gesture))
            }
        }
    }

    private fun change(scope: AnimatedContentTransitionScope<NavBackStackEntry>) =
        screenChange(reduceMotion, changesTab(scope.initialState, scope.targetState))
}

/** The NavHost transitions for the current density, reading direction and animation setting. */
@Composable
fun rememberNavTransitions(changesTab: (from: NavBackStackEntry, to: NavBackStackEntry) -> Boolean): NavTransitions {
    val reading = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val distance = with(LocalDensity.current) { PUSH_DISTANCE.roundToPx() } * reading
    val reduceMotion = LocalReduceMotion.current
    val currentChangesTab by rememberUpdatedState(changesTab)
    return remember(distance, reduceMotion) { NavTransitions(distance, reduceMotion) { from, to -> currentChangesTab(from, to) } }
}

/** Fade through (200 ms): the old content fades out, then the new fades and scales in. */
internal fun fadeThroughIn(gesture: Boolean): EnterTransition =
    fadeIn(incoming(FADE_THROUGH_MS, gesture)) + scaleIn(incoming(FADE_THROUGH_MS, gesture), FADE_THROUGH_SCALE)

internal fun fadeThroughOut(gesture: Boolean): ExitTransition = fadeOut(outgoing(FADE_THROUGH_MS, gesture))

// The old content is gone in the first 35 % before the new fades in, so the
// two never show at once. A back gesture seeks the transition with the
// finger, so there both fade linearly from the start: the previous screen
// shows as soon as the gesture begins.
private fun <T> incoming(
    totalMs: Int,
    gesture: Boolean,
): TweenSpec<T> = if (gesture) tween(totalMs, easing = LinearEasing) else tween(totalMs - outMs(totalMs), delayMillis = outMs(totalMs))

private fun <T> outgoing(
    totalMs: Int,
    gesture: Boolean,
): TweenSpec<T> = if (gesture) tween(totalMs, easing = LinearEasing) else tween(outMs(totalMs))

private fun <T> slide(gesture: Boolean): TweenSpec<T> = tween(PUSH_MS, easing = if (gesture) LinearEasing else FastOutSlowInEasing)

private fun outMs(totalMs: Int) = totalMs * 35 / 100
