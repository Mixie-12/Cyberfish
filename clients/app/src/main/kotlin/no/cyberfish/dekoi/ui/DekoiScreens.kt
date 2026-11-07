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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
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
import no.cyberfish.dekoi.DekoiStatus
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.Monitor
import no.cyberfish.dekoi.MonitorKind
import no.cyberfish.dekoi.MonitorState
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SetupStep
import no.cyberfish.dekoi.Uptime
import no.cyberfish.dekoi.cleanName
import no.cyberfish.dekoi.hasDone
import no.cyberfish.dekoi.hourlyUptime
import no.cyberfish.dekoi.nameToSave
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.serviceName
import no.cyberfish.dekoi.softwareUpdatedAt
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.touchesPerDay
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.MissingScreen
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.Sparkline
import no.cyberfish.dekoi.ui.components.StaleLabel
import no.cyberfish.dekoi.ui.components.UptimeStrip
import no.cyberfish.dekoi.watchList
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val PULSE_MS = 2000
private const val PULSE_ALPHA = 0.3f
private val STATUS_DOT = 8.dp
private val PULSE_REACH = 6.dp
private val STEP_MARK = 24.dp
private val STEP_TICK = 16.dp
private val STEP_DOT = 8.dp
private val STEP_BORDER = 2.dp
private val STEP_LINE = 2.dp

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
        if (status == DekoiStatus.SETTING_UP) {
            item(key = "setup") { SetupChecklist(device, now, Modifier.itemMotion(this, still)) }
        } else if (device.watchList.isNotEmpty()) {
            item(key = "watching") { Watching(device, status, Modifier.itemMotion(this, still)) }
        }
        item(key = "uptime") { UptimeCard(device, data.events, now, Modifier.itemMotion(this, still)) }
        if (device.services.isNotEmpty()) {
            item(key = "decoys") { Decoys(device, data.events, now, onNavigate, Modifier.itemMotion(this, still)) }
        }
        item(key = "details") { Details(device, now, onRename, Modifier.itemMotion(this, still)) }
        if (device.monitors.isNotEmpty() || device.images.isNotEmpty()) {
            item(key = "technical") { TechnicalDetails(device, Modifier.itemMotion(this, still)) }
        }
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

@Composable
private fun Hero(
    device: Device,
    status: DekoiStatus,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceM),
    ) {
        BrandTile(hero = true)
        Text(device.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(horizontal = Dimens.spaceM, vertical = Dimens.spaceS)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
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

@StringRes
private fun label(step: SetupStep): Int =
    when (step) {
        SetupStep.LINKED -> R.string.setup_linked
        SetupStep.FIRST_START -> R.string.setup_first_start
        SetupStep.ONLINE -> R.string.setup_online
        SetupStep.WATCHING -> R.string.setup_protecting
    }

@Composable
private fun SetupChecklist(
    device: Device,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    val current = SetupStep.entries.firstOrNull { !device.hasDone(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Dimens.section)) {
        InsetSection(title = stringResource(R.string.dekoi_setup)) {
            Column(Modifier.padding(Dimens.spaceL)) {
                SetupStep.entries.forEach { step ->
                    SetupStepRow(step, device.hasDone(step), current = step == current, last = step == SetupStep.entries.last())
                }
            }
        }
        if (device.needsSetupHelp(now)) {
            InsetSection(title = stringResource(R.string.setup_help_title)) {
                Text(
                    stringResource(R.string.setup_help_body),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Dimens.spaceL),
                )
            }
        }
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
            stringResource(label(step)),
            style = MaterialTheme.typography.bodyLarge,
            color = if (done || current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = if (last) 0.dp else Dimens.section),
        )
    }
}

/** A hollow circle; the current step has a dot in it, and a finished one fills with a tick that grows in as it happens. */
@Composable
private fun StepMark(
    done: Boolean,
    current: Boolean,
) {
    val safe = LocalSeverityColors.current.safe.color
    val tint = MaterialTheme.colorScheme.primary
    val tick by animateFloatAsState(if (done) 1f else 0f, if (LocalReduceMotion.current) snap() else expandSpring(), label = "tick")
    Box(Modifier.size(STEP_MARK), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().border(STEP_BORDER, if (current) tint else MaterialTheme.colorScheme.outlineVariant, CircleShape))
        if (current) Box(Modifier.size(STEP_DOT).background(tint, CircleShape))
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = tick
                    scaleY = tick
                    alpha = tick
                }.background(safe, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                Modifier.size(STEP_TICK),
                tint = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

@Composable
private fun Details(
    device: Device,
    now: Instant,
    onRename: (Device, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spec = if (LocalReduceMotion.current) snap() else expandSpring<IntSize>()
    InsetSection(modifier.animateContentSize(spec), title = stringResource(R.string.dekoi_details)) {
        NameRow(device, onRename)
        device.agentVersion?.let { version ->
            InsetRow(
                stringResource(R.string.device_version),
                subtitle = device.softwareUpdatedAt?.let { stringResource(R.string.updated_ago, relativeTime(it, now)) },
                trailing = {
                    Text(version, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
            )
        }
        device.localIp?.let { KeyValueRow(stringResource(R.string.device_address), it) }
        KeyValueRow(stringResource(R.string.dekoi_linked_since), absoluteTime(device.createdAt))
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
            Button(onClick = save, enabled = name != null) { Text(stringResource(R.string.action_save)) }
        }
    }
}
