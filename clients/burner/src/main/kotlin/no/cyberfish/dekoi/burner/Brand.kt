package no.cyberfish.dekoi.burner

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.shared.DesignTokens
import org.jetbrains.skia.Data
import org.jetbrains.skia.svg.SVGDOM
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/** The CyberFish palette from the shared tokens, as Compose colours. */
internal object Brand {
    val background = Color(DesignTokens.BACKGROUND)
    val navy = Color(DesignTokens.NAVY)
    val blue = Color(DesignTokens.BLUE)
    val white = Color(DesignTokens.WHITE)
    val text = Color(DesignTokens.TEXT)
    val midGrey = Color(DesignTokens.MID_GREY)
    val hairline = Color(DesignTokens.HAIRLINE)
    val critical = Color(DesignTokens.CRITICAL.color)
}

// Bundled from Images/ by the brandIcon task in build.gradle.kts.
private val appIcon =
    checkNotNull(Brand::class.java.getResourceAsStream("/brand/cyberfish-app-icon.svg")) { "the app icon is not bundled" }
        .use { it.readBytes() }

/** The mark's outline: the path "fish" in the app icon. */
internal val markOutline: Path =
    DocumentBuilderFactory
        .newInstance()
        .run {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            newDocumentBuilder()
        }.parse(appIcon.inputStream())
        .getElementsByTagName("path")
        .let { paths -> (0 until paths.length).map { paths.item(it) as Element } }
        .single { it.getAttribute("id") == "fish" }
        .getAttribute("d")
        .let { PathParser().parsePathString(it).toPath() }

/** The app icon at any size, for the window and the taskbar. */
internal val appIconPainter: Painter =
    object : Painter() {
        private val svg = SVGDOM(Data.makeFromBytes(appIcon))
        private val root = checkNotNull(svg.root) { "the app icon has no root" }

        override val intrinsicSize = Size(root.width.value, root.height.value)

        override fun DrawScope.onDraw() {
            scale(size.width / intrinsicSize.width, size.height / intrinsicSize.height, Offset.Zero) {
                svg.render(drawContext.canvas.skiaCanvas)
            }
        }
    }

// Bundled from UX/UI by the brandFonts task in build.gradle.kts.
private val michroma = FontFamily(Font("fonts/Michroma-Regular.ttf"))
private val mono =
    FontFamily(
        Font("fonts/JetBrainsMono-Regular.ttf", FontWeight.Normal),
        Font("fonts/JetBrainsMono-Medium.ttf", FontWeight.Medium),
    )

// JetBrains Mono's contextual ligatures would draw "://" in text as one
// glyph; the brand shows the characters.
private val monoText = TextStyle(fontFamily = mono, fontFeatureSettings = "calt 0")

/** Michroma for the wordmark and headings, JetBrains Mono for everything else. */
internal object Type {
    val wordmark = TextStyle(fontFamily = michroma, fontSize = 32.sp, letterSpacing = 0.02.em, color = Brand.white)
    val heading = TextStyle(fontFamily = michroma, fontSize = 22.sp, lineHeight = 32.sp, letterSpacing = 0.02.em, color = Brand.white)
    val name = heading.copy(fontSize = 20.sp)
    val body = monoText.copy(fontSize = 16.sp, lineHeight = 25.sp, color = Brand.text)
    val strong = body.copy(fontWeight = FontWeight.Medium, color = Brand.white)
    val secondary = body.copy(fontSize = 14.sp, lineHeight = 21.sp, color = Brand.midGrey)
    val code = body.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp, letterSpacing = 0.02.em, color = Brand.white)
    val label = monoText.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.14.em, color = Brand.blue)
    val action = label.copy(fontSize = 14.sp, letterSpacing = 0.1.em)
}

/** Buttons, fields and focus frames. */
internal val ControlShape = CutCornerShape(4.dp)

/** The HUD frame around the card choice and the final code. */
internal val HudShape = CutCornerShape(10.dp)

// Dark only (ADR 0020). Blue is an accent and never fills a control.
private val colorScheme =
    darkColorScheme(
        primary = Brand.blue,
        onPrimary = Brand.background,
        background = Brand.background,
        onBackground = Brand.text,
        surface = Brand.background,
        onSurface = Brand.text,
        onSurfaceVariant = Brand.midGrey,
        surfaceContainer = Brand.navy,
        outline = Brand.midGrey,
        outlineVariant = Brand.hairline,
        error = Brand.critical,
    )

// The Material slots that the text field and the country menu use.
private val typography =
    Typography(
        bodyLarge = Type.body,
        bodySmall = Type.secondary.copy(fontSize = 12.sp),
        labelLarge = Type.body.copy(fontSize = 15.sp),
    )

private val scrollbarStyle =
    ScrollbarStyle(
        minimalHeight = 32.dp,
        thickness = 6.dp,
        shape = RectangleShape,
        hoverDurationMillis = 200,
        unhoverColor = Brand.midGrey.copy(alpha = 0.4f),
        hoverColor = Brand.midGrey,
    )

@Composable
internal fun DekoiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = Shapes(extraSmall = ControlShape)) {
        CompositionLocalProvider(
            LocalIndication provides FocusIndication,
            LocalScrollbarStyle provides scrollbarStyle,
            content = content,
        )
    }
}

/**
 * How everything clickable reacts: navy under the pointer, and a blue frame
 * while it has the keyboard focus, so the focus is always visible.
 */
private object FocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = FocusIndicationNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

private class FocusIndicationNode(
    private val interactionSource: InteractionSource,
) : Modifier.Node(),
    DrawModifierNode {
    private var hovered = false
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            var hovers = 0
            var focuses = 0
            interactionSource.interactions.collect {
                when (it) {
                    is HoverInteraction.Enter -> hovers++
                    is HoverInteraction.Exit -> hovers--
                    is FocusInteraction.Focus -> focuses++
                    is FocusInteraction.Unfocus -> focuses--
                }
                hovered = hovers > 0
                focused = focuses > 0
                invalidateDraw()
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val outline = ControlShape.createOutline(size, layoutDirection, this)
        if (hovered) drawOutline(outline, Brand.navy)
        drawContent()
        if (focused) drawOutline(outline, Brand.blue, style = Stroke(2.dp.toPx()))
    }
}
