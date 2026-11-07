package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import no.cyberfish.dekoi.R

/** Above this font scale tiles stack in one column, so their text keeps its size (docs/design.md, "Home"). */
private const val ONE_COLUMN_FONT_SCALE = 1.5f

/**
 * Tiles two to a row, one to a row at large font sizes. Tiles in a row
 * take the height of the tallest, so their edges line up. Each tile is
 * handed the modifier that sizes it.
 */
@Composable
fun TileGrid(
    tiles: List<@Composable (Modifier) -> Unit>,
    modifier: Modifier = Modifier,
) {
    val columns = if (LocalDensity.current.fontScale > ONE_COLUMN_FONT_SCALE) 1 else 2
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        tiles.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                row.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A numbered section label, "02 / LAST 7 DAYS": small and uppercase, with
 * the brand name kept as DeKoi (ADR 0020). TalkBack hears [text] as a
 * heading, without the number.
 */
@Composable
private fun SectionTitle(
    number: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    val locale = LocalLocale.current.platformLocale
    val brand = stringResource(R.string.app_name)
    val label = text.uppercase(locale).replace(brand.uppercase(locale), brand)
    Text(
        stringResource(R.string.section_label, number, label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier.semantics {
                heading()
                contentDescription = text
            },
    )
}

/** A numbered section: its label and a hairline to the edge, then its content. Space and the line set it apart, not a card. */
@Composable
fun Section(
    number: Int,
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().padding(top = Dimens.spaceS), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            SectionTitle(number, title)
            Hairline(Modifier.weight(1f))
        }
        content()
    }
}

/** A thin line across the content, the brand's way of separating things. */
@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, Dimens.hairline, MaterialTheme.colorScheme.outlineVariant)
}

/** A part of a screen fades in or out and slides into place as the data changes; [still] when animations are off. */
fun Modifier.itemMotion(
    item: LazyItemScope,
    still: Boolean,
): Modifier = if (still) this else with(item) { this@itemMotion.animateItem() }
