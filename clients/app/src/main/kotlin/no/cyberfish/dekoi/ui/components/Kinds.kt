package no.cyberfish.dekoi.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** The Material Symbol for a device kind. */
@DrawableRes
fun kindIcon(kind: HostKind): Int = look(kind).icon

/**
 * A device kind as a rounded tile: 40 dp in rows and on the map, 96 dp as
 * the hero of Device detail. Only the hero plays the kind's idle animation,
 * and not when animations are off.
 */
@Composable
fun KindTile(
    kind: HostKind,
    modifier: Modifier = Modifier,
    hero: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val look = look(kind)
    val idle = look.idle?.takeIf { hero && !LocalReduceMotion.current }
    Box(
        modifier.size(if (hero) Dimens.heroTile else Dimens.tile).clip(tileShape(hero)).background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        val iconSize = if (hero) HERO_ICON else ICON
        if (idle == null) {
            Icon(painterResource(look.icon), contentDescription = null, modifier = Modifier.size(iconSize), tint = contentColor)
        } else {
            IdleIcon(look.icon, idle, iconSize, contentColor)
        }
    }
}

/** The koi mark on brand orange: DeKoi itself, wherever it appears among devices. */
@Composable
fun BrandTile(
    modifier: Modifier = Modifier,
    hero: Boolean = false,
) {
    Box(modifier.size(if (hero) Dimens.heroTile else Dimens.tile).clip(tileShape(hero)).background(Color(DesignTokens.BRAND))) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

private val ICON = 24.dp
private val HERO_ICON = 48.dp

private fun tileShape(hero: Boolean) = RoundedCornerShape(if (hero) Dimens.heroRadius else Dimens.tileRadius)

private class KindLook(
    @param:DrawableRes val icon: Int,
    val idle: Idle?,
)

// One row per ADR 0010 kind (docs/design.md, "Device kinds"). Idle shapes
// are placed on the symbol's 960-unit grid.
private fun look(kind: HostKind): KindLook =
    when (kind) {
        HostKind.ROUTER -> KindLook(R.drawable.ic_router, Glows(2000, listOf(spot(280f, 680f, 70f), spot(420f, 680f, 70f))))
        HostKind.SWITCH -> KindLook(R.drawable.ic_lan, Travel(3000, switchLinks))
        HostKind.ACCESS_POINT -> KindLook(R.drawable.ic_nest_wifi_point, Arcs(2400, middle, listOf(440f, 520f), 225f, 90f, 2.dp))
        HostKind.COMPUTER -> KindLook(R.drawable.ic_computer, Glows(1000, listOf(Rect(290f, 300f, 330f, 440f)), ::blink))
        HostKind.PHONE -> KindLook(R.drawable.ic_smartphone, Tilt(4000))
        HostKind.TABLET -> KindLook(R.drawable.ic_tablet, Tilt(5000))
        HostKind.TV -> KindLook(R.drawable.ic_tv, Sheen(6000, Rect(160f, 200f, 800f, 680f)))
        HostKind.MEDIA_PLAYER -> KindLook(R.drawable.ic_cast, Arcs(2400, Offset(80f, 800f), listOf(200f, 360f), 270f, 90f))
        HostKind.SPEAKER -> KindLook(R.drawable.ic_speaker, Arcs(1600, Offset(480f, 600f), listOf(380f, 460f), -40f, 80f))
        HostKind.GAME_CONSOLE -> KindLook(R.drawable.ic_sports_esports, Glows(2000, listOf(spot(680f, 480f, 70f))))
        HostKind.PRINTER -> KindLook(R.drawable.ic_print, Slide(3000, Rect(320f, 850f, 640f, 890f)))
        HostKind.CAMERA -> KindLook(R.drawable.ic_videocam, Glows(2000, listOf(spot(250f, 330f, 40f))))
        HostKind.STORAGE -> KindLook(R.drawable.ic_hard_drive, Glows(3000, listOf(spot(680f, 560f, 100f)), ::flicker))
        HostKind.SMART_HOME -> KindLook(R.drawable.ic_home_iot_device, Arcs(4000, middle, listOf(500f), 0f, 360f))
        HostKind.WEARABLE -> KindLook(R.drawable.ic_watch, Glows(2000, listOf(spot(480f, 480f, 200f))))
        HostKind.SERVER -> KindLook(R.drawable.ic_dns, Glows(2400, listOf(spot(300f, 300f, 100f), spot(300f, 700f, 100f))))
        HostKind.UNKNOWN -> KindLook(R.drawable.ic_device_unknown, null)
    }

private val middle = Offset(480f, 480f)

/** From the left port of the switch symbol, up through the hub and down to the right port. */
private val switchLinks = listOf(Offset(280f, 600f), Offset(280f, 480f), Offset(680f, 480f), Offset(680f, 600f))

// Calm motion: an idle moves at most 2 dp or changes at most 20 % alpha.
private const val IDLE_ALPHA = 0.2f
private val IDLE_TRAVEL = 2.dp
private const val GRID = 960f
private const val ARC_WIDTH = 60f
private const val TRAVEL_DOT = 70f

/** Elements of an idle light up one after another, each over one period. */
private sealed interface Idle {
    val periodMs: Int
}

/** Soft glows at fixed spots, such as status lights. */
private class Glows(
    override val periodMs: Int,
    val spots: List<Rect>,
    val wave: (Float) -> Float = ::pulse,
) : Idle

/** Arcs around a centre, such as signal or sound waves; [grow] moves them outwards as they fade. */
private class Arcs(
    override val periodMs: Int,
    val center: Offset,
    val radii: List<Float>,
    val startDegrees: Float,
    val sweepDegrees: Float,
    val grow: Dp = 0.dp,
) : Idle

/** A glow that travels along a path. */
private class Travel(
    override val periodMs: Int,
    val path: List<Offset>,
) : Idle

/** The whole symbol rocks by two degrees. */
private class Tilt(
    override val periodMs: Int,
) : Idle

/** A faint sheen drifts across an area. */
private class Sheen(
    override val periodMs: Int,
    val area: Rect,
) : Idle

/** A bar slides out by 2 dp and back. */
private class Slide(
    override val periodMs: Int,
    val bar: Rect,
) : Idle

private fun spot(
    x: Float,
    y: Float,
    radius: Float,
) = Rect(Offset(x, y), radius)

/** 0 at the start and end of a period, 1 halfway. */
private fun pulse(t: Float): Float = (1 - cos(2 * PI * t).toFloat()) / 2

private fun blink(t: Float): Float = if (t < 0.5f) 1f else 0f

/** Three quick flashes, then a pause. */
private fun flicker(t: Float): Float = if (t < 0.5f) pulse(t * 6 % 1f) else 0f

/** Element [index] of [count] runs [index]/[count] of a period behind the first. */
private fun phase(
    t: Float,
    index: Int,
    count: Int,
): Float = (t - index.toFloat() / count + 1f) % 1f

@Composable
private fun IdleIcon(
    @DrawableRes icon: Int,
    idle: Idle,
    size: Dp,
    color: Color,
) {
    val transition = rememberInfiniteTransition(label = "idle")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(idle.periodMs, easing = LinearEasing)), label = "idle")
    Box(Modifier.size(size)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = color,
            modifier =
                Modifier.size(size).graphicsLayer {
                    if (idle is Tilt) rotationZ = 2f * sin(2 * PI * t).toFloat()
                },
        )
        Canvas(Modifier.size(size)) { drawIdle(idle, t, color) }
    }
}

