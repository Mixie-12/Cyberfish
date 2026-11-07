package no.cyberfish.dekoi.burner

import java.nio.file.Files
import java.nio.file.Path

/** Raspberry Pi Imager's command line, the only thing that writes cards (ADR 0008). */
object Imager {
    const val DOWNLOAD_PAGE = "https://www.raspberrypi.com/software/"

    /** Installs Imager from winget's own source; Windows asks the user to confirm. */
    val windowsInstall =
        listOf(
            "winget",
            "install",
            "--exact",
            "--id",
            "RaspberryPiFoundation.RaspberryPiImager",
            "--source",
            "winget",
            "--accept-package-agreements",
            "--accept-source-agreements",
        )

    /** Where the official installer and winget put Imager, if it is there. */
    fun windowsExecutable(programFiles: String? = System.getenv("ProgramFiles")): Path? {
        if (programFiles.isNullOrEmpty()) return null
        return Path.of(programFiles, "Raspberry Pi Ltd", "Imager", "rpi-imager.exe").takeIf(Files::isRegularFile)
    }

    // A constant script: the executable and its arguments arrive in environment
    // variables and are never parsed as PowerShell. RunAs shows the UAC prompt
    // that Imager's requireAdministrator manifest needs; -PassThru returns the
    // exit code, the only result an elevated Imager can hand back.
    private const val ELEVATED_START =
        "\$p = Start-Process -FilePath \$env:DEKOI_IMAGER -ArgumentList \$env:DEKOI_IMAGER_ARGS " +
            "-Verb RunAs -Wait -PassThru; exit \$p.ExitCode"

    /** The process that starts Imager elevated and exits with Imager's exit code. */
    fun elevatedStart(
        executable: Path,
        arguments: List<String>,
    ): ProcessBuilder {
        val builder = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ELEVATED_START)
        builder.environment()["DEKOI_IMAGER"] = executable.toString()
        builder.environment()["DEKOI_IMAGER_ARGS"] = windowsCommandLine(arguments)
        return builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD)
    }

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
