package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant

/** The one state the home screen always shows. */
enum class Protection { NO_DEVICE, SETTING_UP, PROTECTED, ATTENTION, OFFLINE }

/** The backend marks a device offline after 30 minutes; its job runs every 5. */
val OFFLINE_AFTER: Duration = Duration.ofMinutes(35)

/** Activity on a decoy keeps the home screen at "attention" this long. */
val ATTENTION_WINDOW: Duration = Duration.ofHours(24)

/** A new device that has not come online by then gets troubleshooting help. */
val SETUP_HELP_AFTER: Duration = Duration.ofMinutes(20)

// Everything a device reports is suspicious; only device_online and
// device_offline come from the backend itself (private.device_event_kinds()).
private val INTRUSION_KINDS =
    setOf(
        "login_attempt",
        "connection",
        "port_scan",
        "host_scan",
        "arp_spoof",
        "rogue_dhcp",
        "rogue_router",
        "name_poisoning",
        "ids_alert",
    )

val Event.isIntrusion: Boolean get() = kind in INTRUSION_KINDS

val Device.isSettingUp: Boolean get() = registeredAt == null || lastSeenAt == null

fun Device.isOnline(now: Instant): Boolean {
    val seen = lastSeenAt ?: return false
    return online && Duration.between(seen, now) < OFFLINE_AFTER
}

fun Device.needsSetupHelp(now: Instant): Boolean = isSettingUp && Duration.between(createdAt, now) > SETUP_HELP_AFTER

/** Intrusions that still matter: recent, and not from a device the owner marked as known. */
fun recentIntrusions(
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
): List<Event> = events.filter { it.isIntrusion && it.srcMac !in trustedMacs && Duration.between(it.occurredAt, now) < ATTENTION_WINDOW }

/** The Alerts tab badge: unseen critical and warning incidents not from a device the owner knows (ADR 0013). */
fun alertBadgeCount(
    events: List<Event>,
    trustedMacs: Set<String>,
): Int = incidents(events).count { !it.seen && it.severity != Severity.INFO && it.mac !in trustedMacs }

/** The most serious of these events (Severity lists the most serious first), or null when there are none. */
fun highestSeverity(events: List<Event>): Severity? = events.minOfOrNull { it.severity }

fun protection(
    devices: List<Device>,
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
): Protection =
    when {
        devices.isEmpty() -> Protection.NO_DEVICE
        recentIntrusions(events, trustedMacs, now).isNotEmpty() -> Protection.ATTENTION
        devices.any { !it.isSettingUp && !it.isOnline(now) } -> Protection.OFFLINE
        devices.any { it.isSettingUp } -> Protection.SETTING_UP
        else -> Protection.PROTECTED
    }
