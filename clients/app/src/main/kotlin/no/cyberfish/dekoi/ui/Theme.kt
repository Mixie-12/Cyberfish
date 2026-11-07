package no.cyberfish.dekoi.ui

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.shared.DesignTokens
import java.util.Locale

/** A colour, a quiet background for it, and the text colour on that background. */
@Immutable
data class Tone(
    val color: Color,
    val container: Color,
    val onContainer: Color,
)

/** Status colours the M3 ColorScheme has no slot for. Colour never stands alone: pair it with a glyph and a word. */
@Immutable
data class SeverityColors(
    val critical: Tone,
    val warning: Tone,
    val info: Tone,
    val safe: Tone,
    val offline: Color,
) {
    fun of(severity: Severity): Tone =
        when (severity) {
            Severity.CRITICAL -> critical
            Severity.WARNING -> warning
            Severity.INFO -> info
        }
}

val LocalSeverityColors = staticCompositionLocalOf<SeverityColors> { error("SeverityColors are provided by DekoiTheme") }

/** True when the owner turned animations off (animation scale 0): no idle loops, draw-ins or rolling numbers. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * The status hero's colour. [highest] is the most serious recent alert; it
 * only matters while the state is attention.
 */
@Composable
fun protectionColor(
    protection: Protection,
    highest: Severity?,
): Color {
    val colors = LocalSeverityColors.current
    return when (protection) {
        Protection.PROTECTED -> colors.safe.color
        Protection.ATTENTION -> colors.of(highest ?: Severity.WARNING).color
        Protection.OFFLINE -> colors.offline
        Protection.SETTING_UP, Protection.NO_DEVICE -> MaterialTheme.colorScheme.primary
    }
}

/** Expanding, collapsing and sliding into place (docs/design.md, "Tokens"). */
fun <T> expandSpring(visibilityThreshold: T? = null): SpringSpec<T> =
    spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold)

/**
 * Digits of equal width, so counts and times do not jitter when they
 * change. JetBrains Mono's digits are equal by design; Michroma has no
 * tabular figures, and its digits differ by at most 5 %.
 */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

/** Headings: wordmarks, titles and section numbers. One weight, wide. */
internal val michroma = FontFamily(Font(R.font.michroma_regular))

/** Everything else. Only these two weights, so no text falls back to the logo's ExtraBold. */
private val jetBrainsMono = FontFamily(Font(R.font.jetbrains_mono_regular), Font(R.font.jetbrains_mono_medium, FontWeight.Medium))

/** The `><>` mark's face, and nothing else (UX/UI/CyberFish-aesthetic.md, "Logo"). */
internal val markFont = FontFamily(Font(R.font.jetbrains_mono_extrabold, FontWeight.ExtraBold))

// Michroma is wide: at 20 sp the longest screen title, NETWORKS, takes 309
// of the 320 dp a 360 dp phone has at 200 % font scale. Headings are white
// (the guide); callers upper-case them with headingCase(). No synthesised
// bold or italic: the bundled weights are the only ones.
private fun headingStyle(
    size: Int,
    lineHeight: Int,
) = TextStyle(
    fontFamily = michroma,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = HEADING_TRACKING.em,
    color = Color(DesignTokens.WHITE),
    fontSynthesis = FontSynthesis.None,
)

private fun bodyStyle(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
) = TextStyle(
    fontFamily = jetBrainsMono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontSynthesis = FontSynthesis.None,
)

private const val HEADING_TRACKING = 0.02f
private const val LABEL_TRACKING = 0.13f

private val typography =
    Typography(
        displayLarge = headingStyle(40, 48).tabular(),
        displayMedium = headingStyle(32, 40).tabular(),
        displaySmall = headingStyle(20, 28),
        headlineLarge = headingStyle(22, 30),
        headlineMedium = headingStyle(18, 26),
        headlineSmall = headingStyle(16, 24),
        titleLarge = headingStyle(18, 26),
        titleMedium = headingStyle(15, 22),
        titleSmall = headingStyle(13, 20),
        bodyLarge = bodyStyle(16, 24),
        bodyMedium = bodyStyle(14, 20),
        bodySmall = bodyStyle(12, 18),
        labelLarge = bodyStyle(14, 20, FontWeight.Medium),
        labelMedium = bodyStyle(12, 16, FontWeight.Medium),
        labelSmall = bodyStyle(11, 16, FontWeight.Medium),
    )

