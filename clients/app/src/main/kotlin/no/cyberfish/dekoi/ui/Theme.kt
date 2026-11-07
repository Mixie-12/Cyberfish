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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.shared.DesignTokens

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

/** Digits of equal width, so counts and times do not jitter when they change. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = "tnum")

private val addressStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 21.sp)

/** Addresses, hardware addresses and other technical values. */
val Typography.address: TextStyle get() = addressStyle

private val typography =
    Typography(
        displayMedium = TextStyle(fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold).tabular(),
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    )

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
