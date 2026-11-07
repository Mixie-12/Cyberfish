package no.cyberfish.dekoi.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Raise it when the tour changes enough that every owner should see it
 * again: each version has its own marker, so an owner who saw an older
 * one sees the new one once. Version 1 used "onboarding_seen".
 */
private const val TOUR_VERSION = 2

/** The marker of the current tour in [dir]. */
internal fun tourMarker(dir: File): File = File(dir, "onboarding_seen_v$TOUR_VERSION")

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

/** Calls [open] once per installation and tour version, the first time the owner is signed in. */
@Composable
fun OnboardingOnce(open: () -> Unit) {
    val files = LocalContext.current.filesDir
    LaunchedEffect(files) {
        if (withContext(Dispatchers.IO) { firstTime(tourMarker(files)) }) open()
    }
}
