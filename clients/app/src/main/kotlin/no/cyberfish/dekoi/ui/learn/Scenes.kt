package no.cyberfish.dekoi.ui.learn

import androidx.annotation.StringRes
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.LocalSeverityColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min

/**
 * The drawings of the tour and the Learn library. Each is one short loop
 * that shows what happens; with animations off it holds [still], the
 * frame that tells the story on its own.
 */
enum class Scene(
    @param:StringRes val description: Int,
    val periodMs: Int,
    val still: Float,
) {
    DECOY(R.string.scene_decoy, 6000, 0.3f),
    TOUCH(R.string.scene_touch, 5000, 0.8f),
    STEPS(R.string.scene_steps, 6000, 0.55f),
    WATCH(R.string.scene_watch, 5000, 0.6f),
    PROBE(R.string.scene_probe, 4800, 0.55f),
    SWEEP(R.string.scene_sweep, 6000, 0.45f),
    IMPOSTOR(R.string.scene_impostor, 6000, 0.75f),
    SETTINGS(R.string.scene_settings, 6000, 0.42f),
    WIFI_COPY(R.string.scene_wifi_copy, 6000, 0.7f),
    NEW_DEVICE(R.string.scene_new_device, 5000, 0.7f),
    EXPOSED(R.string.scene_exposed, 5000, 0.3f),
    DNS(R.string.scene_dns, 6000, 0.8f),
}

/** A [Scene], drawn to fit; an image with a description for screen readers. */
@Composable
fun SceneDrawing(
    scene: Scene,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(scene.description)
    val severity = LocalSeverityColors.current
    val colors = MaterialTheme.colorScheme
    val ink =
        Ink(
            accent = colors.primary,
            device = colors.onSurfaceVariant,
            strong = colors.onBackground,
            line = colors.onSurfaceVariant.copy(alpha = LINE_ALPHA),
            alarm = severity.warning.color,
            safe = severity.safe.color,
        )
    val glyphs =
        Glyphs(
            router = painterResource(R.drawable.ic_router),
            computer = painterResource(R.drawable.ic_computer),
            phone = painterResource(R.drawable.ic_smartphone),
            tv = painterResource(R.drawable.ic_tv),
            speaker = painterResource(R.drawable.ic_speaker),
            camera = painterResource(R.drawable.ic_videocam),
            storage = painterResource(R.drawable.ic_hard_drive),
            unknown = painterResource(R.drawable.ic_device_unknown),
            bell = painterResource(R.drawable.ic_notifications),
            wifi = painterResource(R.drawable.ic_wifi),
            check = painterResource(R.drawable.ic_check),
            book = painterResource(R.drawable.ic_dns),
        )
    val measurer = rememberTextMeasurer()
    val numbers = MaterialTheme.typography.labelLarge
    val loop = if (LocalReduceMotion.current) null else loop(scene.periodMs)
    Canvas(
        modifier.clipToBounds().semantics {
            contentDescription = description
            role = Role.Image
        },
    ) {
        Stage(this, ink, glyphs, measurer, numbers).draw(scene, loop?.value ?: scene.still)
    }
}

private const val LARGE_TEXT = 1.5f
private val DRAWING = 200.dp
private val DRAWING_LARGE_TEXT = 140.dp

/** How tall a drawing is: shorter at large font sizes, so the words stay close to it. */
@Composable
@ReadOnlyComposable
internal fun drawingHeight(): Dp = if (LocalDensity.current.fontScale > LARGE_TEXT) DRAWING_LARGE_TEXT else DRAWING

@Composable
private fun loop(periodMs: Int): State<Float> =
    rememberInfiniteTransition(label = "scene")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = "scene")

private class Ink(
    val accent: Color,
    val device: Color,
    val strong: Color,
    val line: Color,
    val alarm: Color,
    val safe: Color,
)

