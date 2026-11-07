package no.cyberfish.dekoi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.CODE_GROUP
import no.cyberfish.dekoi.DekoiApplication
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.SetupStep
import no.cyberfish.dekoi.groupedOffset
import no.cyberfish.dekoi.hasDone
import no.cyberfish.dekoi.isSetUp
import no.cyberfish.dekoi.needsSetupHelp
import no.cyberfish.dekoi.scanQrCode
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.typedCode
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ErrorBanner
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KindTile
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.components.StatusRing
import no.cyberfish.dekoi.ui.motion.rememberHaptics
import no.cyberfish.dekoi.ungroupedOffset
import java.time.Instant

private const val POLL_MS = 15_000L
private const val STEP_MS = 300
private val STEP_DISTANCE = 30.dp

private val ART_HEIGHT = 168.dp
private val CABLE = 72.dp
private const val DRAW_IN_MS = 700
private const val SIGNAL_MS = 3000

private val LINE = 2.dp

private val STEP_MARK = 24.dp
private val STEP_TICK = 16.dp
private val STEP_DOT = 8.dp
private val STEP_BORDER = 2.dp

/**
 * Adding a DeKoi (ADR 0017): its card carries a claim code. The phone writes
 * the card and claims the code itself (Route.WriteCard), or the owner scans
 * or types the code DeKoi Setup showed. Then the setup timeline follows the
 * new DeKoi; [onRefresh] reloads the account's devices for it.
 */
@Composable
fun AddDekoiScreen(
    data: OwnerData,
    now: Instant,
    onRefresh: () -> Unit,
    onNavigate: (Route) -> Unit,
    onBack: () -> Unit,
) {
    val add: AddDekoiViewModel = viewModel { AddDekoiViewModel((this[APPLICATION_KEY] as DekoiApplication).backend) }
    val linkedId by add.linked.collectAsStateWithLifecycle()
    val claiming by add.claiming.collectAsStateWithLifecycle()
    val problem by add.problem.collectAsStateWithLifecycle()
    val device = linkedId?.let { id -> data.devices.firstOrNull { it.id == id } }
    val settingUp = linkedId != null && device?.isSetUp != true
    Poll(settingUp, onRefresh)
    val haptics = rememberHaptics()
    // Confirms once that the code linked; not again when rotating composes the screen anew.
    var confirmed by rememberSaveable { mutableStateOf(linkedId != null) }
    LaunchedEffect(linkedId != null) {
        if (linkedId == null || confirmed) return@LaunchedEffect
        haptics.confirm()
        confirmed = true
    }

    Screen(stringResource(R.string.add_title), onBack = onBack) {
        val failedReload = data.problem?.takeIf { settingUp }
        if (failedReload != null) item { ErrorBanner(stringResource(failedReload), onRetry = onRefresh) }
        item {
            Steps(linked = linkedId != null) { linked ->
                if (linked) {
                    SetupProgress(device, now, onDone = onBack)
                } else {
                    Prepare(onWriteCard = { onNavigate(Route.WriteCard) }) {
                        CodeEntry(problem, claiming, add::claim, add::retry)
                    }
                }
            }
        }
    }
}

/**
 * A new DeKoi's setup: the ring fills and the steps tick off as it reports
 * in, with help after 20 minutes. [device] is null until the account's
 * device list has it.
 */
@Composable
fun SetupProgress(
    device: Device?,
    now: Instant,
    onDone: () -> Unit,
) {
    val done = SetupStep.entries.count { device.hasDone(it) }
    val finished = device?.isSetUp == true
    val started = device?.registeredAt != null
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.section)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            StatusRing(
                if (finished) LocalSeverityColors.current.safe.color else MaterialTheme.colorScheme.primary,
                fraction = done.toFloat() / SetupStep.entries.size,
            )
        }
        Headline(stringResource(if (finished) R.string.add_linked_done else R.string.add_linked_title))
        if (!started) {
            InsetSection(title = stringResource(R.string.add_plug_in_title)) {
                StepList {
                    StepItem(R.drawable.ic_sd_card, R.string.add_plug_in_card)
                    StepItem(R.drawable.ic_cable, R.string.add_plug_in_cable)
                    StepItem(R.drawable.ic_power, R.string.add_plug_in_power)
                }
            }
        }
        InsetSection(title = stringResource(R.string.dekoi_setup)) { SetupTimeline(device, Modifier.padding(Dimens.spaceL)) }
        if (device?.needsSetupHelp(now) == true) SetupHelp()
        PrimaryButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.add_done), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Reloads the device list every few seconds while [active] and the screen is visible. */
