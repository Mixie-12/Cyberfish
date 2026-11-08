package no.cyberfish.dekoi

import androidx.annotation.StringRes
import java.time.Instant

/** Signals at or above this are drawn next to the DeKoi, at or below [WEAK_DBM] at the edge. */
private const val STRONG_DBM = -30
private const val WEAK_DBM = -90

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
 * One plain sentence on what Wi-Fi Guard is doing for [device], or why it
 * is off. A name the DeKoi has not picked up yet (it asks every few
 * minutes) reads as starting. Software with Wi-Fi Guard always reports a
 * state, so none at all means it arrives with an update, unless the DeKoi
 * has only just started or updated.
 */
@StringRes
fun wifiWatchText(
    device: Device,
    now: Instant,
): Int {
    if (device.wifiName == null) return R.string.wifi_watch_add
    if (device.wifiWatchName == null && !device.startedRecently(now)) return R.string.wifi_watch_update
    return when (device.wifiWatch) {
        WifiWatch.WATCHING -> R.string.wifi_watch_watching
        WifiWatch.LEARNING -> R.string.wifi_watch_learning
        WifiWatch.NO_COUNTRY -> R.string.wifi_watch_no_country
        WifiWatch.NO_RADIO -> R.string.wifi_watch_no_radio
        WifiWatch.UPLINK -> R.string.wifi_watch_uplink
        WifiWatch.NO_NAME, null -> R.string.wifi_watch_starting
    }
}

/**
 * The access points with the home Wi-Fi's name that Wi-Fi Guard reported on
 * [deviceId] (ADR 0019): the newest report of each, newest first.
 */
fun heardAccessPoints(
    deviceId: String,
    events: List<Event>,
): List<Event> =
    events
        .filter { it.deviceId == deviceId && it.kind in Feature.WIFI_GUARD.kinds }
        .sortedByDescending { it.occurredAt }
        .distinctBy { it.srcMac ?: it.id }

/** What Wi-Fi Guard leads with: the most serious of [heard] that still matters, the newest of those; null while none does. */
fun wifiFinding(
    heard: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
): Event? = recentIntrusions(heard, trustedMacs, now).minWithOrNull(compareBy<Event> { it.severity }.thenByDescending { it.occurredAt })

/** How far from the DeKoi an access point heard at [dbm] is drawn: 0 beside it, 1 at the edge, where one without a signal goes too. */
fun signalReach(dbm: Int?): Float = dbm?.let { ((STRONG_DBM - it).toFloat() / (STRONG_DBM - WEAK_DBM)).coerceIn(0f, 1f) } ?: 1f
