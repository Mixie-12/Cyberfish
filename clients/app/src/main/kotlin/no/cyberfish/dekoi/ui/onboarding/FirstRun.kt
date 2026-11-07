package no.cyberfish.dekoi.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Raise it when the tour changes enough that every owner should see it
 * again: each version has its own marker, so an owner who saw an older
 * one sees the new one once. Version 1 used "onboarding_seen".
 */
private const val TOUR_VERSION = 2

private const val WELCOME_CLOSED = "welcome_closed"

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

/**
 * Home's welcome, open until the owner closes it. Closing leaves an empty
 * file in app-private storage, so it stays closed on this phone. It reads
 * as closed until that file has been looked for, so a closed welcome never
 * flashes up.
 */
@Stable
class Welcome internal constructor(
    private val marker: File,
    private val scope: CoroutineScope,
) {
    var open by mutableStateOf(false)
        private set

    internal suspend fun load() {
        open = withContext(Dispatchers.IO) { !marker.exists() }
    }

    fun close() {
        open = false
        scope.launch(Dispatchers.IO) { firstTime(marker) }
    }
}

@Composable
fun rememberWelcome(): Welcome {
    val files = LocalContext.current.filesDir
    val scope = rememberCoroutineScope()
    val welcome = remember(files, scope) { Welcome(File(files, WELCOME_CLOSED), scope) }
    LaunchedEffect(welcome) { welcome.load() }
    return welcome
}