private fun DrawScope.drawIdle(
    idle: Idle,
    t: Float,
    color: Color,
) {
    val unit = size.width / GRID
    when (idle) {
        is Glows -> {
            idle.spots.forEachIndexed { i, spot ->
                val glow = color.copy(alpha = IDLE_ALPHA * idle.wave(phase(t, i, idle.spots.size)))
                val radius = spot.minDimension * unit / 2
                drawRoundRect(glow, spot.topLeft * unit, spot.size * unit, CornerRadius(radius))
            }
        }

        is Arcs -> {
            idle.radii.forEachIndexed { i, r ->
                val progress = phase(t, i, idle.radii.size)
                val radius = r * unit + idle.grow.toPx() * progress
                drawArc(
                    color.copy(alpha = IDLE_ALPHA * pulse(progress)),
                    idle.startDegrees,
                    idle.sweepDegrees,
                    useCenter = false,
                    topLeft = idle.center * unit - Offset(radius, radius),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(ARC_WIDTH * unit, cap = StrokeCap.Round),
                )
            }
        }

        is Travel -> {
            drawCircle(color.copy(alpha = IDLE_ALPHA * pulse(t)), TRAVEL_DOT * unit, pointAlong(idle.path, t) * unit)
        }

        is Sheen -> {
            val area = Rect(idle.area.topLeft * unit, idle.area.size * unit)
            val band = area.width / 2
            val start = area.left - band + (area.width + band) * t
            val sheen = listOf(Color.Transparent, color.copy(alpha = IDLE_ALPHA), Color.Transparent)
            drawRect(Brush.horizontalGradient(sheen, start, start + band), area.topLeft, area.size)
        }

        is Slide -> {
            drawRect(color, idle.bar.topLeft * unit + Offset(0f, IDLE_TRAVEL.toPx() * pulse(t)), idle.bar.size * unit)
        }

        // A rotation of the whole layer, applied in IdleIcon.
        is Tilt -> {}
    }
}

/** The point [t] of the way along a polyline. */
private fun pointAlong(
    path: List<Offset>,
    t: Float,
): Offset {
    val segments = path.zipWithNext()
    val lengths = segments.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
    var remaining = lengths.sum() * t
    segments.forEachIndexed { i, (a, b) ->
        if (remaining <= lengths[i]) return a + (b - a) * (remaining / lengths[i])
        remaining -= lengths[i]
    }
    return path.last()
}
