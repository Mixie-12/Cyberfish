package no.cyberfish.dekoi.ui.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalSeverityColors
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.motion.sharedKindTile
import no.cyberfish.dekoi.ui.relativeTime
import java.time.Instant

private val DOT = 10.dp
private val DOT_GAP = 2.dp
private val TAG_PADDING = 6.dp
private val DOT_SHIFT = 3.dp
private val HALO = 34.dp
private const val HALO_ALPHA = 0.45f

/** Device detail for this host. */
internal val NetworkHost.detail: Route.HostDetail get() = Route.HostDetail(deviceId, mac)

/**
 * A host's kind tile with its status dot and, for an alert in the last 24
 * hours ([attention]), a halo in that alert's colour. The tile moves into
 * the hero of Device detail as that screen opens and closes.
 */
@Composable
internal fun HostTile(
    host: NetworkHost,
    online: Boolean,
    attention: Severity?,
) {
    Box(Modifier.alertHalo(attention)) {
        KindTile(host.kind, Modifier.sharedKindTile(host.detail))
        StatusDot(online, Modifier.align(Alignment.BottomEnd).offset(DOT_SHIFT, DOT_SHIFT))
    }
}

/** A soft glow in the colour of [attention] around a 40 dp tile; nothing when it is null. */
@Composable
internal fun Modifier.alertHalo(attention: Severity?): Modifier {
    val color = attention?.let { LocalSeverityColors.current.of(it).color } ?: return this
    return drawBehind {
        val radius = HALO.toPx()
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = HALO_ALPHA), Color.Transparent), radius = radius), radius)
    }
}

/** "Online now" or "Last seen 2 h ago". */
@Composable
internal fun seenText(
    online: Boolean,
    lastSeenAt: Instant,
    now: Instant,
): String =
    if (online) {
        stringResource(R.string.host_online_now)
    } else {
        stringResource(R.string.host_last_seen_ago, relativeTime(lastSeenAt, now))
    }

/**
 * Online (filled) or not seen recently (hollow), on a ring of the
 * background so it stands clear of the tile it sits on. Decorative: say
 * the same in words nearby.
 */
@Composable
internal fun StatusDot(
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSeverityColors.current
    val gap = MaterialTheme.colorScheme.background
    Canvas(modifier.size(DOT)) {
        val radius = size.minDimension / 2
        val inner = radius - DOT_GAP.toPx()
        drawCircle(gap, radius)
        if (online) {
            drawCircle(colors.safe.color, inner)
        } else {
            val stroke = Dimens.hairline.toPx() * 2
            drawCircle(colors.offline, inner - stroke / 2, style = Stroke(stroke))
        }
    }
}

/** A short word in a hairline outline, such as "New" or a service label. Plain text only. */
@Composable
internal fun Tag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelMedium,
) {
    Text(
        text,
        modifier
            .border(Dimens.hairline, color, MaterialTheme.shapes.small)
            .padding(horizontal = TAG_PADDING, vertical = Dimens.hairline),
        color = color,
        style = style,
        maxLines = 1,
    )
}