private class Glyphs(
    val router: Painter,
    val computer: Painter,
    val phone: Painter,
    val tv: Painter,
    val speaker: Painter,
    val camera: Painter,
    val storage: Painter,
    val unknown: Painter,
    val bell: Painter,
    val wifi: Painter,
    val check: Painter,
    val book: Painter,
)

private const val LINE_ALPHA = 0.35f

// Scenes are laid out on a 320 by 200 grid, scaled to fit and centred.
private const val WIDTH = 320f
private const val HEIGHT = 200f
private const val ICON = 28f
private const val DOT = 3f
private const val DASH = 4f
private const val BAR = 4f
private val STROKE = 1.5.dp

// Each loop fades out over its last few per cent, so it starts again from nothing.
private const val FADE = 0.08f
private const val END = 0.92f

/** How far [t] is from [start] to [end], kept within 0..1. */
private fun span(
    t: Float,
    start: Float,
    end: Float,
) = ((t - start) / (end - start)).coerceIn(0f, 1f)

/** [span], eased: things start and stop gently. */
private fun phase(
    t: Float,
    start: Float,
    end: Float,
) = FastOutSlowInEasing.transform(span(t, start, end))

/** 1 from shortly after [start] until the loop fades out at its end. */
private fun shown(
    t: Float,
    start: Float,
) = phase(t, start, start + FADE) * (1 - phase(t, END, 1f))

/** Rises and falls once over the loop. */
private fun breathe(t: Float) = (1 - cos(2 * PI * t).toFloat()) / 2

