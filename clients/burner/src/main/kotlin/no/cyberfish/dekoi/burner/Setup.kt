package no.cyberfish.dekoi.burner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.cyberfish.dekoi.shared.ClaimCode

sealed interface Screen {
    data object Looking : Screen

    data object NotWindows : Screen

    data class NoImager(
        val installFailed: Boolean,
    ) : Screen

    data object Installing : Screen

    data class ChooseCard(
        val drives: List<Drive>,
    ) : Screen

    data class ChooseNetwork(
        val drive: Drive,
    ) : Screen

    data class Confirm(
        val drive: Drive,
        val network: Network,
    ) : Screen

    data object Writing : Screen

    data class Failed(
        val details: String,
    ) : Screen

    data class Done(
        val code: ClaimCode,
    ) : Screen
}

/** The steps of preparing one card, as a state the window shows. */
class Setup(
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Screen>(Screen.Looking)
    val screen: StateFlow<Screen> = state.asStateFlow()

    fun lookForCards() {
        state.value = Screen.Looking
        scope.launch { state.value = withContext(Dispatchers.IO) { look(installFailed = false) } }
    }

    fun installImager() {
        state.value = Screen.Installing
        scope.launch {
            state.value =
                withContext(Dispatchers.IO) {
                    ProcessBuilder(Imager.windowsInstall).inheritIO().start().waitFor()
                    look(installFailed = true)
                }
        }
    }

    fun choose(drive: Drive) {
        state.value = Screen.ChooseNetwork(drive)
    }

    fun confirm(
        drive: Drive,
        network: Network,
    ) {
        state.value = Screen.Confirm(drive, network)
    }

    /** The claim code is made here, so the card and the QR code always carry the same one. */
    fun write(
        drive: Drive,
        network: Network,
    ) {
        val executable = Imager.windowsExecutable() ?: return lookForCards()
        state.value = Screen.Writing
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        writeCard(executable, drive, network, ClaimCode.generate())
                    } catch (e: Exception) {
                        WriteResult.Failed(-1, e.toString())
                    }
                }
            state.value =
                when (result) {
                    is WriteResult.Written -> Screen.Done(result.code)
                    is WriteResult.Failed -> Screen.Failed("Raspberry Pi Imager exit code ${result.exitCode}\n\n${result.log}")
                }
        }
    }

    // Blocking: runs Get-Disk. A failure offers no cards rather than guessing.
    private fun look(installFailed: Boolean): Screen {
        if (!System.getProperty("os.name").startsWith("Windows")) return Screen.NotWindows
        if (Imager.windowsExecutable() == null) return Screen.NoImager(installFailed)
        return try {
            Screen.ChooseCard(WindowsDrives.list())
        } catch (e: Exception) {
            Screen.Failed("Listing the cards failed: $e")
        }
    }
}
