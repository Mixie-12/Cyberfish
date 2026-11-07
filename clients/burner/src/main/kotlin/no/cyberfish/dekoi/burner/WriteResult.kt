package no.cyberfish.dekoi.burner

import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import no.cyberfish.dekoi.shared.Network
import java.nio.file.Files
import java.nio.file.Path

sealed interface WriteResult {
    data class Written(
        val code: ClaimCode,
    ) : WriteResult

    data class Failed(
        val exitCode: Int,
        val log: String,
    ) : WriteResult
}

/**
 * Writes one card through Imager: renders the cloud-init files for [code]
 * into a fresh temporary directory, starts Imager elevated and waits. The
 * directory holds the claim code and the Wi-Fi key, so it is deleted
 * afterwards whatever happens.
 */
fun writeCard(
    executable: Path,
    drive: Drive,
    network: Network,
    code: ClaimCode,
    run: (ProcessBuilder) -> Int = { it.start().waitFor() },
): WriteResult {
    val dir = Files.createTempDirectory("dekoi-setup")
    try {
        val userData = Files.writeString(dir.resolve("user-data"), CloudInit.userData(Device.userDataTemplate, Device::read, code))
        val networkConfig = Files.writeString(dir.resolve("network-config"), CloudInit.networkConfig(network))
        val log = dir.resolve("imager.log")
        val exitCode = run(Imager.elevatedStart(executable, Imager.arguments(Device.image, userData, networkConfig, log, drive)))
        if (exitCode == 0) return WriteResult.Written(code)
        return WriteResult.Failed(exitCode, if (Files.exists(log)) tail(Files.readAllBytes(log)) else "")
    } finally {
        dir.toFile().deleteRecursively()
    }
}

// Imager's debug log is long; the end holds the error.
private fun tail(log: ByteArray): String = log.takeLast(LOG_TAIL_BYTES).toByteArray().toString(Charsets.UTF_8)

private const val LOG_TAIL_BYTES = 32 * 1024
