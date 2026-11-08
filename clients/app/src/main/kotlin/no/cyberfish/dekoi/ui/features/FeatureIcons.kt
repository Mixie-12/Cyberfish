package no.cyberfish.dekoi.ui.features

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Feature
import no.cyberfish.dekoi.ui.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A feature's drawing: thin grey lines for the network, blue for what
 * DeKoi does. While [playing], one slow loop shows it at work; otherwise,
 * and always when animations are off, it holds the frame that tells the
 * story on its own. Decorative: the card says the same in words.
 */
@Composable
fun FeatureIcon(
    feature: Feature,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val loop = if (playing && !LocalReduceMotion.current) loop(loopMs(feature)) else null
    FeatureDrawing(feature, { loop?.value }, modifier)
}

/**
 * [feature]'s drawing at [frame], how far through its loop it is, read
 * while drawing; its still frame while that is null. Callers that play it
 * keep to one loop on screen.
 */
@Composable
fun FeatureDrawing(
    feature: Feature,
    frame: () -> Float?,
    modifier: Modifier = Modifier,
) {
    val ink = Ink(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onSurfaceVariant)
    Canvas(modifier.clearAndSetSemantics { }) {
        Stage(this, ink).draw(feature, frame() ?: look(feature).still)
    }
}

/** How long one loop of [feature]'s drawing takes. */
fun loopMs(feature: Feature): Int = look(feature).periodMs

/** How long one loop takes, and the frame shown when still. */
private class Look(
    val periodMs: Int,
    val still: Float,
)

private fun look(feature: Feature): Look =
    when (feature) {
        Feature.DECOY -> Look(4000, 0.6f)
        Feature.IDS -> Look(3000, 0.45f)
        Feature.SCANS -> Look(4000, 0.125f)
        Feature.ARP_GUARD -> Look(4000, 0.5f)
        Feature.ROUTER_GUARD -> Look(4000, 0.5f)
        Feature.NAME_TRAP -> Look(4000, 0.8f)
        Feature.WIFI_GUARD -> Look(5000, 0.6f)
        Feature.NETWORK_MAP -> Look(6000, 0.42f)
        Feature.NEW_DEVICES -> Look(4000, 0.5f)
        Feature.INTERNET_HEALTH -> Look(3000, 0.5f)
        Feature.OPEN_PORTS -> Look(3000, 0.5f)
        Feature.WEEKLY_SUMMARY -> Look(5000, 0.8f)
    }

@Composable
private fun loop(periodMs: Int): State<Float> =
    rememberInfiniteTransition(label = "feature")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "feature")

private class Ink(
    val accent: Color,
    val line: Color,
)

// Drawings are laid out on a 48 by 48 grid and scaled to fit.
private const val GRID = 48f
private const val CENTRE = 24f
private const val FAINT = 0.35f
private const val GLOW = 0.5f
private const val DASH = 2f
private const val MAP_NODES = 6
private const val KNOWN_NODES = 5
private const val SWEEP_TRAIL = 120f
private const val BAR_STEP = 0.08f
private val STROKE = 1.5.dp

/** A unit step at [degrees] clockwise from three o'clock, as drawings place things around a centre. */
internal fun direction(degrees: Float): Offset {
    val r = degrees * PI.toFloat() / 180f
    return Offset(cos(r), sin(r))
}

/** 0 at the start and end of a period, 1 halfway. */
private fun pulse(t: Float): Float = (1 - cos(2 * PI * t).toFloat()) / 2

/** How far [t] is from [start] to [end], kept within 0..1. */
private fun span(
    t: Float,
    start: Float,
    end: Float,
): Float = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** 1 for most of the loop, fading out over its last tenth so it starts again from nothing. */
private fun fadeAtEnd(t: Float): Float = 1 - span(t, 0.9f, 1f)

