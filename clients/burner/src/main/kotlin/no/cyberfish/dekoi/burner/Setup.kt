package no.cyberfish.dekoi.burner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.Network

sealed interface Screen {
    data object Welcome : Screen

    data object Looking : Screen

    data object NotWindows : Screen

    data class NoImager(
        val installFailed: Boolean,
    ) : Screen

    data object Installing : Screen

    data class ChooseCard(
        val drives: List<Drive>,
    ) : Screen

    data class ListFailed(
        val details: String,
    ) : Screen

    /** [network] is what the form starts with: cable, or what was entered before going back. */
    data class ChooseNetwork(
        val drive: Drive,
        val network: Network,
    ) : Screen

    data class Confirm(
        val drive: Drive,
        val network: Network,
    ) : Screen

    data object Writing : Screen

    data class WriteFailed(
        val details: String,
    ) : Screen

    /** [cable] decides whether the next steps include the network cable; the Wi-Fi details are not kept. */
    data class Done(
        val code: ClaimCode,
        val cable: Boolean,
    ) : Screen
}

/** The steps of preparing one card, as a state the window shows. */
class Setup(
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow<Screen>(Screen.Welcome)
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

    // Cable is the recommended connection, so it is chosen until the owner picks Wi-Fi.
    fun choose(
        drive: Drive,
        network: Network = Network.Cable,
    ) {
        state.value = Screen.ChooseNetwork(drive, network)
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
                    is WriteResult.Written -> Screen.Done(result.code, cable = network == Network.Cable)
                    is WriteResult.Failed -> Screen.WriteFailed("Raspberry Pi Imager exit code ${result.exitCode}\n\n${result.log}")
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
            Screen.ListFailed("Listing the cards failed: $e")
        }
    }
}
