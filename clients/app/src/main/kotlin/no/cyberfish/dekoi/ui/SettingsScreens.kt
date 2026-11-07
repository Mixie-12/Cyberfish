package no.cyberfish.dekoi.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.Screen

@Composable
fun SettingsScreen(
    email: String?,
    devices: List<Device>,
    listState: LazyListState,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onNavigate: (Route) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    Screen(stringResource(R.string.tab_settings), listState = listState) {
        item {
            InsetSection(title = stringResource(R.string.settings_account_title)) {
                InsetRow(email.orEmpty(), subtitle = stringResource(R.string.settings_account))
                InsetRow(
                    stringResource(R.string.settings_sign_out),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = onSignOut,
                    chevron = false,
                )
            }
        }
        item {
            InsetSection(title = stringResource(R.string.settings_devices)) {
                devices.forEach { device ->
                    InsetRow(device.name, leading = { BrandTile() }, onClick = { onNavigate(Route.Dekoi(device.id)) })
                }
                InsetRow(
                    stringResource(R.string.action_add_dekoi),
                    titleColor = MaterialTheme.colorScheme.primary,
                    leading = {
                        Icon(
                            painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onNavigate(Route.AddDekoi) },
                )
            }
        }
        item { Notifications() }
        item {
            InsetSection(title = stringResource(R.string.settings_privacy_title)) {
                Text(
                    stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(Dimens.spaceL),
                )
            }
        }
        item {
            InsetSection(title = stringResource(R.string.settings_about)) {
                InsetRow(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            }
        }
        item {
            InsetSection {
                InsetRow(
                    stringResource(R.string.settings_delete),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirming = true },
                    chevron = false,
                )
            }
        }
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

/** Whether this phone shows DeKoi's notifications; the row opens the system settings, where the owner changes it. */
@Composable
private fun Notifications() {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    var enabled by remember { mutableStateOf(manager.areNotificationsEnabled()) }
    LifecycleResumeEffect(manager) {
        enabled = manager.areNotificationsEnabled()
        onPauseOrDispose { }
    }
    InsetSection(title = stringResource(R.string.settings_notifications)) {
        InsetRow(
            stringResource(R.string.settings_notifications_row),
            subtitle = stringResource(if (enabled) R.string.settings_notifications_on else R.string.settings_notifications_off),
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                )
            },
        )
    }
}
