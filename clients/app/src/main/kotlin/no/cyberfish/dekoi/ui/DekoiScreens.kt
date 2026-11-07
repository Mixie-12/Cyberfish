package no.cyberfish.dekoi.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import no.cyberfish.dekoi.DecoyEntrance
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SetupStep
import no.cyberfish.dekoi.cleanName
import no.cyberfish.dekoi.decoyEntrances
import no.cyberfish.dekoi.hasDone
import no.cyberfish.dekoi.nameToSave
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.FishMark
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Section
import no.cyberfish.dekoi.ui.components.StaleLabel
import java.time.Instant

private const val PULSE_MS = 2000
private const val PULSE_ALPHA = 0.3f
private val STATUS_DOT = 8.dp
private val PULSE_REACH = 6.dp
private val STEP_MARK = 24.dp
private val STEP_TICK = 16.dp
private val STEP_DOT = 8.dp
private val STEP_BORDER = 2.dp
private val STEP_LINE = 2.dp
private val HERO_MARK = 48.dp

/** Online, offline or setting up, in words. */
@StringRes
fun statusWord(status: DekoiStatus): Int =
    when (status) {
        DekoiStatus.ONLINE -> R.string.device_online
        DekoiStatus.OFFLINE -> R.string.device_offline
        DekoiStatus.SETTING_UP -> R.string.device_setting_up
    }

/** The colour of [statusWord]. */
@Composable
fun statusColor(status: DekoiStatus): Color =
    when (status) {
        DekoiStatus.ONLINE -> LocalSeverityColors.current.safe.color
        DekoiStatus.OFFLINE -> LocalSeverityColors.current.offline
        DekoiStatus.SETTING_UP -> MaterialTheme.colorScheme.primary
    }

/** "Checked in 2 minutes ago", or that it has not yet. */
@Composable
fun checkedIn(
    device: Device,
    now: Instant,
): String =
    device.lastSeenAt?.let { stringResource(R.string.device_checked_in, relativeTime(it, now)) }
        ?: stringResource(R.string.device_never_checked_in)

/**
 * A dot in a status colour. With [pulse] a faint halo keeps spreading from
 * it, the one idle loop a hero may have; never when animations are off.
 */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    pulse: Boolean = false,
) {
    if (!pulse || LocalReduceMotion.current) {
        Box(modifier.size(STATUS_DOT).background(color, CircleShape))
        return
    }
    val transition = rememberInfiniteTransition(label = "pulse")
    val spread by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(PULSE_MS, easing = LinearOutSlowInEasing)), label = "pulse")
    Canvas(modifier.size(STATUS_DOT)) {
        val radius = size.minDimension / 2
        drawCircle(color.copy(alpha = PULSE_ALPHA * (1 - spread)), radius + PULSE_REACH.toPx() * spread)
        drawCircle(color, radius)
    }
}

@Composable
fun DekoiScreen(
    data: OwnerData,
    now: Instant,
    deviceId: String,
    onRename: (Device, String) -> Unit,
    onRemove: (Device) -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val device = data.devices.firstOrNull { it.id == deviceId }
    if (device == null) {
        MissingScreen(R.string.home_your_dekoi, data.loaded, R.drawable.ic_device_unknown, R.string.dekoi_missing, onBack)
        return
    }
    val status = device.status(now)
    val still = LocalReduceMotion.current
    var confirming by rememberSaveable { mutableStateOf(false) }
    Screen(stringResource(R.string.home_your_dekoi), onBack = onBack, subtitle = { StaleLabel(data.updatedAt, now) }) {
        item(key = "hero") { Hero(device, status, now, Modifier.itemMotion(this, still)) }
        var section = 0
        if (status == DekoiStatus.SETTING_UP) {
            val number = ++section
            item(key = "setup") { SetupChecklist(number, device, now, Modifier.itemMotion(this, still)) }
        } else {
            val number = ++section
            item(key = "watching") { Watching(number, device, status, now, Modifier.itemMotion(this, still)) }
        }
        val uptime = ++section
        item(key = "uptime") { UptimeCard(uptime, device, data.events, now, Modifier.itemMotion(this, still)) }
        if (device.services.isNotEmpty()) {
            val number = ++section
            item(key = "decoys") { Decoys(number, device, data.events, now, onNavigate, Modifier.itemMotion(this, still)) }
        }
        val entrances = device.decoyEntrances(now)
        if (entrances.isNotEmpty()) {
            val number = ++section
            item(key = "try") { TryDecoy(number, entrances, Modifier.itemMotion(this, still)) }
        }
        val details = ++section
        item(key = "details") { Details(details, device, onRename, Modifier.itemMotion(this, still)) }
        item(key = "remove") {
            InsetSection(Modifier.itemMotion(this, still)) {
                InsetRow(
                    stringResource(R.string.device_remove),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirming = true },
                    chevron = false,
                )
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = R.string.device_remove_confirm_title,
            body = R.string.device_remove_confirm_body,
            confirm = R.string.action_remove,
            onConfirm = {
                confirming = false
                onRemove(device)
            },
            onDismiss = { confirming = false },
        )
    }
}

