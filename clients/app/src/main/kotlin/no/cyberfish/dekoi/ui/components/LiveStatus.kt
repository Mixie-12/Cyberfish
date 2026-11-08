package no.cyberfish.dekoi.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.FeatureState
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Signal
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalSeverityColors

private val STATUS_ICON = 18.dp

/** The glyph and colour of a state (docs/design.md, "Live status"). */
@Composable
fun checkMark(state: FeatureState): Pair<Int, Color> {
    val colors = LocalSeverityColors.current
    return when (state.signal) {
        Signal.LIVE -> R.drawable.ic_check_circle to colors.safe.color
        Signal.STARTING -> R.drawable.ic_refresh to colors.warning.color
        Signal.FAILED -> R.drawable.ic_error to colors.critical.color
        Signal.ASK -> R.drawable.ic_info to MaterialTheme.colorScheme.primary
        Signal.QUIET -> R.drawable.ic_info to colors.offline
    }
}

@StringRes
fun checkWord(state: FeatureState): Int =
    when (state) {
        FeatureState.ON -> R.string.dekoi_state_on
        FeatureState.LEARNING -> R.string.features_state_learning
        FeatureState.STARTING -> R.string.dekoi_state_starting
        FeatureState.NEEDS_WIFI_NAME -> R.string.features_state_wifi_name
        FeatureState.WAITING_FOR_UPDATE -> R.string.features_state_update
        FeatureState.PAUSED -> R.string.home_checks_paused
        FeatureState.OFF -> R.string.dekoi_state_off
        FeatureState.FAILED -> R.string.dekoi_state_failed
        FeatureState.UNKNOWN -> R.string.dekoi_state_unknown
    }

/**
 * A check's state in glyph, colour and word, under it [detail], what the
 * DeKoi reports about it, and, when it fails, what the owner can do. Your
 * DeKoi and Features show every state with it.
 */
@Composable
fun CheckStatus(
    state: FeatureState,
    detail: String?,
    modifier: Modifier = Modifier,
) {
    val (glyph, tint) = checkMark(state)
    Row(modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        Icon(painterResource(glyph), contentDescription = null, modifier = Modifier.size(STATUS_ICON), tint = tint)
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
            Text(stringResource(checkWord(state)), style = MaterialTheme.typography.labelLarge, color = tint)
            detail?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state == FeatureState.FAILED) {
                Text(stringResource(R.string.check_failed_do), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
