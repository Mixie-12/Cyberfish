package no.cyberfish.dekoi.ui

import android.content.ActivityNotFoundException
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.cyberfish.dekoi.Card
import no.cyberfish.dekoi.DekoiApplication
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.WriteFailure
import no.cyberfish.dekoi.WriteState
import no.cyberfish.dekoi.cardTree
import no.cyberfish.dekoi.pickerIntent
import no.cyberfish.dekoi.removableCards
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.TechnicalDetailsRow

/**
 * Preparing a card with this phone (ADR 0017, 0031): connect the card,
 * choose it and give DeKoi its root folder in Android's picker, confirm
 * "Erase this card", copy the installer onto it, link the card's code to
 * the account with [onClaim], then power on.
 */
@Composable
fun WriteCardScreen(
    onClaim: (ClaimCode, (Int) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val writing = remember { (context.applicationContext as DekoiApplication).cardWriting }
    val state by writing.state.collectAsStateWithLifecycle()

    // Each visit starts over, unless a card is being prepared or waits to be
    // linked; rotating the phone is not a new visit.
    var entered by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!entered) writing.enter()
        entered = true
    }

    fun claim() {
        val code = writing.claim() ?: return
        onClaim(code) { message -> if (message == R.string.add_done) writing.linked() else writing.claimFailed(message) }
    }

    // A verified card is linked straight away; after a failure, when the owner asks.
    val waiting = state.let { it is WriteState.Written && it.problem == null }
    LaunchedEffect(waiting) {
        if (waiting) claim()
    }

    // Leaving lets go of a card that is chosen but not confirmed.
    val leave = {
        writing.reset()
        onBack()
    }
    BackHandler(onBack = leave)

    // The access is not made persistable: it ends when DeKoi closes.
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val card = (writing.state.value as? WriteState.Opening)?.card ?: return@rememberLauncherForActivityResult
            writing.opened(cardTree(result, card))
        }

    fun open(card: Card) {
        if (!writing.open(card)) return
        val intent = pickerIntent(context, card) ?: return writing.failed(WriteFailure.CARD_ERROR)
        try {
            picker.launch(intent)
        } catch (error: ActivityNotFoundException) {
            writing.failed(WriteFailure.CARD_ERROR, error)
        }
    }

    Screen(stringResource(R.string.write_title), onBack = leave) {
        item {
            when (val current = state) {
                WriteState.Connect -> {
                    Connect { writing.look(removableCards(context)) }
                }

                is WriteState.Choose -> {
                    Choose(current.cards, ::open) { writing.look(removableCards(context)) }
                }

                is WriteState.Opening -> {
                    Step(R.string.write_step_choose) { Body(stringResource(R.string.write_opening)) }
                }

                is WriteState.Confirm -> {
                    Confirm(current.card, onErase = writing::erase, onCancel = writing::reset)
                }

                is WriteState.Writing -> {
                    Writing(current)
                }

                is WriteState.Written -> {
                    val problem = current.problem
                    if (problem == null) Linking() else Unlinked(current.code, problem, ::claim)
                }

                is WriteState.Linking -> {
                    Linking()
                }

                is WriteState.Linked -> {
                    Linked(current.name, leave)
                }

                is WriteState.Failed -> {
                    Failed(current, writing::reset)
                }
            }
        }
    }
}

@Composable
private fun Connect(onFind: () -> Unit) {
    Step(R.string.write_step_connect) {
        Body(stringResource(R.string.write_connect_body))
        Action(R.string.write_connect_button, onFind)
    }
}

