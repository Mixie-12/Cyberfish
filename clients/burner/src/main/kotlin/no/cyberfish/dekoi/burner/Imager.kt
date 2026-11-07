package no.cyberfish.dekoi.burner

import java.nio.file.Path

/** Raspberry Pi Imager's command line, the only thing that writes cards (ADR 0008). */
object Imager {
    /**
     * Imager's arguments. `--enable-writing-system-drives` is never passed,
     * so Imager also refuses any destination it does not list as removable.
     */
    fun arguments(
        image: Image,
        userData: Path,
        networkConfig: Path,
        log: Path,
        drive: Drive,
    ): List<String> =
        listOf(
            "--cli",
            "--debug",
            "--log-file",
            log.toString(),
            "--sha256",
            image.extractSha256,
            "--cloudinit-userdata",
            userData.toString(),
            "--cloudinit-networkconfig",
            networkConfig.toString(),
            image.url,
            drive.device,
        )

    /**
     * One Windows command line from [arguments]. Every argument is quoted;
     * Windows paths cannot contain a double quote, and a trailing backslash
     * would escape the closing quote, so both are refused.
     */
    fun windowsCommandLine(arguments: List<String>): String =
        arguments.joinToString(" ") {
            require('"' !in it && !it.endsWith("\\")) { "Cannot pass to Imager: $it" }
            "\"$it\""
        }
}
