package no.cyberfish.dekoi.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.address

private const val COPIED_MS = 2000L
private val CHEVRON = 20.dp

/**
 * Rows on one rounded card, with an optional heading above and footer
 * below, as in the iOS Settings app. Put [InsetRow]s or rows built on it in
 * [content]; separators fall between them.
 */
@Composable
fun InsetSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = Dimens.spaceL, end = Dimens.spaceL, bottom = Dimens.spaceS).semantics { heading() },
            )
        }
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            // Every row draws a separator along its top edge. Moving the rows
            // up by one hairline tucks the first one under the card's edge.
            Column(
                Modifier.layout { measurable, constraints ->
                    val hairline = Dimens.hairline.roundToPx()
                    val rows = measurable.measure(constraints)
                    layout(rows.width, (rows.height - hairline).coerceAtLeast(0)) { rows.place(0, -hairline) }
                },
                content = content,
            )
        }
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Dimens.spaceL, end = Dimens.spaceL, top = Dimens.spaceS),
            )
        }
    }
}

/**
 * One row of an [InsetSection]: at least 56 dp, 64 dp with a 40 dp tile in
 * [leading]. A row with [onClick] shows a chevron unless [chevron] is false,
 * as for actions such as signing out.
 */
@Composable
fun InsetRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null,
) {
    val separator = MaterialTheme.colorScheme.outlineVariant
    // Where the text starts and ends inside the padding; the separator starts at the text.
    var textStart by remember { mutableFloatStateOf(0f) }
    var textEnd by remember { mutableFloatStateOf(0f) }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.row)
            .padding(horizontal = Dimens.spaceL, vertical = Dimens.spaceM)
            .drawBehind {
                val y = Dimens.hairline.toPx() / 2 - Dimens.spaceM.toPx()
                val outside = Dimens.spaceL.toPx()
                val (from, to) = if (layoutDirection == LayoutDirection.Ltr) textStart to size.width + outside else -outside to textEnd
                drawLine(separator, Offset(from, y), Offset(to, y), Dimens.hairline.toPx())
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        leading?.invoke()
        Column(
            Modifier.weight(1f).onPlaced {
                textStart = it.positionInParent().x
                textEnd = textStart + it.size.width
            },
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
        trailing?.invoke(this)
        if (chevron) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(CHEVRON),
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/** A label and its value. Values from a device are plain text, never links. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    InsetRow(label, modifier, trailing = { Value(value, monospace = false) })
}

/** A technical value in monospace with a copy button, such as a hardware address. */
@Composable
fun CopyableRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = checkNotNull(LocalContext.current.getSystemService(ClipboardManager::class.java))
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (!copied) return@LaunchedEffect
        delay(COPIED_MS)
        copied = false
    }
    InsetRow(
        label,
        modifier,
        trailing = {
            Value(value, monospace = true)
            IconButton(onClick = {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
                copied = true
            }) {
                Icon(
                    painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_content_copy),
                    contentDescription = stringResource(if (copied) R.string.action_copied else R.string.action_copy),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun RowScope.Value(
    value: String,
    monospace: Boolean,
) {
    Text(
        value,
        style = if (monospace) MaterialTheme.typography.address else MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.weight(1.4f),
    )
}
