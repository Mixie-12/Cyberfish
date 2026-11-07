package no.cyberfish.dekoi.burner

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/** The `><>` mark: white with a blue outline, and a blue glow at hero size only. */
@Composable
internal fun Mark(
    size: TextUnit,
    modifier: Modifier = Modifier,
    glow: Boolean = false,
) {
    val style = Type.mark.copy(fontSize = size, lineHeight = size)
    val outline = with(LocalDensity.current) { size.toPx() / 40 }
    Box(modifier.clearAndSetSemantics {}) {
        Text(MARK, style = style.copy(color = Brand.white, shadow = if (glow) Shadow(Brand.blue, blurRadius = size.value / 2) else null))
        Text(MARK, style = style.copy(color = Brand.blue, drawStyle = Stroke(outline)))
    }
}

private const val MARK = "><>"

/** The mark at the cap height of "DeKoi Setup", 0.6 em before it. */
@Composable
internal fun Lockup() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Mark(20.sp)
        Spacer(Modifier.width(9.dp))
        Text(text("app_name"), style = Type.wordmark.copy(fontSize = 15.sp))
    }
}

/** A glowing horizon with the mark floating above it, its reflection below, and a dark sea. */
@Composable
internal fun Horizon(
    markSize: TextUnit,
    seaHeight: Dp,
    glow: Boolean,
) {
    val gap = 12.dp
    Column(
        Modifier.fillMaxWidth().drawBehind { horizon(lineY = markSize.toPx() + gap.toPx() / 2) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mark(markSize, glow = glow)
        Spacer(Modifier.height(gap))
        Mark(markSize, Modifier.graphicsLayer(scaleY = -1f, alpha = 0.18f))
        Spacer(Modifier.height(seaHeight))
    }
}

private fun DrawScope.horizon(lineY: Float) {
    // Navy fog around the line: as wide as the space, as high as the space above the line.
    val center = Offset(size.width / 2, lineY)
    val radius = size.width / 2
    scale(scaleX = 1f, scaleY = lineY / radius, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(Brand.navy, Color.Transparent), center, radius), radius, center)
    }
    val fade = Brush.horizontalGradient(listOf(Color.Transparent, Brand.blue, Color.Transparent))
    for ((width, alpha) in listOf(12.dp to 0.05f, 5.dp to 0.12f, 1.dp to 0.9f)) {
        drawLine(fade, Offset(0f, lineY), Offset(size.width, lineY), width.toPx(), alpha = alpha)
    }
    // The sea: lines that spread out toward the bottom and fade.
    val depth = size.height - lineY
    for (i in 1..SEA_LINES) {
        val t = i.toFloat() / SEA_LINES
        val y = lineY + depth * t * t
        drawLine(fade, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), alpha = 0.24f * (1 - t))
    }
}

private const val SEA_LINES = 7

/** The HUD frame: one thin blue line with cut corners, and nothing else. */
internal fun Modifier.hudFrame(): Modifier = border(1.dp, Brand.blue.copy(alpha = 0.15f), HudShape).padding(8.dp)

@Composable
internal fun Hairline() = HorizontalDivider(thickness = 1.dp, color = Brand.hairline)

@Composable
internal fun StepLabel(step: Step) = Text(step.label, style = Type.label)

/** Where the flow stands, as five short lines; the step label carries the words. */
@Composable
internal fun StepRail(current: Step) {
    Row(
        Modifier.semantics { contentDescription = text("step_rail", current.ordinal + 1, Step.entries.size) },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (step in Step.entries) {
            Box(Modifier.size(width = 20.dp, height = 2.dp).background(if (step <= current) Brand.blue else Brand.hairline))
        }
    }
}

@Composable
internal fun Heading(text: String) = Text(text.uppercase(Locale.ROOT), Modifier.semantics { heading() }, style = Type.heading)

@Composable
internal fun Body(
    text: String,
    textAlign: TextAlign? = null,
) = Text(text, Modifier.fillMaxWidth(), style = Type.body, textAlign = textAlign)

@Composable
internal fun Secondary(text: String) = Text(text, style = Type.secondary)

@Composable
internal fun Label(text: String) = Text(text.uppercase(Locale.ROOT), style = Type.label.copy(color = Brand.midGrey))

/** An outlined action, or a quiet one; blue never fills it. */
@Composable
internal fun Action(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    Box(
        modifier
            .heightIn(min = 44.dp)
            .border(1.dp, if (primary) Brand.blue else Color.Transparent, ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(Locale.ROOT), style = Type.action.copy(color = if (primary) Brand.white else Brand.text))
    }
}

/** Actions in reading order, which is also the keyboard order: back first, then forward. */
@Composable
internal fun Actions(content: @Composable RowScope.() -> Unit) =
    Row(
        Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )

/** One option of a radio group; the parent sets selectableGroup(). */
@Composable
internal fun Choice(
    title: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        val colors = RadioButtonDefaults.colors(selectedColor = Brand.blue, unselectedColor = Brand.midGrey)
        RadioButton(selected, onClick = null, colors = colors)
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.strong)
            Secondary(detail)
        }
    }
}

/** Rows of a label and a text, divided by hairlines instead of boxes. */
@Composable
internal fun Lines(
    rows: List<Pair<String, String>>,
    labelWidth: Dp,
) {
    val labelStyle = Type.label.copy(color = Brand.midGrey)
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        for ((label, text) in rows) {
            Row(Modifier.padding(vertical = 14.dp)) {
                Text(label.uppercase(Locale.ROOT), Modifier.width(labelWidth).alignByBaseline(), style = labelStyle)
                Text(text, Modifier.alignByBaseline(), style = Type.body)
            }
            Hairline()
        }
    }
}

/** Technical text for someone helping, collapsed until asked for. */
@Composable
internal fun Details(details: String) {
    var open by remember { mutableStateOf(false) }
    Action(if (open) text("details_hide") else text("details_show"), onClick = { open = !open })
    if (open) {
        SelectionContainer {
            Text(details, Modifier.fillMaxWidth().padding(horizontal = 20.dp), style = Type.secondary)
        }
    }
}

/** A slow light moving along a hairline, for work without a known end. */
@Composable
internal fun ProgressLine() {
    val position by rememberInfiniteTransition()
        .animateFloat(0f, 1f, infiniteRepeatable(tween(PROGRESS_MILLIS, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    Canvas(Modifier.fillMaxWidth().height(2.dp).progressSemantics()) {
        val y = size.height / 2
        drawLine(Brand.hairline, Offset(0f, y), Offset(size.width, y), size.height)
        val length = size.width / 4
        val start = (size.width - length) * position
        val light = Brush.horizontalGradient(listOf(Color.Transparent, Brand.blue, Color.Transparent), start, start + length)
        drawLine(light, Offset(start, y), Offset(start + length, y), size.height)
    }
}

private const val PROGRESS_MILLIS = 2400