@Composable
private fun Poll(
    active: Boolean,
    onRefresh: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(active, lifecycle) {
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                refresh()
                delay(POLL_MS)
            }
        }
    }
}

/** Linking slides the setup in along the reading direction, as a pushed screen does. */
@Composable
private fun Steps(
    linked: Boolean,
    content: @Composable (Boolean) -> Unit,
) {
    val reduceMotion = LocalReduceMotion.current
    val reading = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val distance = with(LocalDensity.current) { STEP_DISTANCE.roundToPx() } * reading
    AnimatedContent(
        targetState = linked,
        transitionSpec = {
            if (reduceMotion) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (slideInHorizontally(tween(STEP_MS)) { distance } + fadeIn(tween(STEP_MS))) togetherWith
                    (slideOutHorizontally(tween(STEP_MS)) { -distance } + fadeOut(tween(STEP_MS)))
            }
        },
        label = "add step",
    ) { content(it) }
}

@Composable
private fun Prepare(
    onWriteCard: () -> Unit,
    codeEntry: @Composable () -> Unit,
) {
    var fromComputer by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    val haptics = rememberHaptics()
    Column(
        (if (reduceMotion) Modifier else Modifier.animateContentSize(expandSpring())).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Dimens.section),
    ) {
        PrepareArt()
        Headline(stringResource(R.string.add_prepare_title), stringResource(R.string.add_prepare_body))
        Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
            PrimaryButton(
                onClick = onWriteCard,
                modifier = Modifier.fillMaxWidth(),
            ) { ButtonLabel(R.drawable.ic_sd_card, R.string.add_write_card) }
            Text(
                stringResource(R.string.add_write_card_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        OutlinedButton(
            onClick = {
                fromComputer = !fromComputer
                haptics.toggle(fromComputer)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            ButtonLabel(R.drawable.ic_computer, R.string.add_computer_card)
        }
        if (fromComputer) codeEntry()
    }
}

/** The code DeKoi Setup shows, scanned or typed. */
@Composable
private fun CodeEntry(
    problem: Int?,
    claiming: Boolean,
    onClaim: (ClaimCode) -> Unit,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by rememberSaveable { mutableStateOf("") }
    var scanProblem by rememberSaveable { mutableStateOf<Int?>(null) }
    val code = ClaimCode.parse(typed)
    val invalid = typed.length == ClaimCode.LENGTH && code == null

    fun scan() {
        scanProblem = null
        scope.launch {
            try {
                val raw = scanQrCode(context) ?: return@launch
                ClaimCode.parse(raw)?.let(onClaim) ?: run { scanProblem = R.string.add_invalid }
            } catch (_: MlKitException) {
                scanProblem = R.string.add_scan_failed
            }
        }
    }

    fun submit() {
        scanProblem = null
        code?.let(onClaim)
    }

    InsetSection {
        Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceM)) {
            Text(
                stringResource(R.string.add_code_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(onClick = ::scan, enabled = !claiming, modifier = Modifier.fillMaxWidth()) {
                ButtonLabel(R.drawable.ic_qr_code_scanner, R.string.add_scan)
            }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = typedCode(it) },
                label = { Text(stringResource(R.string.add_manual_label)) },
                singleLine = true,
                textStyle = MaterialTheme.typography.address,
                visualTransformation = GroupsOfFour,
                isError = invalid,
                supportingText = if (invalid) ({ Text(stringResource(R.string.add_invalid)) }) else null,
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            PrimaryButton(onClick = ::submit, enabled = code != null && !claiming, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_manual_button), style = MaterialTheme.typography.labelLarge)
            }
            val message = scanProblem ?: problem
            if (message != null) {
                ErrorBanner(stringResource(message), onRetry = if (scanProblem != null) ::scan else onRetry)
            }
        }
    }
}

