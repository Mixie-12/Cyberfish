package no.cyberfish.dekoi.burner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.Network
import java.awt.Dimension
import java.text.Collator
import java.util.Locale

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = text("app_name"), state = rememberWindowState(width = 840.dp, height = 780.dp)) {
            // AWT sizes are in the same scaled units as dp. The minimum fits
            // the content column and the QR code next to the claim code.
            LaunchedEffect(window) { window.minimumSize = Dimension(720, 560) }
            val scope = rememberCoroutineScope()
            val setup = remember { Setup(scope) }
            val screen by setup.screen.collectAsState()
            DekoiTheme { SetupWindow(screen, setup) }
        }
    }

@Composable
private fun SetupWindow(
    screen: Screen,
    setup: Setup,
) {
    val step = screen.step
    // The welcome screen is the title page: centred, with the wordmark large instead of the header.
    val title = screen == Screen.Welcome
    Column(Modifier.fillMaxSize().background(Brand.background)) {
        if (!title) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Lockup()
                Spacer(Modifier.weight(1f))
                step?.let { StepRail(it) }
            }
            Hairline()
        }
        Box(Modifier.weight(1f)) {
            // Each screen opens at its top, wherever the one before was scrolled to.
            val scroll = remember(screen) { ScrollState(0) }
            Column(
                Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 40.dp, vertical = 32.dp),
                verticalArrangement = if (title) Arrangement.Center else Arrangement.Top,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    step?.let { StepLabel(it) }
                    SetupScreen(screen, setup)
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(4.dp))
        }
    }
}

@Composable
private fun ColumnScope.SetupScreen(
    screen: Screen,
    setup: Setup,
) {
    when (screen) {
        Screen.Welcome -> {
            Welcome(setup)
        }

        Screen.Looking -> {
            Waiting(text("looking"))
        }

        Screen.NotWindows -> {
            Heading(text("not_windows_heading"))
            Body(text("not_windows_body"))
        }

        is Screen.NoImager -> {
            NoImager(screen.installFailed, setup)
        }

        Screen.Installing -> {
            Waiting(text("installing_heading"), text("installing_body"))
        }

        is Screen.ChooseCard -> {
            ChooseCard(screen.drives, setup)
        }

        is Screen.ListFailed -> {
            Failed(text("list_failed_heading"), text("list_failed_body"), screen.details, setup)
        }

        is Screen.ChooseNetwork -> {
            ChooseNetwork(screen.drive, screen.network, setup)
        }

        is Screen.Confirm -> {
            Confirm(screen.drive, screen.network, setup)
        }

        Screen.Writing -> {
            Waiting(text("writing_heading"), text("writing_time"), text("writing_leave"))
        }

        is Screen.WriteFailed -> {
            Failed(text("write_failed_heading"), text("write_failed_body"), screen.details, setup)
        }

        is Screen.Done -> {
            Done(screen.code, screen.cable, setup)
        }
    }
}

@Composable
private fun ColumnScope.Welcome(setup: Setup) {
    Horizon(markSize = 64.sp, seaHeight = 72.dp, glow = true)
    Text(text("app_name"), Modifier.align(Alignment.CenterHorizontally), style = Type.wordmark)
    Body(text("welcome_purpose"), TextAlign.Center)
    Body(text("welcome_code"), TextAlign.Center)
    Action(text("action_start"), setup::lookForCards, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp), primary = true)
}

@Composable
private fun NoImager(
    installFailed: Boolean,
    setup: Setup,
) {
    Heading(text("no_imager_heading"))
    Body(text("no_imager_body"))
    if (installFailed) {
        SelectionContainer {
            Body(text("no_imager_failed", Imager.DOWNLOAD_PAGE))
        }
    }
    Actions {
        Action(text("action_look_again"), setup::lookForCards)
        Action(text("action_install_imager"), setup::installImager, primary = true)
    }
}

@Composable
private fun ChooseCard(
    drives: List<Drive>,
    setup: Setup,
) {
    if (drives.isEmpty()) {
        Heading(text("no_card_heading"))
        Body(text("no_card_body"))
    } else {
        Heading(text("choose_card_heading"))
        Body(text("choose_card_body"))
        Column(Modifier.fillMaxWidth().hudFrame()) {
            drives.forEachIndexed { index, drive ->
                if (index > 0) Hairline()
                CardRow(drive) { setup.choose(drive) }
            }
        }
    }
    Actions { Action(text("action_look_again"), setup::lookForCards) }
}

@Composable
private fun CardRow(
    drive: Drive,
    onChoose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onChoose).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(drive.label, style = Type.strong)
            Secondary(size(drive))
        }
        Text(text("action_choose").uppercase(Locale.ROOT), style = Type.label)
    }
}

