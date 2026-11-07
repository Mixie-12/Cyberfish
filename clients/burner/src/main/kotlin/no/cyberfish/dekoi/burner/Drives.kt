package no.cyberfish.dekoi.burner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A card DeKoi Setup may offer to erase. [device] is what Imager takes as its destination. */
data class Drive(
    val device: String,
    val label: String,
    val sizeBytes: Long,
)

/** Removable cards on Windows, from Get-Disk (ADR 0007). */
object WindowsDrives {
    // Every disk as JSON; the filtering is in parse() so that it is tested.
    // @() keeps a single disk an array in Windows PowerShell 5.1.
    val command =
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            "ConvertTo-Json -Compress -InputObject @(Get-Disk | " +
                "Select-Object Number, FriendlyName, Size, BusType, IsSystem, IsBoot, IsOffline, IsReadOnly)",
        )

    private val cardBuses = setOf("USB", "SD", "MMC")

    /** Only card-reader and USB disks that hold no running system and can be written. */
    fun parse(json: String): List<Drive> =
        Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }.mapNotNull { disk ->
            fun flag(name: String) = disk.getValue(name).jsonPrimitive.boolean
            val size = disk.getValue("Size").jsonPrimitive.long
            val bus = disk.getValue("BusType").jsonPrimitive.content
            val offered =
                bus in cardBuses && size > 0 &&
                    !flag("IsSystem") && !flag("IsBoot") && !flag("IsOffline") && !flag("IsReadOnly")
            if (!offered) return@mapNotNull null
            Drive(
                device = "\\\\.\\PhysicalDrive" + disk.getValue("Number").jsonPrimitive.int,
                label = disk.getValue("FriendlyName").jsonPrimitive.content,
                sizeBytes = size,
            )
        }
}
