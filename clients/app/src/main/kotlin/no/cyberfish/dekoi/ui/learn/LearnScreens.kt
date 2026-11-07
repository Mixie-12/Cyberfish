package no.cyberfish.dekoi.ui.learn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.components.Horizon
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.SectionLabel
import no.cyberfish.dekoi.ui.headingCase

private val CHEVRON = 20.dp
private val NUMBER = 28.dp

/** Every Learn topic in reading order, one hairline row each. */
@Composable
fun LearnScreen(
    onOpen: (LearnTopic) -> Unit,
    onBack: () -> Unit,
) {
    Screen(stringResource(R.string.learn_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.learn_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Column {
                LearnTopic.entries.forEachIndexed { i, topic -> TopicRow(i + 1, topic) { onOpen(topic) } }
            }
        }
    }
}

@Composable
private fun TopicRow(
    number: Int,
    topic: LearnTopic,
    onClick: () -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.learn_open), role = Role.Button, onClick = onClick)
            .drawBehind { drawLine(hairline, Offset.Zero, Offset(size.width, 0f), Dimens.hairline.toPx()) }
            .heightIn(min = Dimens.row)
            .padding(vertical = Dimens.spaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Number(number.toString().padStart(2, '0'), Modifier.clearAndSetSemantics { })
        Column(Modifier.weight(1f)) {
            Text(stringResource(topic.title), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)
            Text(
                stringResource(topic.summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.size(CHEVRON),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/** One topic: its drawing, what it is, what DeKoi does about it and what the owner can do. */
@Composable
fun LearnTopicScreen(
    id: String,
    onBack: () -> Unit,
) {
    val topic = LearnTopic.of(id)
    if (topic == null) {
        MissingScreen(R.string.learn_title, loaded = true, R.drawable.ic_info, R.string.learn_missing, onBack)
        return
    }
    Screen(stringResource(topic.title), onBack = onBack) {
        item {
            HudFrame(Modifier.fillMaxWidth()) {
                SceneDrawing(topic.scene, Modifier.fillMaxWidth().height(drawingHeight()))
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                stringArrayResource(topic.body).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                SectionLabel(1, stringResource(R.string.learn_section_dekoi))
                Text(stringResource(topic.dekoi), style = MaterialTheme.typography.bodyLarge)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
                SectionLabel(2, stringResource(R.string.learn_section_you))
                stringArrayResource(topic.steps).forEachIndexed { i, step -> Step(i + 1, step) }
            }
        }
    }
}

/** A numbered step, read as one item by screen readers. */
@Composable
private fun Step(
    number: Int,
    text: String,
) {
    Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
        Number(number.toString())
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Number(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.widthIn(min = NUMBER),
    )
}
