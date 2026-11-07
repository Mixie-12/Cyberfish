package no.cyberfish.dekoi.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.shared.DesignTokens

@Immutable
data class StatusColors(
    val protected: Color,
    val attention: Color,
    val offline: Color,
    val settingUp: Color,
    val secondaryText: Color,
    val divider: Color,
) {
    fun of(protection: Protection): Color =
        when (protection) {
            Protection.PROTECTED -> protected
            Protection.ATTENTION -> attention
            Protection.OFFLINE -> offline
            Protection.SETTING_UP, Protection.NO_DEVICE -> settingUp
        }
}

val LocalStatusColors =
    staticCompositionLocalOf {
        StatusColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified)
    }

private val typography =
    Typography(
        displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
        headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    )

@Composable
fun DekoiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val accent = Color(DesignTokens.ACCENT)
    val scheme =
        if (dark) {
            darkColorScheme(
                primary = accent,
                onPrimary = Color.White,
                background = Color(DesignTokens.Dark.BACKGROUND),
                onBackground = Color(DesignTokens.Dark.TEXT),
                surface = Color(DesignTokens.Dark.SURFACE),
                onSurface = Color(DesignTokens.Dark.TEXT),
                surfaceContainer = Color(DesignTokens.Dark.SURFACE),
            )
        } else {
            lightColorScheme(
                primary = accent,
                onPrimary = Color.White,
                background = Color(DesignTokens.Light.BACKGROUND),
                onBackground = Color(DesignTokens.Light.TEXT),
                surface = Color(DesignTokens.Light.SURFACE),
                onSurface = Color(DesignTokens.Light.TEXT),
                surfaceContainer = Color(DesignTokens.Light.SURFACE),
            )
        }
    val status =
        StatusColors(
            protected = Color(DesignTokens.PROTECTED),
            attention = Color(DesignTokens.ATTENTION),
            offline = Color(DesignTokens.OFFLINE),
            settingUp = Color(DesignTokens.SETTING_UP),
            secondaryText = Color(if (dark) DesignTokens.Dark.SECONDARY_TEXT else DesignTokens.Light.SECONDARY_TEXT),
            divider = Color(if (dark) DesignTokens.Dark.DIVIDER else DesignTokens.Light.DIVIDER),
        )
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
