package no.cyberfish.dekoi.ui.features

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.FeatureState
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.heardAccessPoints
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.signalReach
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.components.FishMark
import no.cyberfish.dekoi.ui.components.checkMark
import no.cyberfish.dekoi.ui.components.drawAlarm
import no.cyberfish.dekoi.ui.components.rememberAlarm
import no.cyberfish.dekoi.ui.components.severityGlyph
import no.cyberfish.dekoi.wifiFinding
import no.cyberfish.dekoi.wifiGuardState
import java.time.Instant

private const val EVIL_TWIN = "evil_twin"
private const val SHOWN = 6
private const val WAVE_MS = 4000
private const val WAVES = 2
private const val WAVE_ALPHA = 0.3f
private const val GLOW_ALPHA = 0.25f
private const val FIRST_ANGLE = -50f
private const val NEAREST = 0.2f
private const val DASH = 3f
private val SCENE = 200.dp
private val MARK = 20.dp
private val GUARD = 32.dp
private val NODE = 8.dp
private val STROKE = 1.5.dp
private val GLOW = 10.dp
private val VERDICT_ICON = 20.dp

/**
 * Wi-Fi Guard at a glance (ADR 0019). The DeKoi in the middle, inside the
 * ring of its watch in the colour of its state; around it the access
 * points with the home Wi-Fi's name it heard, nearer the stronger they came
 * in, a copy dashed. One that still matters is in the colour of its alert:
 * a copy plays the alarm of a very likely attack, a new access point is
 * ringed in amber and holds still. While [playing] and on, waves go out
 * from the DeKoi. Under it the Wi-Fi's name and the finding, which opens
 * its alert, or that no copy is near.
 */
@Composable
fun WifiGuardView(
    device: Device,
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
    playing: Boolean,
    onNavigate: (Route) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = device.wifiGuardState(now)
    val heard = heardAccessPoints(device.id, events).take(SHOWN)
    val recent = recentIntrusions(heard, trustedMacs, now).toSet()
    val finding = wifiFinding(heard, trustedMacs, now)
    val colors = LocalSeverityColors.current
    val guard = checkMark(state).second
    val blue = MaterialTheme.colorScheme.primary
    val waves = if (playing && state.isOn && !LocalReduceMotion.current) waves() else null
    val alarm = if (finding?.severity == Severity.CRITICAL) rememberAlarm() else null
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Box(Modifier.fillMaxWidth().height(SCENE).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.matchParentSize()) {
                val inner = GUARD.toPx()
                val edge = size.minDimension / 2 - NODE.toPx()
                waves?.let { drawWaves(inner, edge, blue, it.value) }
                drawGuard(inner, guard)
                heard.forEachIndexed { i, point ->
                    val angle = FIRST_ANGLE + 360f * i / heard.size
                    val reach = NEAREST + (1 - NEAREST) * signalReach(point.detail.signalDbm)
                    val at = center + direction(angle) * (inner + (edge - inner) * reach)
                    val color = if (point in recent) colors.of(point.severity).color else colors.offline
                    if (point == finding) {
                        if (alarm != null) {
                            drawAlarm(alarm.value, color, NODE.toPx(), at, edge / 2)
                        } else {
                            drawCircle(color, NODE.toPx() * 2, at, style = Stroke(STROKE.toPx()))
                        }
                    }
                    drawAccessPoint(at, color, copy = point.kind == EVIL_TWIN)
                }
            }
            FishMark(MARK)
        }
        Text(
            device.wifiName ?: stringResource(R.string.wifi_name_not_set),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        when {
            finding != null -> {
                Finding(finding, onNavigate)
            }

            state == FeatureState.ON -> {
                Verdict(
                    R.drawable.ic_check_circle,
                    stringResource(R.string.wifi_guard_protected),
                    colors.safe.color,
                )
            }
        }
        if (heard.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.wifi_guard_heard, heard.size, heard.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** What Wi-Fi Guard found, in the colour of its alert, and the way to the alert. */
@Composable
private fun Finding(
    finding: Event,
    onNavigate: (Route) -> Unit,
) {
    val words = if (finding.kind == EVIL_TWIN) R.string.wifi_guard_copy else R.string.wifi_guard_new
    Verdict(severityGlyph(finding.severity), stringResource(words), LocalSeverityColors.current.of(finding.severity).color)
    TextButton(onClick = { onNavigate(Route.AlertDetail(finding.id)) }) { Text(stringResource(R.string.home_see_what_to_do)) }
}

@Composable
private fun Verdict(
    @DrawableRes glyph: Int,
    words: String,
    color: Color,
) {
    Row(
        Modifier.semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(VERDICT_ICON), tint = color)
        Text(words, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun waves(): State<Float> =
    rememberInfiniteTransition(label = "wifi")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(WAVE_MS, easing = LinearEasing)), label = "wifi")

/** The watch's ring around the DeKoi, with a soft glow of the same colour. */
private fun DrawScope.drawGuard(
    radius: Float,
    color: Color,
) {
    val glow = GLOW.toPx()
    drawCircle(
        Brush.radialGradient(
            (radius - glow) / (radius + glow) to Color.Transparent,
            radius / (radius + glow) to color.copy(alpha = GLOW_ALPHA),
            1f to Color.Transparent,
            center = center,
            radius = radius + glow,
        ),
        radius + glow,
    )
    drawCircle(color, radius, style = Stroke(STROKE.toPx()))
}

/** Rings that leave the guard and fade towards the edge, [WAVES] at a time. */
private fun DrawScope.drawWaves(
    inner: Float,
    outer: Float,
    color: Color,
    progress: Float,
) {
    for (wave in 0 until WAVES) {
        val phase = (progress + wave.toFloat() / WAVES) % 1f
        drawCircle(color.copy(alpha = WAVE_ALPHA * (1 - phase)), inner + (outer - inner) * phase, style = Stroke(STROKE.toPx()))
    }
}

/** An access point as the Wi-Fi symbol: a dot under two arcs; dashed for a copy. */
private fun DrawScope.drawAccessPoint(
    at: Offset,
    color: Color,
    copy: Boolean,
) {
    val node = NODE.toPx()
    val stroke = STROKE.toPx()
    val dashes = if (copy) PathEffect.dashPathEffect(floatArrayOf(DASH * stroke, DASH * stroke)) else null
    val base = at + Offset(0f, node / 2)
    drawCircle(color, stroke * 1.2f, base)
    listOf(node * 0.6f, node * 1.1f).forEach { r ->
        drawArc(
            color,
            225f,
            90f,
            useCenter = false,
            topLeft = base - Offset(r, r),
            size = Size(2 * r, 2 * r),
            style = Stroke(stroke, cap = StrokeCap.Round, pathEffect = dashes),
        )
    }
}
