package no.cyberfish.dekoi.burner

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.shared.Network
import java.util.Locale

fun main() =
    application {
        Window(onCloseRequest = ::exitApplication, title = "DeKoi Setup", state = rememberWindowState(width = 560.dp, height = 720.dp)) {
            DekoiTheme {
                val scope = rememberCoroutineScope()
                val setup = remember { Setup(scope) }
                LaunchedEffect(setup) { setup.lookForCards() }
                val screen by setup.screen.collectAsState()
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SetupScreen(screen, setup)
                    }
                }
            }
        }
    }

@Composable
private fun DekoiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DesignTokens.Dark else DesignTokens.Light
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme =
        base.copy(
            primary = Color(palette.tint.color),
            onPrimary = Color(palette.onTint),
            background = Color(palette.background),
            onBackground = Color(palette.text),
            surface = Color(palette.surface),
            onSurface = Color(palette.text),
            onSurfaceVariant = Color(palette.textSecondary),
            outline = Color(palette.textTertiary),
            outlineVariant = Color(palette.separator),
            error = Color(palette.critical.color),
        )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun ColumnScope.SetupScreen(
    screen: Screen,
    setup: Setup,
) {
    when (screen) {
        Screen.Looking -> {
            Waiting("Looking for SD cards")
        }

        Screen.NotWindows -> {
            Title("DeKoi Setup runs on Windows")
            Body("This version of DeKoi Setup can only write cards on a Windows PC.")
        }

        is Screen.NoImager -> {
            NoImager(screen.installFailed, setup)
        }

        Screen.Installing -> {
            Waiting("Installing Raspberry Pi Imager")
            Body("Windows may ask for permission. Choose Yes.")
        }

        is Screen.ChooseCard -> {
            ChooseCard(screen.drives, setup)
        }

        is Screen.ChooseNetwork -> {
            ChooseNetwork(screen.drive, setup)
        }

        is Screen.Confirm -> {
            Confirm(screen.drive, screen.network, setup)
        }

        Screen.Writing -> {
            Waiting("Writing the card")
            Body("This takes 10 to 20 minutes. Raspberry Pi Imager shows the details in its own window.")
            Body("Leave the card in until DeKoi Setup says it is ready.")
        }

        is Screen.Failed -> {
            Failed(screen.details, setup)
        }

        is Screen.Done -> {
            Done(screen.code, setup)
        }
    }
}

@Composable
private fun NoImager(
    installFailed: Boolean,
    setup: Setup,
) {
    Title("DeKoi Setup needs Raspberry Pi Imager")
    Body("Raspberry Pi Imager writes the card. It is free and made by Raspberry Pi.")
    if (installFailed) {
        Body("It was not installed. You can also download it from ${Imager.DOWNLOAD_PAGE} and then choose Look again.")
    }
    Button(onClick = setup::installImager) { Text("Install Raspberry Pi Imager") }
    TextButton(onClick = setup::lookForCards) { Text("Look again") }
}

@Composable
private fun ChooseCard(
    drives: List<Drive>,
    setup: Setup,
) {
    Title("Choose the SD card")
    if (drives.isEmpty()) {
        Body("No SD card found. Insert the card, then choose Look again.")
    }
    for (drive in drives) {
        Surface(
            Modifier.fillMaxWidth().clickable { setup.choose(drive) },
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(drive.label, style = MaterialTheme.typography.titleMedium)
                Secondary(size(drive))
            }
        }
    }
    OutlinedButton(onClick = setup::lookForCards) { Text("Look again") }
}

@Composable
private fun ChooseNetwork(
    drive: Drive,
    setup: Setup,
) {
    var wifi by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var country by remember { mutableStateOf(Locale.getDefault().country.takeIf { it in countries } ?: "GB") }
    var problem by remember { mutableStateOf<String?>(null) }

    Title("How will your DeKoi connect?")
    Choice("Network cable (recommended)", selected = !wifi) { wifi = false }
    Choice("Wi-Fi", selected = wifi) { wifi = true }
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
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = {
            try {
                setup.confirm(drive, if (wifi) Network.WiFi(name, password, country) else Network.Cable)
            } catch (e: IllegalArgumentException) {
                problem = e.message
            }
        }) { Text("Continue") }
        TextButton(onClick = setup::lookForCards) { Text("Back") }
    }
}

@Composable
private fun CountryPicker(
    country: String,
    onChange: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Secondary("Country (sets the Wi-Fi rules for your country)")
    OutlinedButton(onClick = { open = true }) { Text(countries.getValue(country)) }
    DropdownMenu(open, onDismissRequest = { open = false }, Modifier.heightIn(max = 360.dp)) {
        for ((code, name) in countries) {
            DropdownMenuItem(text = { Text(name) }, onClick = {
                onChange(code)
                open = false
            })
        }
    }
}

@Composable
private fun Confirm(
    drive: Drive,
    network: Network,
    setup: Setup,
) {
    Title("Erase this card?")
    Body("Everything on ${drive.label} (${size(drive)}) will be erased and replaced with DeKoi.")
    Body(
        when (network) {
            Network.Cable -> "Your DeKoi will connect with a network cable."
            is Network.WiFi -> "Your DeKoi will connect to the Wi-Fi network ${network.ssid}."
        },
    )
    Body("Windows will ask whether Raspberry Pi Imager may make changes. Choose Yes.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { setup.write(drive, network) }) { Text("Erase and write") }
        TextButton(onClick = { setup.choose(drive) }) { Text("Back") }
    }
}

@Composable
private fun Failed(
    details: String,
    setup: Setup,
) {
    var open by remember { mutableStateOf(false) }
    Title("The card was not written")
    Body("Check that the card is still in the reader, then try again. If Windows asked for permission, choose Yes next time.")
    Button(onClick = setup::lookForCards) { Text("Try again") }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide technical details" else "Show technical details") }
    if (open) {
        SelectionContainer {
            Text(details, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ColumnScope.Done(
    code: ClaimCode,
    setup: Setup,
) {
    Title("Your card is ready")
    QrCode(code.qrPayload, Modifier.align(Alignment.CenterHorizontally).size(260.dp))
    Text(code.shortName, Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.headlineSmall)
    Text(
        code.grouped,
        Modifier.align(Alignment.CenterHorizontally),
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Body("In the DeKoi app, choose Add DeKoi and scan this code. If scanning does not work, type the code shown under it.")
    Text("Insert the card and plug in your DeKoi", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = setup::lookForCards) { Text("Prepare another card") }
}

/** Dark modules on white with a four-module quiet zone, in light and dark mode alike, so phones can read it. */
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
private fun Choice(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Waiting(text: String) {
    Title(text)
    CircularProgressIndicator()
}

@Composable
private fun Title(text: String) = Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge)

@Composable
private fun Secondary(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)

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
