package no.cyberfish.dekoi.ui.motion

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.expandSpring
import kotlin.reflect.KClass

/** The scopes a shared element needs: the app's [SharedTransitionScope] and the [AnimatedVisibilityScope] of the screen it is on. */
class SharedTransition(
    val layout: SharedTransitionScope,
    val screen: AnimatedVisibilityScope,
)

/**
 * The shared transition of the screen being composed. Null outside a
 * NavHost destination declared with [screen], where the shared element
 * modifiers do nothing.
 */
val LocalSharedTransition = staticCompositionLocalOf<SharedTransition?> { null }

private val LocalSharedTransitionLayout = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Lets the screens in [content] share elements. Wraps the NavHost. */
@Composable
fun SharedElementHost(content: @Composable () -> Unit) {
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionLayout provides this, content = content)
    }
}

/** A NavHost destination, as `composable<T>`, whose content gets [LocalSharedTransition]. */
inline fun <reified T : Any> NavGraphBuilder.screen(noinline content: @Composable (NavBackStackEntry) -> Unit) {
    composable<T> { entry -> ProvideSharedTransition(this) { content(entry) } }
}

@PublishedApi
@Composable
internal fun ProvideSharedTransition(
    screen: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val layout = LocalSharedTransitionLayout.current
    val shared = remember(layout, screen) { layout?.let { SharedTransition(it, screen) } }
    CompositionLocalProvider(LocalSharedTransition provides shared, content = content)
}

/**
 * Moves a host's kind tile between a list row or map node and the Device
 * detail hero. [hero] is the route of the screen with the hero, on both
 * ends, so the two tiles match:
 *
 * ```
 * // Network list row, map node, device card on Alert detail
 * KindTile(host.kind, Modifier.sharedKindTile(Route.HostDetail(host.deviceId, host.mac)))
 * // Device detail hero
 * KindTile(host.kind, Modifier.sharedKindTile(Route.HostDetail(deviceId, mac)), hero = true)
 * ```
 *
 * Put it first in the modifier chain, before size, clip and background,
 * on one element per screen and hero. The tile moves only while the hero's
 * screen is pushed or popped, never on a tab switch, and not at all when
 * animations are off or outside a [screen] destination.
 */
@Composable
fun Modifier.sharedKindTile(hero: Route): Modifier = sharedHero(HeroPart.KIND_TILE, hero)

/**
 * Moves an alert's severity tile or dot between a list row or card and the
 * hero of its detail screen, as [sharedKindTile] does for kind tiles:
 * `SeverityTile(event.severity, Modifier.sharedSeverity(Route.AlertDetail(event.id)))`
 * on both ends.
 */
@Composable
fun Modifier.sharedSeverity(hero: Route): Modifier = sharedHero(HeroPart.SEVERITY, hero)

private enum class HeroPart { KIND_TILE, SEVERITY }

private data class HeroKey(
    val part: HeroPart,
    val hero: Route,
)

// The shared element follows the expand spring, as everything that grows does.
private val heroBounds = BoundsTransform { _, _ -> expandSpring(Rect.VisibilityThreshold) }

@Composable
private fun Modifier.sharedHero(
    part: HeroPart,
    hero: Route,
): Modifier {
    val shared = LocalSharedTransition.current
    if (shared == null || LocalReduceMotion.current) return this
    val config = remember(shared.screen, hero::class) { WhileHeroMoves(shared.screen, hero::class) }
    with(shared.layout) {
        val state = rememberSharedContentState(HeroKey(part, hero), config)
        // Bounds rather than element: the small and the large tile differ in
        // corner radius and icon size, so they cross-fade while scaled.
        return this@sharedHero.sharedBounds(
            state,
            shared.screen,
            boundsTransform = heroBounds,
            resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
        )
    }
}

/**
 * Enabled only while a screen of the [hero] route enters or leaves, so two
 * tab roots showing the same item do not trade it on a tab switch.
 */
private class WhileHeroMoves(
    private val screen: AnimatedVisibilityScope,
    private val hero: KClass<out Route>,
) : SharedTransitionScope.SharedContentConfig {
    override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
        get() {
            // NavHost shows each destination in a child of its transition between back stack entries.
            val navigation = screen.transition.parentTransition ?: return false
            return listOf(navigation.currentState, navigation.targetState).any {
                (it as? NavBackStackEntry)?.destination?.hasRoute(hero) == true
            }
        }
}