@Composable
private fun Choose(
    cards: List<Card>,
    onChoose: (Card) -> Unit,
    onLookAgain: () -> Unit,
) {
    Step(R.string.write_step_choose) {
        if (cards.isEmpty()) {
            Body(stringResource(R.string.write_no_card))
            Action(R.string.write_look_again, onLookAgain)
            return@Step
        }
        Body(stringResource(R.string.write_choose_body))
        Column {
            cards.forEach { card ->
                HorizontalDivider()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Dimens.row)
                        .clickable(role = Role.Button) { onChoose(card) }
                        .padding(vertical = Dimens.spaceL),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                ) {
                    Text(card.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Size(card.bytes)
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Size(bytes: Long) {
    Text(
        Formatter.formatShortFileSize(LocalContext.current, bytes),
        style = MaterialTheme.typography.bodyLarge.tabular(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Confirm(
    card: Card,
    onErase: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    RefuseObscuredTouches()
    Step(R.string.write_step_confirm) {
        HudFrame(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Size(card.bytes)
                Body(stringResource(R.string.write_confirm_body, Formatter.formatShortFileSize(context, CardContent.installer.size)))
                OutlinedButton(
                    onClick = onErase,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                    border = BorderStroke(Dimens.hairline, MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.write_erase), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

/**
 * Ignores touches that pass through another app's window while the erase
 * confirmation shows, so that an overlay cannot trick the owner into it.
 */
@Composable
private fun RefuseObscuredTouches() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = before }
    }
}

@Composable
private fun Writing(progress: WriteState.Writing) {
    val phases =
        listOf(
            Triple(R.string.write_phase_download, progress.downloaded, CardContent.installer.size),
            Triple(R.string.write_phase_write, progress.written, progress.total),
            Triple(R.string.write_phase_check, progress.checked, progress.total),
        )
    // Each phase moves about as many bytes, so each fills a third of the line.
    val fraction = phases.sumOf { (_, done, total) -> if (total > 0) done.toDouble() / total else 0.0 } / phases.size
    Step(R.string.write_step_writing) {
        GlowLine(fraction.toFloat())
        Column {
            phases.forEach { (name, done, total) -> Phase(name, done, total) }
            HorizontalDivider()
        }
        Body(stringResource(R.string.write_keep_connected))
    }
}

/** One phase of preparing; [total] is 0 while it is not known yet. */
@Composable
private fun Phase(
    @StringRes name: Int,
    done: Long,
    total: Long,
) {
    val context = LocalContext.current
    val finished = total in 1..done
    val color =
        when {
            finished -> MaterialTheme.colorScheme.onSurface
            done > 0 -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        }
    HorizontalDivider()
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dimens.row).padding(vertical = Dimens.spaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Text(stringResource(name), style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.weight(1f))
        if (finished) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = stringResource(R.string.write_phase_done), tint = color)
        } else if (total > 0) {
            Text(
                stringResource(
                    R.string.write_progress,
                    Formatter.formatShortFileSize(context, done),
                    Formatter.formatShortFileSize(context, total),
                ),
                style = MaterialTheme.typography.bodyMedium.tabular(),
                color = color,
            )
        }
    }
}

@Composable
private fun Linking() {
    Step(R.string.write_step_link) { Body(stringResource(R.string.write_linking)) }
}

/** The card is prepared, but linking it failed: try again, or keep the code to type later. */
@Composable
private fun Unlinked(
    code: ClaimCode,
    @StringRes problem: Int,
    onTryAgain: () -> Unit,
) {
    Step(R.string.write_step_link) {
        Body(stringResource(R.string.write_link_failed))
        Body(stringResource(problem))
        Action(R.string.action_try_again, onTryAgain)
        Body(stringResource(R.string.write_link_later))
        Text(code.grouped, style = MaterialTheme.typography.address)
    }
}

@Composable
private fun Linked(
    name: String,
    onDone: () -> Unit,
) {
    Step(R.string.write_step_start) {
        Body(stringResource(R.string.write_done_body, name))
        Column {
            listOf(R.string.write_next_card, R.string.write_next_cable, R.string.write_next_power).forEachIndexed { index, step ->
                HorizontalDivider()
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Dimens.spaceM),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
                ) {
                    Text(
                        (index + 1).toString(),
                        style = MaterialTheme.typography.labelMedium.tabular(),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(stringResource(step), style = MaterialTheme.typography.bodyLarge)
                }
            }
            HorizontalDivider()
        }
        Action(R.string.write_done_button, onDone)
    }
}

@Composable
private fun Failed(
    failed: WriteState.Failed,
    onTryAgain: () -> Unit,
) {
    val size = Formatter.formatShortFileSize(LocalContext.current, failed.bytes ?: 0)
    val message =
        when (failed.failure) {
            WriteFailure.DENIED -> {
                stringResource(R.string.write_failed_denied)
            }

            WriteFailure.TOO_SMALL -> {
                stringResource(R.string.write_failed_too_small, size)
            }

            WriteFailure.TOO_LARGE -> {
                stringResource(R.string.write_failed_too_large, size)
            }

            WriteFailure.LOCKED -> {
                stringResource(R.string.write_failed_locked)
            }

            WriteFailure.NETWORK -> {
                stringResource(R.string.write_failed_network)
            }

            WriteFailure.DOWNLOAD_DAMAGED -> {
                stringResource(R.string.write_failed_download)
            }

            WriteFailure.CARD_ERROR -> {
                stringResource(R.string.write_failed_card)
            }

            WriteFailure.NOT_VERIFIED -> {
                stringResource(R.string.write_failed_verify)
            }
        }
    Step(R.string.write_step_failed) {
        Body(message)
        failed.detail?.let { TechnicalDetail(it) }
        Action(R.string.action_try_again, onTryAgain)
    }
}

/** Collapsed: what the phone or the download reported, for whoever helps the owner. */
@Composable
private fun TechnicalDetail(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    InsetSection(Modifier.animateContentSize()) {
        TechnicalDetailsRow(open) { open = it }
        if (open) {
            SelectionContainer {
                Text(
                    text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Dimens.spaceM),
                )
            }
        }
    }
}

/** A numbered step: its label, then [content] spaced below it. */
@Composable
private fun Step(
    @StringRes label: Int,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceL)) {
        // The brand's label (ADR 0020): small, upper case, widely tracked.
        // Announced when the step changes, not on every progress update.
        Text(
            stringResource(label),
            style = MaterialTheme.typography.label,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
        )
        content()
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Action(
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    PrimaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(label)) }
}

/** Progress as a lit line: a soft glow under a thin accent line, on a hairline track. */
@Composable
private fun GlowLine(fraction: Float) {
    val target = fraction.coerceIn(0f, 1f)
    val animated by animateFloatAsState(target, label = "progress")
    val value = if (LocalReduceMotion.current) target else animated
    val track = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(GLOW).semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) }) {
        val y = size.height / 2
        val end = Offset(size.width * value, y)
        drawLine(track, Offset(0f, y), Offset(size.width, y), Dimens.hairline.toPx())
        drawLine(accent.copy(alpha = GLOW_ALPHA), Offset(0f, y), end, GLOW.toPx(), StrokeCap.Round)
        drawLine(accent, Offset(0f, y), end, LINE.toPx(), StrokeCap.Round)
    }
}

private val GLOW = 8.dp
private val LINE = 2.dp
private const val GLOW_ALPHA = 0.25f
