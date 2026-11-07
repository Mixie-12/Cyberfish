package no.cyberfish.dekoi.ui

import android.content.ContentResolver
import android.provider.Settings
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalTonalElevationEnabled
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.unit.dp
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

private val LocalRaisedBorder = staticCompositionLocalOf<Color?> { null }

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

// Every M3 role that components read is set, so no baseline purple shows
// through. Secondary and tertiary are the tint: the app has one accent.
private fun colorScheme(
    palette: DesignTokens.Palette,
    inverse: DesignTokens.Palette,
    dark: Boolean,
): ColorScheme {
    val tint = Color(palette.tint.color)
    val onTint = Color(palette.onTint)
    val tintContainer = Color(palette.tint.container)
    val onTintContainer = Color(palette.tint.onContainer)
    val surface = Color(palette.surface)
    val background = Color(palette.background)
    val raised = Color(palette.raised)
    return (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = tint,
        onPrimary = onTint,
        primaryContainer = tintContainer,
        onPrimaryContainer = onTintContainer,
        inversePrimary = Color(inverse.tint.color),
        secondary = tint,
        onSecondary = onTint,
        secondaryContainer = tintContainer,
        onSecondaryContainer = onTintContainer,
        tertiary = tint,
        onTertiary = onTint,
        tertiaryContainer = tintContainer,
        onTertiaryContainer = onTintContainer,
        background = background,
        onBackground = Color(palette.text),
        surface = surface,
        onSurface = Color(palette.text),
        surfaceVariant = background,
        onSurfaceVariant = Color(palette.textSecondary),
        surfaceTint = tint,
        inverseSurface = Color(inverse.raised),
        inverseOnSurface = Color(inverse.text),
        // Critical and tint are both saturated mid-tones; on tint reads on either.
        error = Color(palette.critical.color),
        onError = onTint,
        errorContainer = Color(palette.critical.container),
        onErrorContainer = Color(palette.critical.onContainer),
        outline = Color(palette.textTertiary),
        outlineVariant = Color(palette.separator),
        surfaceBright = surface,
        surfaceDim = background,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        // Menus and dialogs float, so they take the raised colour.
        surfaceContainer = raised,
        surfaceContainerHigh = raised,
        // Tracks and filled fields: a step away from the surface they sit on.
        surfaceContainerHighest = background,
    )
}

private fun severityColors(palette: DesignTokens.Palette): SeverityColors {
    fun tone(tone: DesignTokens.Tone) = Tone(Color(tone.color), Color(tone.container), Color(tone.onContainer))
    return SeverityColors(
        critical = tone(palette.critical),
        warning = tone(palette.warning),
        info = tone(palette.info),
        safe = tone(palette.safe),
        offline = Color(palette.offline),
    )
}

/** No dynamic colour: the palette carries meaning (ADR 0011). */
@Composable
fun DekoiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DesignTokens.Dark else DesignTokens.Light
    val inverse = if (dark) DesignTokens.Light else DesignTokens.Dark
    CompositionLocalProvider(
        LocalSeverityColors provides severityColors(palette),
        LocalRaisedBorder provides palette.raisedBorder?.let { Color(it) },
        LocalReduceMotion provides rememberReduceMotion(),
        LocalTonalElevationEnabled provides false,
    ) {
        MaterialTheme(colorScheme = colorScheme(palette, inverse, dark), typography = typography, shapes = shapes, content = content)
    }
}

/** A floating element: a shadow in light mode, a hairline border in dark mode, where shadows do not show. */
@Composable
fun RaisedSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val border = LocalRaisedBorder.current
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = if (border == null) Dimens.raisedShadow else 0.dp,
        border = border?.let { BorderStroke(Dimens.hairline, it) },
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
