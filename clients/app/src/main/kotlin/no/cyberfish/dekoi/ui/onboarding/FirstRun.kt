package no.cyberfish.dekoi.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private const val MARKER = "onboarding_seen"

/**
 * True the first time it is asked about [marker], false ever after. The
 * marker is an empty file; creating it is atomic, so two callers cannot
 * both get true.
 */
internal fun firstTime(marker: File): Boolean =
    try {
        marker.createNewFile()
    } catch (_: IOException) {
        // Storage that cannot hold an empty file: skip the tour rather than show it on every start.
        false
    }

/** Calls [open] once per installation, the first time the owner is signed in. */
@Composable
fun OnboardingOnce(open: () -> Unit) {
    val files = LocalContext.current.filesDir
    LaunchedEffect(files) {
        if (withContext(Dispatchers.IO) { firstTime(File(files, MARKER)) }) open()
    }
}