@Composable
private fun ChooseNetwork(
    drive: Drive,
    network: Network,
    setup: Setup,
) {
    val entered = network as? Network.WiFi
    var wifi by remember { mutableStateOf(entered != null) }
    var name by remember { mutableStateOf(entered?.ssid ?: "") }
    var password by remember { mutableStateOf(entered?.password ?: "") }
    var country by remember { mutableStateOf(entered?.country ?: Locale.getDefault().country.takeIf { it in countries } ?: "GB") }
    var problem by remember { mutableStateOf<String?>(null) }

    Heading(text("connect_heading"))
    Body(text("connect_body"))
    Column(Modifier.selectableGroup()) {
        Choice(text("cable_title"), text("cable_detail"), !wifi) { wifi = false }
        Choice(text("wifi_title"), text("wifi_detail"), wifi) { wifi = true }
    }
    if (wifi) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text(text("wifi_name")) }, singleLine = true)
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text(text("wifi_password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        CountryPicker(country) { country = it }
    }
    problem?.let {
        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Type.body.copy(color = Brand.critical))
    }
    Actions {
        Action(text("action_back"), setup::lookForCards)
        Action(text("action_continue"), primary = true, onClick = {
            try {
                setup.confirm(drive, if (wifi) Network.WiFi(name, password, country) else Network.Cable)
            } catch (e: IllegalArgumentException) {
                problem = e.message
            }
        })
    }
}

@Composable
private fun CountryPicker(
    country: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(text("country"))
        Secondary(text("country_detail"))
        Box {
            Action(countries.getValue(country), onClick = { open = true }, Modifier.border(1.dp, Brand.midGrey, ControlShape))
            DropdownMenu(open, onDismissRequest = { open = false }, Modifier.heightIn(max = 360.dp)) {
                for ((code, name) in countries) {
                    DropdownMenuItem(text = { Text(name) }, onClick = {
                        onChange(code)
                        open = false
                    })
                }
            }
        }
    }
}

@Composable
private fun Confirm(
    drive: Drive,
    network: Network,
    setup: Setup,
) {
    Heading(text("erase_heading"))
    Body(text("erase_body"))
    Lines(
        listOf(
            text("erase_card") to "${drive.label}, ${size(drive)}",
            text("erase_network") to
                when (network) {
                    Network.Cable -> text("erase_cable")
                    is Network.WiFi -> text("erase_wifi", network.ssid)
                },
        ),
        labelWidth = 120.dp,
    )
    Body(text("erase_permission"))
    Actions {
        Action(text("action_back"), { setup.choose(drive, network) })
        Action(text("action_erase_and_write"), { setup.write(drive, network) }, primary = true)
    }
    Details(text("erase_disk", drive.device))
}

@Composable
private fun Failed(
    heading: String,
    advice: String,
    details: String,
    setup: Setup,
) {
    Heading(heading)
    Body(advice)
    Actions { Action(text("action_try_again"), setup::lookForCards, primary = true) }
    Details(details)
}

@Composable
private fun Done(
    code: ClaimCode,
    cable: Boolean,
    setup: Setup,
) {
    Heading(text("done_heading"))
    Row(
        Modifier.fillMaxWidth().hudFrame().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(code.qrPayload, Modifier.size(200.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(text("done_name"))
            Text(code.shortName, style = Type.name)
            Spacer(Modifier.height(12.dp))
            Label(text("done_code"))
            SelectionContainer { Text(code.grouped, style = Type.code) }
        }
    }
    Label(text("done_next"))
    Lines(nextSteps(code, cable).mapIndexed { index, step -> "%02d".format(Locale.ROOT, index + 1) to step }, labelWidth = 56.dp)
    Secondary(text("done_first_start"))
    Actions { Action(text("action_prepare_another"), setup::lookForCards) }
    Horizon(markSize = 24.sp, seaHeight = 56.dp, glow = false)
}

/** What the owner does after the card is written (docs/runbooks/setup-and-pair.md). */
internal fun nextSteps(
    code: ClaimCode,
    cable: Boolean,
): List<String> =
    listOf(
        text("done_open_app"),
        text("done_scan", code.shortName),
        text("done_insert"),
        if (cable) text("done_cable") else text("done_wifi"),
    )

/** Dark modules on white with a four-module quiet zone, so phones can read it on the dark screen. */
@Composable
private fun QrCode(
    payload: String,
    modifier: Modifier,
) {
    val modules = remember(payload) { qrModules(payload) }
    Canvas(modifier.semantics { contentDescription = text("done_qr") }) {
        val cell = size.minDimension / (modules.width + 8)
        drawRect(Color.White)
        for (y in 0 until modules.height) {
            for (x in 0 until modules.width) {
                if (modules[x, y]) {
                    drawRect(Color.Black, Offset((x + 4) * cell, (y + 4) * cell), Size(cell, cell))
                }
            }
        }
    }
}

@Composable
private fun Waiting(
    heading: String,
    vararg body: String,
) {
    Heading(heading)
    ProgressLine()
    for (text in body) Body(text)
}

private fun size(drive: Drive) = text("card_size", drive.sizeBytes / 1e9)

// Two-letter codes, as the Wi-Fi regulatory domain needs, with their names
// in the window's language and in its alphabetical order (Æ, Ø, Å last in Bokmål).
private val countries: Map<String, String> =
    Locale
        .getISOCountries()
        .associateWith {
            Locale
                .Builder()
                .setRegion(it)
                .build()
                .getDisplayCountry(uiLocale)
        }.entries
        .sortedWith(compareBy(Collator.getInstance(uiLocale)) { it.value })
        .associate { it.toPair() }
