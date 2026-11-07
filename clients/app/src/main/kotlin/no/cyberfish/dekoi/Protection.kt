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

// Everything a device reports is suspicious (private.device_event_kinds()),
// and so are the findings of its network checks, dns_hijack and
// port_forward (ADR 0015). device_online, device_offline and new_host come
// from the backend itself.
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
        "file_access",
        "dns_hijack",
        "port_forward",
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

/** Alerts to look at, newest first: unseen critical and warning incidents not from a device the owner knows (ADR 0013). */
fun alertsToLookAt(
    events: List<Event>,
    trustedMacs: Set<String>,
): List<Incident> = incidents(events).filter { !it.seen && it.severity != Severity.INFO && it.mac !in trustedMacs }

/** The Alerts tab badge. */
fun alertBadgeCount(
    events: List<Event>,
    trustedMacs: Set<String>,
): Int = alertsToLookAt(events, trustedMacs).size

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