/** The mark, name, status and last check-in inside the brand's HUD frame. */
@Composable
private fun Hero(
    device: Device,
    status: DekoiStatus,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    HudFrame(modifier.fillMaxWidth(), contentPadding = Dimens.spaceXl) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
        ) {
            FishMark(HERO_MARK)
            Text(device.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Row(
                Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
            ) {
                StatusDot(statusColor(status), pulse = status == DekoiStatus.ONLINE)
                Text(stringResource(statusWord(status)), style = MaterialTheme.typography.labelLarge)
            }
            Text(
                checkedIn(device, now),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The steps as a line down the screen, and help once setup takes longer than it should. */
@Composable
private fun SetupChecklist(
    number: Int,
    device: Device,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val current = SetupStep.entries.firstOrNull { !device.hasDone(it) }
    Section(number, stringResource(R.string.dekoi_setup), modifier) {
        Column {
            SetupStep.entries.forEach { step ->
                SetupStepRow(step, device.hasDone(step), current = step == current, last = step == SetupStep.entries.last())
            }
        }
        if (device.needsSetupHelp(now)) SetupHelp()
    }
}

/** One step of the checklist: its mark, a line down to the next step, and its name. */
@Composable
private fun SetupStepRow(
    step: SetupStep,
    done: Boolean,
    current: Boolean,
    last: Boolean,
) {
    val state =
        stringResource(
            when {
                done -> R.string.dekoi_step_done
                current -> R.string.dekoi_step_current
                else -> R.string.dekoi_step_pending
            },
        )
    val line = if (done) LocalSeverityColors.current.safe.color else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.height(IntrinsicSize.Min).semantics(mergeDescendants = true) { stateDescription = state },
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMark(done, current)
            if (!last) Box(Modifier.width(STEP_LINE).weight(1f).background(line))
        }
        Text(
            stringResource(step.label),
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (last) 0.dp else Dimens.section),
        )
    }
}

/** A hollow circle; the current step has a dot in it, and a finished one turns green with a tick that grows in as it happens. */
@Composable
private fun StepMark(
    done: Boolean,
    current: Boolean,
) {
    val safe = LocalSeverityColors.current.safe.color
    val tint = MaterialTheme.colorScheme.primary
    val tick by animateFloatAsState(if (done) 1f else 0f, if (LocalReduceMotion.current) snap() else expandSpring(), label = "tick")
    Box(Modifier.size(STEP_MARK), contentAlignment = Alignment.Center) {
        val ring =
            if (done) {
                safe
            } else if (current) {
                tint
            } else {
                MaterialTheme.colorScheme.outlineVariant
            }
        Box(Modifier.matchParentSize().border(STEP_BORDER, ring, CircleShape))
        if (current) Box(Modifier.size(STEP_DOT).background(tint, CircleShape))
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            Modifier.size(STEP_TICK).graphicsLayer {
                scaleX = tick
                scaleY = tick
                alpha = tick
            },
            tint = safe,
        )
    }
}

/**
 * How to see the decoy as an intruder would, from a computer on the same
 * network (ADR 0018): what to open, where, and its address. Trying it
 * sends an alert, as for an intruder.
 */
@Composable
private fun TryDecoy(
    number: Int,
    entrances: Map<DecoyEntrance, String>,
    modifier: Modifier = Modifier,
) {
    Section(number, stringResource(R.string.dekoi_try), modifier) {
        Text(stringResource(R.string.dekoi_try_body), style = MaterialTheme.typography.bodyLarge)
        Column {
            entrances.forEach { (entrance, address) ->
                HorizontalDivider()
                EntranceRow(entrance, address)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun EntranceRow(
    entrance: DecoyEntrance,
    address: String,
) {
    val (what, where) =
        when (entrance) {
            DecoyEntrance.LOGIN_PAGE -> R.string.dekoi_try_login to R.string.dekoi_try_login_where
            DecoyEntrance.WINDOWS_SHARE -> R.string.dekoi_try_windows to R.string.dekoi_try_windows_where
            DecoyEntrance.MAC_SHARE -> R.string.dekoi_try_mac to R.string.dekoi_try_mac_where
        }
    Column(Modifier.fillMaxWidth().padding(vertical = Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs)) {
        Text(stringResource(what), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(where), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Selectable, so a long press copies it to send to the computer.
        SelectionContainer(Modifier.padding(top = Dimens.spaceXs)) {
            Text(address, style = MaterialTheme.typography.address)
        }
    }
}

@Composable
private fun Details(
    number: Int,
    device: Device,
    onRename: (Device, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    Section(number, stringResource(R.string.dekoi_details), modifier) {
        InsetSection(Modifier.animateContentSize(spec)) {
            NameRow(device, onRename)
            device.localIp?.let { KeyValueRow(stringResource(R.string.device_address), it) }
            KeyValueRow(stringResource(R.string.dekoi_linked_since), absoluteTime(device.createdAt))
        }
    }
}

/** The name, renamed in place: the row opens into a text field. */
@Composable
private fun NameRow(
    device: Device,
    onRename: (Device, String) -> Unit,
) {
    var editing by rememberSaveable(device.id) { mutableStateOf(false) }
    if (editing) {
        NameEditor(device, onRename, onDone = { editing = false })
        return
    }
    InsetRow(
        stringResource(R.string.device_name),
        subtitle = device.name,
        trailing = {
            Text(
                stringResource(R.string.dekoi_rename),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        },
        onClick = { editing = true },
        chevron = false,
    )
}

@Composable
private fun NameEditor(
    device: Device,
    onRename: (Device, String) -> Unit,
    onDone: () -> Unit,
) {
    // The whole name starts selected, so typing replaces it.
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(device.name, TextRange(0, device.name.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val name = nameToSave(field.text, device.name)
    val save = {
        name?.let { onRename(device, it) }
        onDone()
    }
    Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        OutlinedTextField(
            value = field,
            onValueChange = { field = it.copy(text = cleanName(it.text)) },
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            label = { Text(stringResource(R.string.device_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
        )
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) }
            PrimaryButton(onClick = save, enabled = name != null) { Text(stringResource(R.string.action_save)) }
        }
    }
}
