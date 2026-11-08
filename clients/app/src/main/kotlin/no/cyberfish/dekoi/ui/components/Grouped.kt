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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.address
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.label

private const val COPIED_MS = 2000L
private val CHEVRON = 20.dp

/**
 * A list structured by hairlines, with an optional label above and footer
 * below. Put [InsetRow]s or rows built on it in [content]: each draws a
 * hairline along its top, and the list closes with one along its bottom.
 */
@Composable
fun InsetSection(
    modifier: Modifier = Modifier,
    title: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Column(modifier.fillMaxWidth()) {
        title?.let {
            Text(
                it.headingCase(),
                style = MaterialTheme.typography.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = Dimens.spaceS).semantics { heading() },
            )
        }
        // The top line also covers content that is not a row, such as a chart;
        // the first row's own line falls on it.
        Column(
            Modifier.fillMaxWidth().drawWithContent {
                drawContent()
                drawHairline(hairline, bottom = false)
                drawHairline(hairline, bottom = true)
            },
            content = content,
        )
        footer?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Dimens.spaceS),
            )
        }
    }
}

/** A full-width hairline just inside the top or bottom edge. */
internal fun DrawScope.drawHairline(
    color: Color,
    bottom: Boolean,
) {
    val width = Dimens.hairline.toPx()
    val y = if (bottom) size.height - width / 2 else width / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), width)
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
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Dimens.row)
            .drawBehind { drawHairline(hairline, bottom = false) }
            .padding(vertical = Dimens.spaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
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

/**
 * The "Technical details" row of an [InsetSection]: a tap shows or hides
 * the rows after it, which the caller draws while [open]. Give the section
 * an animateContentSize so they slide in.
 */
@Composable
fun TechnicalDetailsRow(
    open: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    InsetRow(
        stringResource(R.string.event_details),
        modifier =
            Modifier.semantics {
                if (open) {
                    collapse {
                        onToggle(false)
                        true
                    }
                } else {
                    expand {
                        onToggle(true)
                        true
                    }
                }
            },
        trailing = {
            Icon(
                painterResource(R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.rotate(if (open) 180f else 0f),
                tint = MaterialTheme.colorScheme.outline,
            )
        },
        onClick = { onToggle(!open) },
        chevron = false,
    )
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
