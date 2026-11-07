package no.cyberfish.dekoi.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.tabular

private const val DRAW_IN_MS = 500
private val BAR_CHART_HEIGHT = 120.dp
private val BAR_RADIUS = 2.dp
private val BAR_GAP = 6.dp
private val SPARK_STROKE = 2.dp
private val LIST_BAR = 4.dp
private val STRIP_HEIGHT = 24.dp
private const val SPARK_FILL_ALPHA = 0.2f

/**
 * One bar of a [StackedBarChart]: its segment values from the bottom up,
 * the short label under it, and the sentence TalkBack reads for it.
 */
@Immutable
class Bar(
    val values: List<Int>,
    val label: String,
    val description: String,
)

/** 0 to 1 once when a chart first appears; 1 at once when animations are off. */
@Composable
private fun drawIn(): Animatable<Float, *> {
    val reduceMotion = LocalReduceMotion.current
    val progress = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(DRAW_IN_MS, easing = FastOutSlowInEasing)) }
    return progress
}

/**
 * Bars of stacked segments, such as alerts per day by severity, with
 * [colors] for the segments from the bottom up. Each bar is a button.
 */
@Composable
fun StackedBarChart(
    bars: List<Bar>,
    colors: List<Color>,
    onBarClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = drawIn()
    val highest = bars.maxOfOrNull { it.values.sum() }?.coerceAtLeast(1) ?: 1
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier.fillMaxWidth().drawBehind {
            // The grid: top, middle and base of the bars.
            val line = Dimens.hairline.toPx()
            val base = BAR_CHART_HEIGHT.toPx() - line / 2
            for (y in listOf(line / 2, base / 2, base)) drawLine(empty, Offset(0f, y), Offset(size.width, y), line)
        },
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
    ) {
        bars.forEachIndexed { index, bar ->
            Column(
                Modifier
                    .weight(1f)
                    .clickable(role = Role.Button) { onBarClick(index) }
                    .clearAndSetSemantics { contentDescription = bar.description },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.fillMaxWidth().height(BAR_CHART_HEIGHT).drawBehind {
                        val total = bar.values.sum()
                        if (total == 0) {
                            drawRect(empty, Offset(0f, size.height - BAR_RADIUS.toPx()), Size(size.width, BAR_RADIUS.toPx()))
                            return@drawBehind
                        }
                        val height = size.height * total / highest * progress.value
                        val top = size.height - height
                        val radius = CornerRadius(BAR_RADIUS.toPx())
                        val shape = RoundRect(0f, top, size.width, size.height, topLeftCornerRadius = radius, topRightCornerRadius = radius)
                        clipPath(Path().apply { addRoundRect(shape) }) {
                            var bottom = size.height
                            bar.values.forEachIndexed { segment, value ->
                                val segmentHeight = height * value / total
                                drawRect(colors[segment], Offset(0f, bottom - segmentHeight), Size(size.width, segmentHeight))
                                bottom -= segmentHeight
                            }
                        }
                    },
                )
                Text(
                    bar.label,
                    style = MaterialTheme.typography.labelMedium.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Dimens.spaceXs),
                )
            }
        }
    }
}

/** A small trend line with a glow under it that fades to nothing. Decorative: say the numbers in text beside it. */
@Composable
fun Sparkline(
    values: List<Int>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val progress = drawIn()
    Canvas(modifier.clearAndSetSemantics { }) {
        if (values.size < 2) return@Canvas
        val stroke = SPARK_STROKE.toPx()
        val highest = values.max().coerceAtLeast(1)
        val step = size.width / (values.size - 1)
        val ltr = layoutDirection == LayoutDirection.Ltr
        val points =
            values.mapIndexed { i, v ->
                Offset(if (ltr) i * step else size.width - i * step, stroke / 2 + (size.height - stroke) * (1f - v.toFloat() / highest))
            }
        val line = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
        val fill =
            Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height)
                lineTo(points.first().x, size.height)
                close()
            }
        val shown = size.width * progress.value
        clipRect(left = if (ltr) 0f else size.width - shown, right = if (ltr) shown else size.width) {
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = SPARK_FILL_ALPHA), Color.Transparent)))
            drawPath(line, color, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Labelled horizontal bars sized against the largest value, such as devices per kind. */
@Composable
fun BarList(
    items: List<Pair<String, Int>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val progress = drawIn()
    val highest = items.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val track = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        items.forEach { (label, value) ->
            Column(Modifier.semantics(mergeDescendants = true) { }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        value.toString(),
                        style = MaterialTheme.typography.bodyMedium.tabular(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Canvas(Modifier.fillMaxWidth().padding(top = Dimens.spaceXs).height(LIST_BAR)) {
                    val radius = CornerRadius(size.height / 2)
                    drawRoundRect(track, cornerRadius = radius)
                    drawRoundRect(color, size = Size(size.width * value / highest * progress.value, size.height), cornerRadius = radius)
                }
            }
        }
    }
}

/**
 * One segment per hour, oldest at the start: online, offline or not known. Colour
 * cannot carry this alone, so [description] says it in words for TalkBack
 * and callers show a summary beside it.
 */
@Composable
fun UptimeStrip(
    hours: List<Uptime>,
    description: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSeverityColors.current
    val unknown = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (hours.isEmpty()) return@Canvas
        val width = size.width / hours.size
        val ltr = layoutDirection == LayoutDirection.Ltr
        val strip = RoundRect(0f, 0f, size.width, size.height, CornerRadius(BAR_RADIUS.toPx()))
        clipPath(Path().apply { addRoundRect(strip) }) {
            hours.forEachIndexed { i, hour ->
                val color =
                    when (hour) {
                        Uptime.ONLINE -> colors.safe.color
                        Uptime.OFFLINE -> colors.offline
                        Uptime.UNKNOWN -> unknown
                    }
                // Overlap by a pixel so no hairline gaps show between segments.
                val x = if (ltr) i * width else size.width - (i + 1) * width
                drawRect(color, Offset(x, 0f), Size(width + 1f, size.height))
            }
        }
    }
}
