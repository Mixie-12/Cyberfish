package no.cyberfish.dekoi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.scanQrCode
import no.cyberfish.dekoi.shared.ClaimCode

@Composable
fun AddDeviceScreen(
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

    Screen(title = stringResource(R.string.add_title), onBack = onBack) {
        item { SecondaryText(stringResource(R.string.add_body)) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        try {
                            scanQrCode(context)?.let(::submit)
                        } catch (_: ApiException) {
                            result = resources.getString(R.string.error_generic)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.add_scan), style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.take(ClaimCode.LENGTH + 12) },
                        label = { Text(stringResource(R.string.add_manual_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    AccentButton(R.string.add_manual_button) { submit(typed) }
                }
            }
        }
        result?.let { item { Card { Text(it, style = MaterialTheme.typography.bodyLarge) } } }
    }
}

@Composable
fun SettingsScreen(
    email: String?,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Screen(title = stringResource(R.string.tab_settings)) {
        item {
            Card {
                Column {
                    KeyValue(R.string.settings_account, email.orEmpty())
                    AccentButton(R.string.settings_sign_out, onSignOut)
                }
            }
        }
        item {
            Card {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_privacy_title), style = MaterialTheme.typography.titleMedium)
                    SecondaryText(stringResource(R.string.settings_privacy_body))
                }
            }
        }
        item { AccentButton(R.string.settings_delete) { confirming = true } }
        item { SecondaryText(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)) }
    }
    if (confirming) {
        ConfirmDialog(
            title = R.string.settings_delete_confirm_title,
            body = R.string.settings_delete_confirm_body,
            confirm = R.string.action_delete,
            onConfirm = {
                confirming = false
                onDeleteAccount()
            },
            onDismiss = { confirming = false },
        )
    }
}
