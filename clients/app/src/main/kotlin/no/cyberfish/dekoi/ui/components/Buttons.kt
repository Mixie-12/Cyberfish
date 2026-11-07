package no.cyberfish.dekoi.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.Dimens

private const val GLOW_ALPHA = 0.35f
private val GLOW = 12.dp

/**
 * The one primary action on a screen: an outlined, lit control with a blue
 * hairline, a soft blue glow and a white label, on navy. Blue never fills
 * it. Secondary actions are a plain TextButton. Same slots as M3 Button.
 */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = CutCornerShape(Dimens.cut)
    val blue = MaterialTheme.colorScheme.primary
    // The glow sits behind the opaque navy, so it shows only outside the line.
    val lit = if (enabled) Modifier.dropShadow(shape, Shadow(GLOW, blue.copy(alpha = GLOW_ALPHA))) else Modifier
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = Dimens.touchTarget).then(lit),
        enabled = enabled,
        shape = shape,
        colors =
            ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = Color(DesignTokens.WHITE),
            ),
        border = BorderStroke(Dimens.hairline, if (enabled) blue else MaterialTheme.colorScheme.outlineVariant),
        content = content,
    )
}
