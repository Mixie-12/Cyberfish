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
import java.awt.Dimension
import java.util.Locale

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "DeKoi Setup", state = rememberWindowState(width = 840.dp, height = 780.dp)) {
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
            Waiting("Looking for memory cards")
        }

        Screen.NotWindows -> {
            Heading("This needs a Windows PC")
            Body("This version of DeKoi Setup can only write cards on a Windows PC.")
        }

        is Screen.NoImager -> {
            NoImager(screen.installFailed, setup)
        }

        Screen.Installing -> {
            Waiting("Installing Raspberry Pi Imager", "Windows may ask for permission. Choose Yes.")
        }

        is Screen.ChooseCard -> {
            ChooseCard(screen.drives, setup)
        }

        is Screen.ListFailed -> {
            Failed(
                "Could not look for cards",
                "DeKoi Setup could not check which memory cards are connected. Try again in a moment.",
                screen.details,
                setup,
            )
        }

        is Screen.ChooseNetwork -> {
            ChooseNetwork(screen.drive, screen.network, setup)
        }

        is Screen.Confirm -> {
            Confirm(screen.drive, screen.network, setup)
        }

        Screen.Writing -> {
            Waiting(
                "Writing the card",
                "This takes 10 to 20 minutes. Raspberry Pi Imager shows the details in its own window.",
                "Leave the card in until DeKoi Setup says it is ready.",
            )
        }

        is Screen.WriteFailed -> {
            Failed(
                "The card was not written",
                "Check that the card is still in the reader, then try again. If Windows asked for permission, choose Yes next time.",
                screen.details,
                setup,
            )
        }

        is Screen.Done -> {
            Done(screen.code, screen.cable, setup)
        }
    }
}

@Composable
private fun ColumnScope.Welcome(setup: Setup) {
    Horizon(markSize = 64.sp, seaHeight = 72.dp, glow = true)
    Text("DeKoi Setup", Modifier.align(Alignment.CenterHorizontally), style = Type.wordmark)
    Body("DeKoi Setup prepares the memory card for your DeKoi.", TextAlign.Center)
    Body("When it is done, it shows a code that links your DeKoi to the app on your phone.", TextAlign.Center)
    Action("Start", setup::lookForCards, Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp), primary = true)
}

@Composable
private fun NoImager(
    installFailed: Boolean,
    setup: Setup,
) {
    Heading("One more program is needed")
    Body("Raspberry Pi Imager writes the card. It is free and made by Raspberry Pi.")
    if (installFailed) {
        SelectionContainer {
            Body("It was not installed. You can also download it from ${Imager.DOWNLOAD_PAGE} and then choose Look again.")
        }
    }
    Actions {
        Action("Look again", setup::lookForCards)
        Action("Install Raspberry Pi Imager", setup::installImager, primary = true)
    }
}

@Composable
private fun ChooseCard(
    drives: List<Drive>,
    setup: Setup,
) {
    if (drives.isEmpty()) {
        Heading("Insert the memory card")
        Body("No memory card was found. Put the card in the card reader, then choose Look again.")
    } else {
        Heading("Which memory card?")
        Body("Only memory cards and USB drives are listed, never the disks inside this computer. The card needs 8 GB or more.")
        Column(Modifier.fillMaxWidth().hudFrame()) {
            drives.forEachIndexed { index, drive ->
                if (index > 0) Hairline()
                CardRow(drive) { setup.choose(drive) }
            }
        }
    }
    Actions { Action("Look again", setup::lookForCards) }
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
        Text("CHOOSE", style = Type.label)
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

    Heading("How will it connect?")
    Body("Your DeKoi needs the internet to reach the app on your phone.")
    Column(Modifier.selectableGroup()) {
        Choice("Network cable (recommended)", "Plug your DeKoi into your router. This is the most reliable.", !wifi) { wifi = false }
        Choice("Wi-Fi", "For when your DeKoi cannot be near the router.", wifi) { wifi = true }
    }
    if (wifi) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Wi-Fi name") }, singleLine = true)
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            label = { Text("Wi-Fi password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        CountryPicker(country) { country = it }
    }
    problem?.let {
        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = Type.body.copy(color = Brand.critical))
    }
    Actions {
        Action("Back", setup::lookForCards)
        Action("Continue", primary = true, onClick = {
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
        Label("Country")
        Secondary("Sets the Wi-Fi rules for your country.")
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
    Heading("Erase this card?")
    Body("Everything on this card will be erased and replaced with DeKoi.")
    Lines(
        listOf(
            "Card" to "${drive.label}, ${size(drive)}",
            "Network" to
                when (network) {
                    Network.Cable -> "Network cable"
                    is Network.WiFi -> "Wi-Fi \"${network.ssid}\""
                },
        ),
        labelWidth = 120.dp,
    )
    Body("Next, Windows asks whether Raspberry Pi Imager may make changes to your device. Choose Yes.")
    Actions {
        Action("Back", { setup.choose(drive, network) })
        Action("Erase and write", { setup.write(drive, network) }, primary = true)
    }
    Details("Windows disk ${drive.device}")
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
    Actions { Action("Try again", setup::lookForCards, primary = true) }
    Details(details)
}

@Composable
private fun Done(
    code: ClaimCode,
    cable: Boolean,
    setup: Setup,
) {
    Heading("Your card is ready")
    Row(
        Modifier.fillMaxWidth().hudFrame().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QrCode(code.qrPayload, Modifier.size(200.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Label("Name")
            Text(code.shortName, style = Type.name)
            Spacer(Modifier.height(12.dp))
            Label("Code")
            SelectionContainer { Text(code.grouped, style = Type.code) }
        }
    }
    Label("Next")
    Lines(nextSteps(code, cable).mapIndexed { index, text -> "%02d".format(Locale.ROOT, index + 1) to text }, labelWidth = 56.dp)
    Secondary("The first start takes up to 10 minutes. The app shows each step.")
    Actions { Action("Prepare another card", setup::lookForCards) }
    Horizon(markSize = 24.sp, seaHeight = 56.dp, glow = false)
}

/** What the owner does after the card is written (docs/runbooks/setup-and-pair.md). */
internal fun nextSteps(
    code: ClaimCode,
    cable: Boolean,
): List<String> =
    listOf(
        "Open the DeKoi app on your phone and tap Add DeKoi.",
        "Scan the code. The app then shows the same name, ${code.shortName}. If scanning does not work, type the code.",
        "Put the card in your DeKoi.",
        if (cable) {
            "Connect the network cable to your router, then the power."
        } else {
            "Connect the power. Your DeKoi joins the Wi-Fi by itself."
        },
    )

/** Dark modules on white with a four-module quiet zone, so phones can read it on the dark screen. */
@Composable
private fun QrCode(
    payload: String,
    modifier: Modifier,
) {
    val modules = remember(payload) { qrModules(payload) }
    Canvas(modifier.semantics { contentDescription = "QR code for the DeKoi app" }) {
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

private fun size(drive: Drive) = "%.1f GB".format(Locale.ROOT, drive.sizeBytes / 1e9)

// Two-letter codes, as the Wi-Fi regulatory domain needs, with their names.
private val countries: Map<String, String> =
    Locale
        .getISOCountries()
        .associateWith {
            Locale
                .Builder()
                .setRegion(it)
                .build()
                .displayCountry
        }.entries
        .sortedBy { it.value }
        .associate { it.toPair() }
