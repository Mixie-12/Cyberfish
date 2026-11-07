package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.scanQrCode
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.Screen

@Composable
fun AddDekoiScreen(
    onClaim: (ClaimCode, (Int) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.add_invalid)
    val resources = LocalResources.current

    fun submit(raw: String) {
        val code = ClaimCode.parse(raw)
        if (code == null) {
            result = invalid
            return
        }
        onClaim(code) { message ->
            result = if (message == R.string.add_done) resources.getString(message, code.shortName) else resources.getString(message)
        }
    }

    Screen(stringResource(R.string.add_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.add_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            scanQrCode(context)?.let(::submit)
                        } catch (_: MlKitException) {
                            result = resources.getString(R.string.add_scan_failed)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(
                    painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(ButtonDefaults.IconSize),
                )
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.add_scan))
            }
        }
        item {
            InsetSection {
                Column(Modifier.padding(Dimens.spaceL), verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.take(ClaimCode.LENGTH + 12) },
                        label = { Text(stringResource(R.string.add_manual_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { submit(typed) }) { Text(stringResource(R.string.add_manual_button)) }
                }
            }
        }
        result?.let {
            item {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
