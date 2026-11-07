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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import kotlinx.coroutines.delay
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shouldAskForNotifications

private const val PREFERENCES = "notifications"
private const val ASKED = "asked"

/** Long enough for a setup ring that has just filled (StatusRing) to finish drawing. */
private const val AFTER_RING_MS = 1000L

/**
 * Explains alerts once a DeKoi has finished its setup, a moment after its
 * setup ring completes, then asks Android 13+ for the permission.
 */
@Composable
fun NotificationPermission(setUp: Boolean) {
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

    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(setUp) {
        if (!setUp) return@LaunchedEffect
        delay(AFTER_RING_MS)
        ready = true
    }

    if (!ready || !shouldAskForNotifications(Build.VERSION.SDK_INT, granted, asked, deviceSetUp = setUp)) return
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
