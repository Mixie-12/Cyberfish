package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion

private const val DRAW_IN_MS = 700
private const val CHANGE_MS = 300
private val STROKE = 10.dp
private val SHIELD = 48.dp

/**
 * The status hero: a ring around a shield in the status colour. The ring
 * draws in once when it appears and moves to a new [fraction] when that
 * changes, such as a setup step completing. Decorative; the status text
 * beside it carries the meaning.
 */
@Composable
fun StatusRing(
    color: Color,
    modifier: Modifier = Modifier,
    fraction: Float = 1f,
) {
    val reduceMotion = LocalReduceMotion.current
    val sweep = remember { Animatable(if (reduceMotion) fraction else 0f) }
    LaunchedEffect(fraction, reduceMotion) {
        if (reduceMotion) sweep.snapTo(fraction) else sweep.animateTo(fraction, tween(DRAW_IN_MS, easing = FastOutSlowInEasing))
    }
    val ring by animateColorAsState(color, if (reduceMotion) snap() else tween(CHANGE_MS), label = "ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.size(Dimens.ring), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = STROKE.toPx()
            val topLeft = Offset(stroke / 2, stroke / 2)
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            drawArc(
                ring,
                -90f,
                360f * sweep.value,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        Icon(painterResource(R.drawable.ic_shield), contentDescription = null, modifier = Modifier.size(SHIELD), tint = ring)
    }
}
