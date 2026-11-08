package no.cyberfish.dekoi.ui.features

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.decoyTouches
import no.cyberfish.dekoi.highestSeverity
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.address
import no.cyberfish.dekoi.ui.components.KindTile
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

private const val SWEEP_MS = 6000
private const val TRAIL = 90f
private const val FIRST_ANGLE = -90f
private const val SPOKE_ALPHA = 0.3f
private const val SWEEP_ALPHA = 0.22f
private const val GLOW_ALPHA = 0.5f
private const val SEPARATOR = " · "
private val SCENE = 200.dp
private val LABEL_ROOM = 34.dp
private val NODE = 5.dp
private val GLOW = 14.dp
private val STROKE = 1.5.dp

/**
 * The decoy at a glance (ADR 0018): a server in the middle and around it
 * the services it offers, named as the DeKoi reports them. A service that
 * something touched in the last day is in the colour of its most serious
 * touch, with how many times. While [on] it is blue, grey otherwise; while
 * [playing], a slow sweep turns around it, lighting each service as it
 * listens. Under it the name the decoy goes by and how often it was
 * touched.
 */
@Composable
fun DecoyView(
    device: Device,
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
    on: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
) {
    val touches = device.decoyTouches(events, trustedMacs, now)
    val worst = highestSeverity(touches.values.flatten())
    val colors = LocalSeverityColors.current
    val calm = if (on) MaterialTheme.colorScheme.primary else colors.offline
    val measurer = rememberTextMeasurer()
    val labels = MaterialTheme.typography.labelMedium
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    val sweep = if (playing && on && !LocalReduceMotion.current) sweep() else null
    val services = device.services
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(SCENE)
                .clipToBounds()
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val ring = size.minDimension / 2 - LABEL_ROOM.toPx()
                val hub = Dimens.tile.toPx() / 2 + NODE.toPx()
                val beam = sweep?.let { FIRST_ANGLE + 360f * it.value }
                beam?.let { drawBeam(ring, calm, it) }
                services.forEachIndexed { i, service ->
                    val degrees = FIRST_ANGLE + 360f * i / services.size
                    val way = direction(degrees)
                    val at = center + way * ring
                    val touched = touches[service]
                    val color = touched?.let { colors.of(highestSeverity(it) ?: Severity.WARNING).color } ?: calm
                    drawLine(color.copy(alpha = SPOKE_ALPHA), center + way * hub, at, STROKE.toPx())
                    val passed = beam?.let { ((it - degrees) % 360f + 360f) % 360f }
                    val lit = if (touched != null) GLOW_ALPHA else passed?.let { GLOW_ALPHA * (1 - it / TRAIL).coerceAtLeast(0f) } ?: 0f
                    drawNode(at, color, lit, filled = touched != null)
                    val words =
                        listOfNotNull(
                            service.uppercase(Locale.ROOT),
                            touched?.sumOf { it.count }?.toString(),
                        ).joinToString(SEPARATOR)
                    val text = measurer.measure(words, labels.copy(color = if (touched != null) color else grey))
                    val reach = ring + 2 * NODE.toPx() + abs(way.x) * text.size.width / 2 + abs(way.y) * text.size.height / 2
                    val middle = center + way * reach
                    drawText(text, topLeft = middle - Offset(text.size.width / 2f, text.size.height / 2f))
                }
            }
            KindTile(HostKind.SERVER, contentColor = calm)
        }
        Text(
            stringResource(R.string.features_decoy_name, BuildConfig.DECOY_NAME.uppercase(Locale.ROOT)),
            style = MaterialTheme.typography.address,
            textAlign = TextAlign.Center,
        )
        val total = touches.values.sumOf { list -> list.sumOf { it.count } }
        Text(
            if (total ==
                0
            ) {
                stringResource(R.string.dekoi_not_touched_day)
            } else {
                pluralStringResource(R.plurals.dekoi_touched_day, total, total)
            },
            style = MaterialTheme.typography.labelLarge,
            color = worst?.let { colors.of(it).color } ?: grey,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun sweep(): State<Float> =
    rememberInfiniteTransition(label = "decoy")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(SWEEP_MS, easing = LinearEasing)), label = "decoy")

/** The sweep: a line from the server to the ring at [degrees], and a fading wedge behind it. */
private fun DrawScope.drawBeam(
    ring: Float,
    color: Color,
    degrees: Float,
) {
    rotate(degrees - TRAIL, center) {
        drawArc(
            Brush.sweepGradient(0f to Color.Transparent, TRAIL / 360f to color.copy(alpha = SWEEP_ALPHA), center = center),
            startAngle = 0f,
            sweepAngle = TRAIL,
            useCenter = true,
            topLeft = center - Offset(ring, ring),
            size = Size(2 * ring, 2 * ring),
        )
    }
    drawLine(color, center, center + direction(degrees) * ring, STROKE.toPx())
}

/** A service: a ring, filled once touched, with a glow of [glow] alpha behind it. */
private fun DrawScope.drawNode(
    at: Offset,
    color: Color,
    glow: Float,
    filled: Boolean,
) {
    val glowRadius = GLOW.toPx()
    if (glow > 0f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = glow), Color.Transparent), at, glowRadius), glowRadius, at)
    if (filled) drawCircle(color, NODE.toPx(), at) else drawCircle(color, NODE.toPx(), at, style = Stroke(STROKE.toPx()))
}
