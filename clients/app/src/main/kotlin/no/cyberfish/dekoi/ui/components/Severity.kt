package no.cyberfish.dekoi.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.severityLabel
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalSeverityColors

@DrawableRes
fun severityGlyph(severity: Severity): Int =
    when (severity) {
        Severity.CRITICAL -> R.drawable.ic_error
        Severity.WARNING -> R.drawable.ic_warning
        Severity.INFO -> R.drawable.ic_info
    }

/** Glyph and word on the severity's container colour. */
@Composable
fun SeverityChip(
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    val tone = LocalSeverityColors.current.of(severity)
    Row(
        modifier
            .background(tone.container, MaterialTheme.shapes.small)
            .padding(horizontal = Dimens.spaceS, vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        Icon(painterResource(severityGlyph(severity)), contentDescription = null, modifier = Modifier.size(16.dp), tint = tone.color)
        Text(stringResource(severityLabel(severity)), style = MaterialTheme.typography.labelMedium, color = tone.onContainer)
    }
}

/** The 40 dp tile that leads an alert row. TalkBack reads the severity word. */
@Composable
fun SeverityTile(
    severity: Severity,
    modifier: Modifier = Modifier,
) {
    val tone = LocalSeverityColors.current.of(severity)
    Box(
        modifier.size(Dimens.tile).background(tone.container, MaterialTheme.shapes.medium),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(severityGlyph(severity)), contentDescription = stringResource(severityLabel(severity)), tint = tone.color)
    }
}
