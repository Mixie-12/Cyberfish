package no.cyberfish.dekoi.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CutCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.cyberfish.dekoi.DekoiApplication
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Reader
import no.cyberfish.dekoi.WriteCardService
import no.cyberfish.dekoi.WriteFailed
import no.cyberfish.dekoi.WriteFailure
import no.cyberfish.dekoi.WriteState
import no.cyberfish.dekoi.allowReader
import no.cyberfish.dekoi.cardReaders
import no.cyberfish.dekoi.openCard
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen

/**
 * Writing a card with this phone through a USB card reader (ADR 0014, 0017):
 * connect, choose the reader, confirm "Erase this card", write, link the
 * card's code to the account with [onClaim], then power on.
 */
@Composable
fun WriteCardScreen(
    onClaim: (ClaimCode, (Int) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val writing = remember { (context.applicationContext as DekoiApplication).cardWriting }
    val state by writing.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Each visit starts over, unless a card is being written or waits to be
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

    // Leaving lets go of a card that is open but not confirmed.
    val leave = {
        writing.reset()
        onBack()
    }
    BackHandler(onBack = leave)

    fun open(reader: Reader) {
        if (!writing.opening()) return
        scope.launch {
            if (!allowReader(context, reader)) return@launch writing.failed(WriteFailure.DENIED)
            // Not cancellable: an opened card always reaches CardWriting, which closes it if no longer wanted.
            withContext(Dispatchers.IO + NonCancellable) {
                try {
                    writing.opened(openCard(context, reader))
                } catch (error: WriteFailed) {
                    writing.failed(error.failure)
                }
            }
        }
    }

    Screen(stringResource(R.string.write_title), onBack = leave) {
        item {
            when (val current = state) {
                WriteState.Connect -> {
                    Connect { writing.look(cardReaders(context)) }
                }

                is WriteState.Choose -> {
                    Choose(current.readers, ::open) { writing.look(cardReaders(context)) }
                }

                WriteState.Opening -> {
                    Step(R.string.write_step_choose) { Body(stringResource(R.string.write_opening)) }
                }

                is WriteState.Confirm -> {
                    Confirm(current, onErase = { startWriting(context) }, onCancel = writing::reset)
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

// Started while the screen is in front; the service moves itself to the foreground.
private fun startWriting(context: Context) {
    context.startService(Intent(context, WriteCardService::class.java))
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
    readers: List<Reader>,
    onChoose: (Reader) -> Unit,
    onLookAgain: () -> Unit,
) {
    Step(R.string.write_step_choose) {
        if (readers.isEmpty()) {
            Body(stringResource(R.string.write_no_reader))
            Action(R.string.write_look_again, onLookAgain)
            return@Step
        }
        Body(stringResource(R.string.write_choose_body))
        Column {
            readers.forEach { reader ->
                HorizontalDivider()
                Text(
                    reader.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.row)
                            .clickable(role = Role.Button) { onChoose(reader) }
                            .padding(vertical = Dimens.spaceL),
                )
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun Confirm(
    card: WriteState.Confirm,
    onErase: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    RefuseObscuredTouches()
    Step(R.string.write_step_confirm) {
        HudFrame(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                Text(card.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    Formatter.formatShortFileSize(context, card.bytes),
                    style = MaterialTheme.typography.bodyLarge.tabular(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Body(stringResource(R.string.write_confirm_body, Formatter.formatShortFileSize(context, CardContent.image.downloadSize)))
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
    val image = CardContent.image
    // Checking reads what writing wrote, several times faster.
    val fraction = (WRITE_SHARE * progress.written + (1 - WRITE_SHARE) * progress.checked) / image.extractSize
    Step(R.string.write_step_writing) {
        GlowLine(fraction.toFloat())
        Column {
            Phase(R.string.write_phase_download, progress.downloaded, image.downloadSize)
            Phase(R.string.write_phase_write, progress.written, image.extractSize)
            Phase(R.string.write_phase_check, progress.checked, image.extractSize)
            HorizontalDivider()
        }
        Body(stringResource(R.string.write_keep_connected))
    }
}

private const val WRITE_SHARE = 0.8

@Composable
private fun Phase(
    @StringRes name: Int,
    done: Long,
    total: Long,
) {
    val context = LocalContext.current
    val finished = done >= total
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
        } else {
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

/** The card is written, but linking it failed: try again, or keep the code to type later. */
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
    val context = LocalContext.current
    val message =
        when (failed.failure) {
            WriteFailure.NO_CARD -> {
                stringResource(R.string.write_failed_no_card)
            }

            WriteFailure.SEVERAL_CARDS -> {
                stringResource(R.string.write_failed_several)
            }

            WriteFailure.DENIED -> {
                stringResource(R.string.write_failed_denied)
            }

            WriteFailure.UNSUITABLE -> {
                stringResource(R.string.write_failed_unsuitable, Formatter.formatShortFileSize(context, failed.bytes ?: 0))
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
        Action(R.string.action_try_again, onTryAgain)
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