private val labelStyle = bodyStyle(12, 16, FontWeight.Medium).copy(letterSpacing = LABEL_TRACKING.em)

/** Labels and HUD text such as "01 / NETWORK": small and widely tracked; upper-case them with [headingCase]. */
val Typography.label: TextStyle get() = labelStyle

/** Addresses, hardware addresses and other technical values: body text, which is monospace. */
val Typography.address: TextStyle get() = bodyLarge

/**
 * Upper case for headings and labels, keeping the product name as it is
 * always written: "YOUR DeKoi".
 */
@Composable
@ReadOnlyComposable
fun String.headingCase(): String {
    val name = stringResource(R.string.app_name)
    return split(name).joinToString(name) { it.uppercase(Locale.getDefault()) }
}

private val shapes =
    Shapes(
        small = RoundedCornerShape(Dimens.chipRadius),
        medium = RoundedCornerShape(Dimens.tileRadius),
        large = RoundedCornerShape(Dimens.cardRadius),
        extraLarge = RoundedCornerShape(Dimens.heroRadius),
    )

private fun tone(tone: DesignTokens.Tone) = Tone(Color(tone.color), Color(tone.container), Color(tone.onContainer))

private val severityColors =
    SeverityColors(
        critical = tone(DesignTokens.CRITICAL),
        warning = tone(DesignTokens.WARNING),
        info = tone(DesignTokens.INFO),
        safe = tone(DesignTokens.SAFE),
        offline = Color(DesignTokens.OFFLINE),
    )

private val background = Color(DesignTokens.BACKGROUND)
private val navy = Color(DesignTokens.NAVY)
private val blue = Color(DesignTokens.BLUE)
private val white = Color(DesignTokens.WHITE)

// Every M3 role that components read is set, so no baseline purple shows
// through. Blue is the one accent; secondary and tertiary repeat it. Pages
// are the background with hairlines; only what floats (menus, dialogs,
// snackbars) and tracks take navy.
private val colorScheme =
    darkColorScheme(
        primary = blue,
        onPrimary = background,
        primaryContainer = navy,
        onPrimaryContainer = white,
        inversePrimary = blue,
        secondary = blue,
        onSecondary = background,
        secondaryContainer = navy,
        onSecondaryContainer = white,
        tertiary = blue,
        onTertiary = background,
        tertiaryContainer = navy,
        onTertiaryContainer = white,
        background = background,
        onBackground = Color(DesignTokens.TEXT),
        surface = background,
        onSurface = Color(DesignTokens.TEXT),
        surfaceVariant = navy,
        onSurfaceVariant = Color(DesignTokens.MID_GREY),
        surfaceTint = blue,
        // Snackbars and tooltips stay dark instead of inverting to a light fill.
        inverseSurface = navy,
        inverseOnSurface = white,
        error = severityColors.critical.color,
        onError = background,
        errorContainer = severityColors.critical.container,
        onErrorContainer = severityColors.critical.onContainer,
        // Text field and control borders need 3:1, which dim grey does not reach.
        outline = Color(DesignTokens.MID_GREY),
        outlineVariant = Color(DesignTokens.HAIRLINE),
        surfaceBright = navy,
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = background,
        surfaceContainer = navy,
        surfaceContainerHigh = navy,
        surfaceContainerHighest = navy,
    )

/** Dark only, no dynamic colour: the palette carries the brand and the meaning (ADR 0020). */
@Composable
fun DekoiTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSeverityColors provides severityColors,
        LocalReduceMotion provides rememberReduceMotion(),
        LocalTonalElevationEnabled provides false,
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = typography, shapes = shapes) {
            // Text outside a Material surface would otherwise default to
            // black, which is unreadable on the dark background.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground, content = content)
        }
    }
}

/** A floating element: navy with a hairline border, since shadows do not show on a dark background. */
@Composable
fun RaisedSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}

@Composable
private fun rememberReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var reduce by remember { mutableStateOf(animationsOff(resolver)) }
    // The owner may change the setting while the app is in the background.
    LifecycleResumeEffect(resolver) {
        reduce = animationsOff(resolver)
        onPauseOrDispose { }
    }
    return reduce
}

private fun animationsOff(resolver: ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