/** Shows a code as groups of four ("7F3K Q2RX ...") without storing the spaces. */
private object GroupsOfFour : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val mapping =
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int): Int = groupedOffset(offset)

                override fun transformedToOriginal(offset: Int): Int = ungroupedOffset(offset, text.length)
            }
        return TransformedText(AnnotatedString(text.text.chunked(CODE_GROUP).joinToString(" ")), mapping)
    }
}

/** A row of an InsetSection that opens Add DeKoi: a plus and [label] in the accent. On Home and in Settings. */
@Composable
fun AddDekoiRow(
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    InsetRow(
        stringResource(label),
        titleColor = MaterialTheme.colorScheme.primary,
        leading = { Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        onClick = onClick,
    )
}

/** What to check when a new DeKoi takes longer than it should to appear; here and on Your DeKoi. */
@Composable
fun SetupHelp() {
    InsetSection(title = stringResource(R.string.setup_help_title), footer = stringResource(R.string.add_help_again)) {
        StepList {
            StepItem(R.drawable.ic_sd_card, R.string.add_help_card)
            StepItem(R.drawable.ic_power, R.string.add_help_power)
            StepItem(R.drawable.ic_cable, R.string.add_help_cable)
            StepItem(R.drawable.ic_wifi, R.string.add_help_wifi)
        }
    }
}

/**
 * The setup steps down a line (linked, first start, online, watching),
 * ticking off as [device] reports in; null is a DeKoi just linked that the
 * account's device list does not have yet. Here and on Your DeKoi.
 */
@Composable
fun SetupTimeline(
    device: Device?,
    modifier: Modifier = Modifier,
) {
    val steps = SetupStep.entries
    val current = steps.firstOrNull { !device.hasDone(it) }
    Column(modifier) {
        steps.forEach { step ->
            SetupStepRow(step, device.hasDone(step), current = step == current, last = step == steps.last())
        }
    }
}

/** One step: its mark, a line down to the next step, and its name. */
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
            if (!last) Box(Modifier.width(LINE).weight(1f).background(line))
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
    val ring =
        when {
            done -> safe
            current -> tint
            else -> MaterialTheme.colorScheme.outlineVariant
        }
    Box(Modifier.size(STEP_MARK), contentAlignment = Alignment.Center) {
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

/** A router, a cable that draws in once, and the DeKoi; then a signal travels the cable. */
@Composable
private fun PrepareArt() {
    val reduceMotion = LocalReduceMotion.current
    val drawn = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { drawn.animateTo(1f, tween(DRAW_IN_MS, easing = FastOutSlowInEasing)) }
    val signal =
        if (reduceMotion) {
            null
        } else {
            rememberInfiniteTransition(label = "cable").animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(SIGNAL_MS, easing = LinearEasing)),
                label = "cable",
            )
        }
    val cable = MaterialTheme.colorScheme.outline
    val dot = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().height(ART_HEIGHT).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(HostKind.ROUTER, containerColor = MaterialTheme.colorScheme.surface)
        Canvas(Modifier.width(CABLE).height(Dimens.tile)) {
            val y = size.height / 2
            val stroke = LINE.toPx()
            drawLine(cable, Offset(0f, y), Offset(size.width * drawn.value, y), stroke, StrokeCap.Round)
            if (signal != null && drawn.value == 1f) drawCircle(dot, stroke * 2, Offset(size.width * signal.value, y))
        }
        BrandTile(hero = true)
    }
}

/** A step's one sentence, centred, read out when it changes. */
@Composable
private fun Headline(
    title: String,
    body: String? = null,
) {
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        body?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StepList(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceL), content = content)
}

/** An instruction with its symbol; the text wraps freely, so it survives large fonts. */
@Composable
private fun StepItem(
    @DrawableRes icon: Int,
    @StringRes text: Int,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spaceL), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ButtonLabel(
    @DrawableRes icon: Int,
    @StringRes label: Int,
) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(stringResource(label), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
}
