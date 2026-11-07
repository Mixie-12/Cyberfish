package no.cyberfish.dekoi

import androidx.annotation.StringRes

/** The home network's name the owner gives for the Wi-Fi watch (ADR 0019), as the backend accepts it. */
object WifiName {
    /** An SSID is at most 32 bytes. */
    const val MAX_BYTES = 32

    /** What to store for what the owner typed: trimmed, or null to turn the watch off. */
    fun of(input: String): String? = input.trim().takeIf { it.isNotEmpty() }

    fun fits(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && text.none { it.isISOControl() }
}

/** The state of a DeKoi's Wi-Fi watch as it last reported it (devices.wifi_watch). */
enum class WifiWatch {
    NO_NAME,
    NO_COUNTRY,
    NO_RADIO,
    UPLINK,
    LEARNING,
    WATCHING,
    ;

    companion object {
        /** Null for a DeKoi that has not reported yet, or a state from a newer backend. */
        fun of(name: String?): WifiWatch? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

val Device.wifiWatch: WifiWatch? get() = WifiWatch.of(wifiWatchName)

/**
 * One plain sentence on what the watch is doing for [device], or why it is
 * off. A name the DeKoi has not picked up yet (it asks every few minutes)
 * reads as starting.
 */
@StringRes
fun wifiWatchText(device: Device): Int {
    if (device.wifiName == null) return R.string.wifi_watch_add
    return when (device.wifiWatch) {
        WifiWatch.WATCHING -> R.string.wifi_watch_watching
        WifiWatch.LEARNING -> R.string.wifi_watch_learning
        WifiWatch.NO_COUNTRY -> R.string.wifi_watch_no_country
        WifiWatch.NO_RADIO -> R.string.wifi_watch_no_radio
        WifiWatch.UPLINK -> R.string.wifi_watch_uplink
        WifiWatch.NO_NAME, null -> R.string.wifi_watch_starting
    }
}
