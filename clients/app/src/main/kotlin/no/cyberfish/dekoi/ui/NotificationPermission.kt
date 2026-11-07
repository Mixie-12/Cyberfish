package no.cyberfish.dekoi.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shouldAskForNotifications

private const val PREFERENCES = "notifications"
private const val ASKED = "asked"

/** Explains alerts once the first DeKoi is linked, then asks Android 13+ for the permission. */
@Composable
fun NotificationPermission(hasDevice: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var asked by remember { mutableStateOf(preferences.getBoolean(ASKED, false)) }
    val granted =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val markAsked = {
        preferences.edit { putBoolean(ASKED, true) }
        asked = true
    }

    if (!shouldAskForNotifications(Build.VERSION.SDK_INT, granted, asked, hasDevice)) return
    AlertDialog(
        onDismissRequest = markAsked,
        title = { Text(stringResource(R.string.notifications_ask_title)) },
        text = { Text(stringResource(R.string.notifications_ask_body)) },
        confirmButton = {
            TextButton(onClick = {
                markAsked()
                request.launch(Manifest.permission.POST_NOTIFICATIONS)
            }) { Text(stringResource(R.string.notifications_ask_allow)) }
        },
        dismissButton = {
            TextButton(onClick = markAsked) { Text(stringResource(R.string.notifications_ask_later)) }
        },
    )
}