private class Stage(
    private val scope: DrawScope,
    private val ink: Ink,
) {
    private val unit = scope.size.minDimension / GRID
    private val stroke = with(scope) { STROKE.toPx() }
    private val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH * unit, DASH * unit))
    private val faint = ink.line.copy(alpha = FAINT)

    fun draw(
        feature: Feature,
        t: Float,
    ) = when (feature) {
        Feature.DECOY -> decoy(t)
        Feature.IDS -> ids(t)
        Feature.SCANS -> scans(t)
        Feature.ARP_GUARD -> arpGuard(t)
        Feature.ROUTER_GUARD -> routerGuard(t)
        Feature.NAME_TRAP -> nameTrap(t)
        Feature.WIFI_GUARD -> wifiGuard(t)
        Feature.NETWORK_MAP -> networkMap(t)
        Feature.NEW_DEVICES -> newDevices(t)
        Feature.INTERNET_HEALTH -> internetHealth(t)
        Feature.OPEN_PORTS -> openPorts(t)
        Feature.WEEKLY_SUMMARY -> weeklySummary(t)
    }

    private fun at(
        x: Float,
        y: Float,
    ) = Offset(x * unit, y * unit)

    private fun line(
        from: Offset,
        to: Offset,
        color: Color,
        dashed: Boolean = false,
    ) = scope.drawLine(color, from, to, stroke, StrokeCap.Round, if (dashed) dashes else null)

    private fun ring(
        center: Offset,
        radius: Float,
        color: Color,
        dashed: Boolean = false,
    ) {
        if (radius <= 0f) return
        scope.drawCircle(color, radius * unit, center, style = Stroke(stroke, pathEffect = if (dashed) dashes else null))
    }

    private fun dot(
        center: Offset,
        radius: Float,
        color: Color,
    ) = scope.drawCircle(color, radius * unit, center)

    private fun box(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        color: Color,
        dashed: Boolean = false,
    ) = scope.drawRoundRect(
        color,
        at(left, top),
        Size((right - left) * unit, (bottom - top) * unit),
        CornerRadius(2 * unit),
        style = Stroke(stroke, pathEffect = if (dashed) dashes else null),
    )

    private fun glow(
        center: Offset,
        radius: Float,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        val r = radius * unit
        scope.drawCircle(Brush.radialGradient(listOf(ink.accent.copy(alpha = alpha), Color.Transparent), center, r), r, center)
    }

    private fun polyline(points: List<Offset>): Path =
        Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }

    // A server with drive bays; someone walks up to it and the server answers with a ring.
    private fun decoy(t: Float) {
        box(16f, 8f, 32f, 40f, ink.line)
        listOf(16f, 22f, 28f).forEach { y -> line(at(19f, y), at(29f, y), faint) }
        dot(at(28.5f, 35f), 1.2f, ink.accent)
        val walk = span(t, 0f, 0.4f)
        val shown = fadeAtEnd(t)
        line(at(2f, 24f), at(2f + 11f * walk, 24f), faint.copy(alpha = FAINT * shown), dashed = true)
        dot(at(2f + 11f * walk, 24f), 1.8f, ink.accent.copy(alpha = shown))
        val answer = span(t, 0.4f, 0.9f)
        if (answer <= 0f || answer >= 1f) return
        val grow = 6f * answer
        scope.drawRoundRect(
            ink.accent.copy(alpha = 1 - answer),
            at(16f - grow, 8f - grow),
            Size((16f + 2 * grow) * unit, (32f + 2 * grow) * unit),
            CornerRadius((2f + grow) * unit),
            style = Stroke(stroke),
        )
    }

    // A shield that a line of light scans from top to bottom.
    private fun ids(t: Float) {
        val shield =
            Path().apply {
                moveTo(24f * unit, 5f * unit)
                lineTo(39f * unit, 10f * unit)
                lineTo(39f * unit, 22f * unit)
                cubicTo(39f * unit, 32f * unit, 32f * unit, 38f * unit, 24f * unit, 43f * unit)
                cubicTo(16f * unit, 38f * unit, 9f * unit, 32f * unit, 9f * unit, 22f * unit)
                lineTo(9f * unit, 10f * unit)
                close()
            }
        scope.drawPath(shield, ink.line, style = Stroke(stroke))
        listOf(17f, 24f, 31f).forEachIndexed { i, y -> line(at(14f + 2f * i, y), at(34f - 2f * i, y), faint, dashed = true) }
        val y = (8f + 33f * t) * unit
        val light = ink.accent.copy(alpha = pulse(t))
        scope.clipPath(shield) {
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, ink.accent.copy(alpha = GLOW * pulse(t) / 2)), y - 6f * unit, y),
                Offset(0f, y - 6f * unit),
                Size(size.width, 6f * unit),
            )
            drawLine(light, Offset(0f, y), Offset(size.width, y), stroke)
        }
    }

    // A radar: the sweep turns, and a device it passes over lights up.
    private fun scans(t: Float) {
        val centre = at(CENTRE, CENTRE)
        listOf(9f, 18f).forEach { ring(centre, it, faint) }
        line(at(CENTRE, 4f), at(CENTRE, 44f), faint)
        line(at(4f, CENTRE), at(44f, CENTRE), faint)
        val angle = 360f * t
        line(centre, centre + direction(angle) * (18f * unit), ink.accent)
        listOf(70f, 200f, 310f).forEach { degrees ->
            val since = ((angle - degrees) % 360f + 360f) % 360f
            val place = centre + direction(degrees) * (13f * unit)
            dot(place, 1.6f, ink.line)
            glow(place, 5f, GLOW * (1 - since / SWEEP_TRAIL).coerceAtLeast(0f))
        }
    }

    // Two devices claim the same place; the copy, dashed, is ringed while it shows itself.
    private fun arpGuard(t: Float) {
        val router = at(CENTRE, 10f)
        box(19f, 5f, 29f, 15f, ink.line)
        val real = at(13f, 34f)
        val copy = at(35f, 34f)
        line(router, real, faint)
        ring(real, 5f, ink.line)
        dot(real, 1.5f, ink.line)
        val shown = pulse(t)
        line(router, copy, faint.copy(alpha = FAINT * shown), dashed = true)
        ring(copy, 5f, ink.line.copy(alpha = shown), dashed = true)
        dot(copy, 1.5f, ink.line.copy(alpha = shown))
        ring(copy, 8f, ink.accent.copy(alpha = shown))
    }

    // The router hands settings to the devices below; a second, dashed one tries too and is ringed.
    private fun routerGuard(t: Float) {
        box(6f, 10f, 20f, 18f, ink.line)
        line(at(9f, 10f), at(8f, 5f), ink.line)
        line(at(17f, 10f), at(18f, 5f), ink.line)
        val rogue = pulse(t)
        box(28f, 10f, 42f, 18f, ink.line.copy(alpha = rogue), dashed = true)
        ring(at(35f, 14f), 11f, ink.accent.copy(alpha = rogue))
        val source = at(13f, 18f)
        val travel = (t * 2) % 1f
        listOf(at(10f, 40f), at(24f, 40f), at(38f, 40f)).forEach { device ->
            line(source, device, faint, dashed = true)
            dot(device, 2f, ink.line)
            dot(source + (device - source) * travel, 1.2f, ink.accent.copy(alpha = pulse(travel)))
        }
    }

    // DeKoi asks for a name that does not exist; a device that answers is caught between brackets.
    private fun nameTrap(t: Float) {
        val centre = at(16f, 30f)
        dot(centre, 2.5f, ink.accent)
        val ask = span(t, 0f, 0.6f)
        if (ask < 1f) ring(centre, 4f + 22f * ask, ink.accent.copy(alpha = 1 - ask))
        val caught = span(t, 0.55f, 0.75f) * fadeAtEnd(t)
        val target = at(36f, 12f)
        dot(target, 2f, ink.line)
        glow(target, 7f, GLOW * caught)
        val reach = (7f - 2f * caught) * unit
        listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEach { (dx, dy) ->
            val corner = target + Offset(dx * reach, dy * reach)
            line(corner, corner - Offset(dx * 3f * unit, 0f), ink.line)
            line(corner, corner - Offset(0f, dy * 3f * unit), ink.line)
        }
    }

    // The home Wi-Fi, and a dashed copy of it nearby that fades in and is ringed.
    private fun wifiGuard(t: Float) {
        arcs(at(14f, 34f), ink.line, dashed = false)
        val copy = span(t, 0.1f, 0.4f) * fadeAtEnd(t)
        arcs(at(34f, 34f), ink.line.copy(alpha = copy), dashed = true)
        ring(at(34f, 27f), 13f, ink.accent.copy(alpha = copy * span(t, 0.35f, 0.5f)))
    }

    private fun arcs(
        centre: Offset,
        color: Color,
        dashed: Boolean,
    ) {
        dot(centre, 1.6f, color)
        listOf(5f, 10f, 15f).forEach { r ->
            scope.drawArc(
                color,
                225f,
                90f,
                useCenter = false,
                topLeft = centre - Offset(r * unit, r * unit),
                size = Size(2 * r * unit, 2 * r * unit),
                style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = if (dashed) dashes else null),
            )
        }
    }

    /** Node [index] of [count] around the centre, the first at the top. */
    private fun around(
        index: Int,
        count: Int,
        radius: Float,
    ): Offset = at(CENTRE, CENTRE) + direction(360f * index / count - 90f) * (radius * unit)

    // DeKoi among the devices it lists; a light travels out to each in turn.
    private fun networkMap(t: Float) {
        val centre = at(CENTRE, CENTRE)
        val nodes = (0 until MAP_NODES).map { around(it, MAP_NODES, 17f) }
        nodes.forEach { line(centre, it, faint) }
        nodes.forEach { ring(it, 3f, ink.line) }
        dot(centre, 3f, ink.accent)
        val leg = t * MAP_NODES
        val target = nodes[leg.toInt() % MAP_NODES]
        dot(centre + (target - centre) * pulse(leg % 1f), 1.2f, ink.accent)
    }

    // Known devices around DeKoi, and a new one that appears with a ring.
    private fun newDevices(t: Float) {
        val centre = at(CENTRE, CENTRE)
        (0 until KNOWN_NODES).map { around(it, MAP_NODES, 16f) }.forEach {
            line(centre, it, faint)
            ring(it, 3f, ink.line)
        }
        dot(centre, 3f, ink.accent)
        val arrive = span(t, 0.15f, 0.35f) * fadeAtEnd(t)
        val newcomer = around(KNOWN_NODES, MAP_NODES, 16f)
        line(centre, newcomer, ink.accent.copy(alpha = FAINT * arrive), dashed = true)
        ring(newcomer, 3f * arrive, ink.accent)
        val call = span(t, 0.35f, 0.75f)
        if (call < 1f) ring(newcomer, 3f + 5f * call, ink.accent.copy(alpha = arrive * (1 - call)))
    }

    // The globe of the internet with a pulse line across it; a light runs along the line.
    private fun internetHealth(t: Float) {
        ring(at(CENTRE, CENTRE), 15f, faint)
        scope.drawOval(faint, at(17f, 9f), Size(14f * unit, 30f * unit), style = Stroke(stroke))
        val beat = heartbeat.map { (x, y) -> at(x, y) }
        scope.drawPath(polyline(beat), ink.accent, style = Stroke(stroke, cap = StrokeCap.Round))
        val light = pointAlong(beat, t)
        glow(light, 5f, GLOW * pulse(t))
        dot(light, 1.4f, ink.accent.copy(alpha = pulse(t)))
    }

    private val heartbeat =
        listOf(2f to 26f, 15f to 26f, 18f to 19f, 22f to 33f, 26f to 21f, 29f to 26f, 46f to 26f)

    // A home with its door open to the internet; something comes in through it and goes again.
    private fun openPorts(t: Float) {
        val wall = polyline(listOf(at(23f, 21f), at(23f, 15f), at(5f, 15f), at(5f, 36f), at(23f, 36f), at(23f, 29f)))
        scope.drawPath(wall, ink.line, style = Stroke(stroke, cap = StrokeCap.Round))
        line(at(3f, 16f), at(14f, 7f), ink.line)
        line(at(14f, 7f), at(25f, 16f), ink.line)
        line(at(23f, 21f), at(23f, 29f), ink.accent.copy(alpha = FAINT + (1 - FAINT) * pulse(t)))
        val world = at(39f, 25f)
        ring(world, 6f, ink.line)
        line(at(33f, 25f), at(45f, 25f), faint)
        line(at(26f, 25f), at(33f, 25f), faint, dashed = true)
        dot(at(33f - 15f * pulse(t), 25f), 1.5f, ink.accent)
    }

    // A week of bars that rise one after another, then rest.
    private fun weeklySummary(t: Float) {
        line(at(6f, 40f), at(42f, 40f), ink.line)
        line(at(10f, 7f), at(38f, 7f), faint)
        val shown = fadeAtEnd(t)
        week.forEachIndexed { i, height ->
            val grow = span(t, i * BAR_STEP, (i + 2) * BAR_STEP)
            if (grow <= 0f) return@forEachIndexed
            val x = 9f + i * 5f
            val color = if (i == week.lastIndex) ink.accent else ink.line
            line(at(x, 38f), at(x, 38f - height * grow), color.copy(alpha = shown))
        }
    }

    private val week = listOf(10f, 18f, 8f, 22f, 13f, 20f, 15f)

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
}