private class Stage(
    private val scope: DrawScope,
    private val ink: Ink,
    private val glyphs: Glyphs,
    private val measurer: TextMeasurer,
    private val numbers: TextStyle,
) {
    private val unit = min(scope.size.width / WIDTH, scope.size.height / HEIGHT)
    private val origin = Offset((scope.size.width - WIDTH * unit) / 2, (scope.size.height - HEIGHT * unit) / 2)
    private val stroke = with(scope) { STROKE.toPx() }
    private val dashes = PathEffect.dashPathEffect(floatArrayOf(DASH * unit, DASH * unit))

    fun draw(
        scene: Scene,
        t: Float,
    ) = when (scene) {
        Scene.DECOY -> decoy(t)
        Scene.TOUCH -> touch(t)
        Scene.STEPS -> steps(t)
        Scene.WATCH -> watch(t)
        Scene.PROBE -> probe(t)
        Scene.SWEEP -> sweep(t)
        Scene.IMPOSTOR -> impostor(t)
        Scene.SETTINGS -> settings(t)
        Scene.WIFI_COPY -> wifiCopy(t)
        Scene.NEW_DEVICE -> newDevice(t)
        Scene.EXPOSED -> exposed(t)
        Scene.DNS -> dns(t)
    }

    private fun at(
        x: Float,
        y: Float,
    ) = origin + Offset(x * unit, y * unit)

    private fun glyph(
        painter: Painter,
        center: Offset,
        color: Color,
        alpha: Float = 1f,
        size: Float = ICON,
    ) {
        if (alpha <= 0f) return
        val side = size * unit
        scope.translate(center.x - side / 2, center.y - side / 2) {
            with(painter) { draw(Size(side, side), alpha, ColorFilter.tint(color)) }
        }
    }

    private fun halo(
        center: Offset,
        radius: Float,
        color: Color,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        val r = radius * unit
        scope.drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, r), r, center)
    }

    private fun link(
        from: Offset,
        to: Offset,
        color: Color = ink.line,
        alpha: Float = 1f,
        dashed: Boolean = false,
    ) {
        if (alpha <= 0f) return
        scope.drawLine(color, from, to, stroke, pathEffect = if (dashed) dashes else null, alpha = color.alpha * alpha)
    }

    private fun bar(
        from: Offset,
        to: Offset,
        color: Color,
    ) = scope.drawLine(color, from, to, BAR * unit, StrokeCap.Round)

    /** A dot on its way from [from] to [to]; nothing before it leaves or after it arrives. */
    private fun travel(
        from: Offset,
        to: Offset,
        progress: Float,
        color: Color,
    ) {
        if (progress <= 0f || progress >= 1f) return
        val here = lerp(from, to, progress)
        halo(here, DOT * 3, color, 0.4f)
        scope.drawCircle(color, DOT * unit, here)
    }

    /** A small card on its way, for settings handed from one device to another. */
    private fun carry(
        from: Offset,
        to: Offset,
        progress: Float,
        color: Color,
    ) {
        if (progress <= 0f || progress >= 1f) return
        val here = lerp(from, to, progress)
        val card = Size(14 * unit, 10 * unit)
        val corner = CornerRadius(2 * unit)
        scope.drawRoundRect(color, here - Offset(card.width / 2, card.height / 2), card, corner, Stroke(stroke))
    }

    /** A circle spreading out from [center] and fading as it goes. */
    private fun ring(
        center: Offset,
        progress: Float,
        color: Color,
        radius: Float,
    ) {
        if (progress <= 0f || progress >= 1f) return
        scope.drawCircle(color, radius * unit * progress, center, alpha = 1 - progress, style = Stroke(stroke))
    }

    private fun number(
        n: Int,
        center: Offset,
        color: Color,
        alpha: Float,
    ) {
        if (alpha <= 0f) return
        // Sized to the drawing, not to the font scale: the circle around it does not grow either.
        val size = with(scope) { (ICON * unit / 2).toSp() }
        val layout = measurer.measure(n.toString(), numbers.copy(color = color, fontSize = size, lineHeight = TextUnit.Unspecified))
        scope.drawText(layout, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f), alpha = alpha)
    }

    private fun outline(
        center: Offset,
        width: Float,
        height: Float,
        color: Color,
        alpha: Float = 1f,
    ) {
        val size = Size(width * unit, height * unit)
        val corner = CornerRadius(4 * unit)
        scope.drawRoundRect(color, center - Offset(size.width / 2, size.height / 2), size, corner, Stroke(stroke), alpha)
    }

    // Your devices and router, and the DeKoi beside them: everyday traffic
    // goes to the devices, never to the DeKoi.
    private fun decoy(t: Float) {
        val router = at(160f, 48f)
        val dekoi = at(272f, 140f)
        val homes = listOf(at(48f, 150f) to glyphs.computer, at(118f, 162f) to glyphs.phone, at(188f, 150f) to glyphs.tv)
        homes.forEachIndexed { i, (place, icon) ->
            link(router, place)
            travel(router, place, (t * 2 + i / 3f) % 1f, ink.device)
            glyph(icon, place, ink.device)
        }
        link(router, dekoi)
        halo(dekoi, 40f, ink.accent, 0.12f + 0.1f * breathe(t))
        glyph(glyphs.router, router, ink.device)
        glyph(glyphs.storage, dekoi, ink.accent)
    }

    // Something reaches the DeKoi; it rings, and the phone gets an alert.
    private fun touch(t: Float) {
        val intruder = at(48f, 128f)
        val dekoi = at(164f, 128f)
        val phone = at(276f, 104f)
        link(intruder, dekoi, ink.alarm, alpha = 0.5f, dashed = true)
        link(dekoi, phone, ink.accent, alpha = 0.4f * shown(t, 0.45f), dashed = true)
        travel(intruder, dekoi, phase(t, 0.05f, 0.35f), ink.alarm)
        ring(dekoi, phase(t, 0.35f, 0.75f), ink.accent, 44f)
        travel(dekoi, phone, phase(t, 0.45f, 0.7f), ink.accent)
        halo(dekoi, 40f, ink.accent, 0.12f + 0.12f * shown(t, 0.35f))
        glyph(glyphs.unknown, intruder, ink.alarm)
        glyph(glyphs.storage, dekoi, ink.accent)
        glyph(glyphs.phone, phone, ink.strong, size = 40f)
        glyph(glyphs.bell, at(276f, 66f), ink.accent, alpha = shown(t, 0.7f), size = 22f)
    }

    // An alert with three numbered steps, ticked off one after another.
    private fun steps(t: Float) {
        val card = Size(176 * unit, 168 * unit)
        scope.drawRoundRect(ink.line, at(72f, 16f), card, CornerRadius(12 * unit), Stroke(stroke))
        bar(at(92f, 38f), at(126f, 38f), ink.alarm)
        bar(at(92f, 56f), at(224f, 56f), ink.strong.copy(alpha = 0.5f))
        STEP_LENGTHS.forEachIndexed { i, length ->
            val y = 92f + i * 32f
            val center = at(104f, y)
            val done = shown(t, 0.15f + 0.22f * i)
            scope.drawCircle(ink.accent, 10 * unit, center, style = Stroke(stroke))
            number(i + 1, center, ink.accent, 1 - done)
            glyph(glyphs.check, center, ink.safe, alpha = done, size = 16f)
            bar(at(126f, y), at(126f + length, y), ink.device.copy(alpha = 0.5f))
        }
    }

    // DeKoi in the middle, listening in every direction; a newcomer appears.
    private fun watch(t: Float) {
        val dekoi = at(160f, 104f)
        val newcomer = at(256f, 160f)
        repeat(2) { ring(dekoi, (t + it / 2f) % 1f, ink.accent, 120f) }
        listOf(at(64f, 48f) to glyphs.router, at(256f, 48f) to glyphs.wifi, at(64f, 160f) to glyphs.computer).forEach { (place, icon) ->
            link(dekoi, place)
            glyph(icon, place, ink.device)
        }
        val arrived = shown(t, 0.45f)
        link(dekoi, newcomer, alpha = arrived)
        halo(newcomer, 24f, ink.accent, 0.25f * arrived)
        glyph(glyphs.phone, newcomer, ink.strong, alpha = arrived)
        halo(dekoi, 36f, ink.accent, 0.2f)
        glyph(glyphs.storage, dekoi, ink.accent)
    }

    // A device knocks on one door of the DeKoi after another.
    private fun probe(t: Float) {
        val scanner = at(44f, 104f)
        val dekoi = at(262f, 104f)
        halo(dekoi, 44f, ink.accent, 0.1f + 0.15f * phase(t, 0.3f, 0.8f) * (1 - phase(t, END, 1f)))
        link(at(206f, 28f), at(206f, 180f), ink.accent, alpha = 0.3f)
        repeat(DOORS) { i ->
            val door = at(206f, 44f + i * 24f)
            val start = 0.05f + i * 0.13f
            val knock = phase(t, start + 0.08f, start + 0.12f) * (1 - phase(t, start + 0.12f, start + 0.28f))
            travel(scanner, door, phase(t, start, start + 0.08f), ink.alarm)
            outline(door, 10f, 16f, ink.line)
            outline(door, 10f, 16f, ink.alarm, knock)
        }
        glyph(glyphs.unknown, scanner, ink.alarm)
        glyph(glyphs.storage, dekoi, ink.accent)
    }

    // One device asks after every other in turn; DeKoi hears each question.
    private fun sweep(t: Float) {
        val asker = at(160f, 36f)
        val dekoi = at(288f, 156f)
        val row = listOf(glyphs.computer, glyphs.phone, glyphs.tv, glyphs.speaker, glyphs.phone)
        var heard = 0f
        row.forEachIndexed { i, icon ->
            val place = at(40f + i * 48f, 156f)
            val start = 0.04f + i * 0.17f
            link(asker, place, ink.alarm, alpha = 0.4f * shown(t, start), dashed = true)
            travel(asker, place, phase(t, start, start + 0.1f), ink.alarm)
            ring(place, phase(t, start + 0.1f, start + 0.3f), ink.alarm, 22f)
            glyph(icon, place, ink.device)
            heard = maxOf(heard, phase(t, start, start + 0.05f) * (1 - phase(t, start + 0.05f, start + 0.17f)))
        }
        halo(dekoi, 36f, ink.accent, 0.12f + 0.2f * heard)
        glyph(glyphs.unknown, asker, ink.alarm)
        glyph(glyphs.storage, dekoi, ink.accent)
    }

    // A device poses as the router; the phone's traffic starts going through it.
    private fun impostor(t: Float) {
        val phone = at(56f, 100f)
        val router = at(264f, 56f)
        val fake = at(176f, 152f)
        val detour = shown(t, 0.4f)
        link(phone, router, alpha = 1 - 0.7f * detour)
        link(phone, fake, ink.alarm, alpha = 0.6f * detour, dashed = true)
        link(fake, router, ink.alarm, alpha = 0.6f * detour, dashed = true)
        travel(phone, router, phase(t, 0.05f, 0.35f), ink.device)
        val trip = span(t, 0.5f, 0.9f)
        if (trip < 0.5f) travel(phone, fake, trip * 2, ink.alarm) else travel(fake, router, trip * 2 - 1, ink.alarm)
        halo(fake, 30f, ink.alarm, 0.2f * detour)
        glyph(glyphs.phone, phone, ink.strong)
        glyph(glyphs.router, router, ink.device)
        glyph(glyphs.router, fake, ink.alarm, alpha = detour)
    }

    // A phone asks for settings; two devices answer, the false one first.
    private fun settings(t: Float) {
        val phone = at(160f, 150f)
        val router = at(60f, 52f)
        val fake = at(260f, 52f)
        ring(phone, phase(t, 0.02f, 0.3f), ink.device, 80f)
        link(router, phone)
        link(fake, phone, ink.alarm, alpha = 0.5f, dashed = true)
        carry(fake, phone, span(t, 0.25f, 0.45f), ink.alarm)
        carry(router, phone, span(t, 0.3f, 0.6f), ink.device)
        halo(phone, 30f, ink.alarm, 0.25f * shown(t, 0.45f))
        glyph(glyphs.router, router, ink.device)
        glyph(glyphs.router, fake, ink.alarm)
        glyph(glyphs.phone, phone, ink.strong)
    }

    // Two networks with the same name; the phone drifts to the copy.
    private fun wifiCopy(t: Float) {
        val real = at(84f, 56f)
        val copy = at(236f, 56f)
        val phone = at(160f, 160f)
        repeat(2) {
            val wave = (t + it / 2f) % 1f
            ring(real, wave, ink.device, 44f)
            ring(copy, wave, ink.alarm, 44f)
        }
        for (place in listOf(at(84f, 94f), at(236f, 94f))) {
            outline(place, 56f, 14f, ink.line)
            bar(place - Offset(14 * unit, 0f), place + Offset(14 * unit, 0f), ink.strong.copy(alpha = 0.5f))
        }
        val drift = phase(t, 0.35f, 0.6f) * (1 - phase(t, END, 1f))
        link(phone, real, alpha = 1 - drift)
        link(phone, copy, ink.alarm, alpha = drift, dashed = true)
        glyph(glyphs.wifi, real, ink.device)
        glyph(glyphs.wifi, copy, ink.alarm)
        glyph(glyphs.phone, phone, ink.strong)
    }

    // A device DeKoi has not seen before joins the network.
    private fun newDevice(t: Float) {
        val router = at(160f, 96f)
        val dekoi = at(160f, 172f)
        val newcomer = at(256f, 156f)
        listOf(at(64f, 44f) to glyphs.computer, at(256f, 44f) to glyphs.tv, at(64f, 156f) to glyphs.speaker).forEach { (place, icon) ->
            link(router, place)
            glyph(icon, place, ink.device)
        }
        link(router, dekoi)
        val arrived = shown(t, 0.2f)
        link(router, lerp(router, newcomer, phase(t, 0.25f, 0.45f)), alpha = arrived)
        ring(newcomer, phase(t, 0.4f, 0.8f), ink.accent, 32f)
        halo(newcomer, 24f, ink.accent, 0.25f * arrived)
        glyph(glyphs.phone, newcomer, ink.strong, alpha = arrived)
        glyph(glyphs.router, router, ink.device)
        glyph(glyphs.storage, dekoi, ink.accent)
    }

    // A camera at home with a door open to the internet, and knocks coming in.
    private fun exposed(t: Float) {
        val house =
            Path().apply {
                listOf(24f to 82f, 112f to 24f, 200f to 82f).forEachIndexed { i, (x, y) -> point(i == 0, at(x, y)) }
                listOf(36f to 74f, 36f to 184f, 188f to 184f, 188f to 138f).forEachIndexed { i, (x, y) -> point(i == 0, at(x, y)) }
                point(true, at(188f, 110f))
                point(false, at(188f, 74f))
            }
        scope.drawPath(house, ink.line, style = Stroke(stroke))
        val router = at(112f, 96f)
        val camera = at(80f, 152f)
        val tv = at(148f, 152f)
        val door = at(188f, 124f)
        val internet = at(276f, 124f)
        link(router, camera)
        link(router, tv)
        link(internet, door, ink.alarm, alpha = 0.6f, dashed = true)
        link(door, camera, ink.alarm, alpha = 0.6f, dashed = true)
        val knock = (t * 2) % 1f
        travel(internet, door, span(knock, 0f, 0.5f), ink.alarm)
        travel(door, camera, span(knock, 0.5f, 1f), ink.alarm)
        globe(internet)
        glyph(glyphs.router, router, ink.device)
        glyph(glyphs.camera, camera, ink.alarm)
        glyph(glyphs.tv, tv, ink.device)
    }

    private fun Path.point(
        start: Boolean,
        at: Offset,
    ) = if (start) moveTo(at.x, at.y) else lineTo(at.x, at.y)

    private fun globe(center: Offset) {
        val r = 18 * unit
        scope.drawCircle(ink.device, r, center, style = Stroke(stroke))
        scope.drawOval(ink.device, Offset(center.x - r / 2, center.y - r), Size(r, 2 * r), style = Stroke(stroke))
        scope.drawLine(ink.device, Offset(center.x - r, center.y), Offset(center.x + r, center.y), stroke)
    }

    // The phone asks the way; the answer is wrong, and it goes to a copy.
    private fun dns(t: Float) {
        val phone = at(48f, 104f)
        val book = at(160f, 40f)
        val real = at(272f, 52f)
        val copy = at(272f, 156f)
        link(phone, book)
        link(phone, real, alpha = 0.3f, dashed = true)
        link(phone, copy, ink.alarm, alpha = 0.6f * shown(t, 0.55f), dashed = true)
        travel(phone, book, phase(t, 0.05f, 0.25f), ink.device)
        travel(book, phone, phase(t, 0.3f, 0.5f), ink.alarm)
        travel(phone, copy, phase(t, 0.6f, 0.85f), ink.alarm)
        site(real, ink.device)
        site(copy, ink.alarm)
        halo(book, 28f, ink.alarm, 0.2f * shown(t, 0.3f))
        glyph(glyphs.book, book, ink.device)
        glyph(glyphs.phone, phone, ink.strong)
    }

    private fun site(
        center: Offset,
        color: Color,
    ) {
        val box = Rect(center - Offset(26 * unit, 18 * unit), Size(52 * unit, 36 * unit))
        scope.drawRoundRect(color, box.topLeft, box.size, CornerRadius(4 * unit), Stroke(stroke))
        scope.drawLine(color, Offset(box.left, box.top + 8 * unit), Offset(box.right, box.top + 8 * unit), stroke)
        bar(Offset(box.left + 8 * unit, box.top + 18 * unit), Offset(box.right - 12 * unit, box.top + 18 * unit), color.copy(alpha = 0.5f))
        bar(Offset(box.left + 8 * unit, box.top + 27 * unit), Offset(box.right - 20 * unit, box.top + 27 * unit), color.copy(alpha = 0.5f))
    }
}

private const val DOORS = 6
private val STEP_LENGTHS = listOf(104f, 84f, 96f)
