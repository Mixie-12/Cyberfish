package no.cyberfish.dekoi.ui

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.eventText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val ScreenPadding = 20.dp
private val CardShape = RoundedCornerShape(16.dp)

/** A screen with a large title, an optional back button, and scrolling content. */
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    action?.invoke()
                }
                Text(
                    title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.semantics { heading() },
                )
            }
        }
        content()
    }
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(shape = CardShape, color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Box(clickable.padding(16.dp)) { content() }
    }
}

@Composable
fun SectionTitle(
    @StringRes text: Int,
) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
fun SecondaryText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun StatusDot(
    color: Color,
    size: Int = 10,
) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
fun KeyValue(
    @StringRes label: Int,
    value: String,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        SecondaryText(stringResource(label), Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.4f))
    }
}

@Composable
fun Divider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun AccentButton(
    @StringRes text: Int,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick) {
        Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun eventTitle(event: Event): String {
    val text = eventText(event)
    val service = text.service?.let { stringResource(it) }
    return if (service != null) stringResource(text.title, service) else stringResource(text.title)
}

@Composable
fun EventRow(
    event: Event,
    now: Instant,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
    ) {
        StatusDot(LocalSeverityColors.current.of(event.severity).color)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(eventTitle(event), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SecondaryText(relativeTime(event.occurredAt, now))
        }
    }
}

fun relativeTime(
    time: Instant,
    now: Instant,
): String =
    DateUtils
        .getRelativeTimeSpanString(time.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

fun absoluteTime(time: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(time)
