package no.cyberfish.dekoi

import androidx.annotation.StringRes

/**
 * What the owner should do about an event, as numbered steps (docs/design.md,
 * "Alerts"). The steps depend on what kind of problem it is and on what the
 * source device looks like, so "disconnect it" becomes "unplug it from power"
 * for a TV and "turn off Wi-Fi" for a phone. All wording lives in
 * strings_guidance.xml.
 *
 * No step links to the router's web page: it is cleartext, and during ARP
 * spoofing opening it would hand the router password to the attacker.
 */
enum class Response {
    INTRUSION,
    IMPERSONATION,
    NEWCOMER,
    EXPOSURE,
    WRONG_ADDRESSES,
    WIFI_COPY,
    NEW_ACCESS_POINT,
    OFFLINE,
    NONE,
}

fun responseFor(kind: String): Response =
    when (kind) {
        "arp_spoof", "rogue_dhcp", "rogue_router" -> Response.IMPERSONATION
        "new_host" -> Response.NEWCOMER
        "port_forward" -> Response.EXPOSURE
        "dns_hijack" -> Response.WRONG_ADDRESSES
        "evil_twin" -> Response.WIFI_COPY
        "new_access_point" -> Response.NEW_ACCESS_POINT
        "device_offline" -> Response.OFFLINE
        "device_online" -> Response.NONE
        else -> Response.INTRUSION
    }

/** One numbered step. A step that names the source's hardware address offers to copy it. */
data class Step(
    @param:StringRes val text: Int,
    val usesHardwareAddress: Boolean = false,
)

/** How to take a device of this kind off the network, one action per step (the table in docs/design.md, "Alerts"). */
fun disconnectSteps(kind: HostKind): List<Step> =
    when (kind) {
        HostKind.PHONE, HostKind.TABLET, HostKind.WEARABLE -> {
            listOf(Step(R.string.step_ask_owner), Step(R.string.step_disconnect_personal))
        }

        HostKind.COMPUTER -> {
            listOf(Step(R.string.step_disconnect_computer))
        }

        HostKind.TV, HostKind.MEDIA_PLAYER, HostKind.SPEAKER, HostKind.GAME_CONSOLE, HostKind.PRINTER -> {
            listOf(Step(R.string.step_disconnect_power))
        }

        HostKind.CAMERA, HostKind.SMART_HOME -> {
            listOf(Step(R.string.step_disconnect_smart_home), Step(R.string.step_reset_smart_home))
        }

        HostKind.STORAGE, HostKind.SERVER -> {
            listOf(Step(R.string.step_shut_down_storage), Step(R.string.step_disconnect_storage))
        }

        HostKind.ROUTER, HostKind.ACCESS_POINT, HostKind.SWITCH -> {
            listOf(Step(R.string.step_disconnect_network_gear))
        }

        HostKind.UNKNOWN -> {
            listOf(Step(R.string.step_disconnect_unknown, usesHardwareAddress = true))
        }
    }

/** Where to look for a device of this kind. */
fun findStep(kind: HostKind): Step =
    when (kind) {
        HostKind.PHONE, HostKind.TABLET, HostKind.WEARABLE -> Step(R.string.step_find_personal)
        HostKind.COMPUTER -> Step(R.string.step_find_computer)
        HostKind.TV, HostKind.MEDIA_PLAYER, HostKind.SPEAKER, HostKind.GAME_CONSOLE -> Step(R.string.step_find_entertainment)
        HostKind.PRINTER, HostKind.STORAGE, HostKind.SERVER -> Step(R.string.step_find_office)
        HostKind.CAMERA, HostKind.SMART_HOME -> Step(R.string.step_find_smart_home)
        HostKind.ROUTER, HostKind.SWITCH, HostKind.ACCESS_POINT -> Step(R.string.step_find_network_gear)
        HostKind.UNKNOWN -> Step(R.string.step_find_unknown, usesHardwareAddress = true)
    }

/**
 * The steps for an event whose source looks like [source]: find it,
 * disconnect it, then secure the network. "Mark as known" is not a step;
 * the alert offers it after them.
 */
fun steps(
    kind: String,
    source: HostKind,
): List<Step> =
    when (responseFor(kind)) {
        Response.INTRUSION -> {
            listOf(findStep(source)) + disconnectSteps(source) +
                listOf(Step(R.string.step_change_wifi_password), Step(R.string.step_change_account_passwords))
        }

        Response.IMPERSONATION -> {
            listOf(findStep(source)) + disconnectSteps(source) +
                listOf(Step(R.string.step_restart_router), Step(R.string.step_change_wifi_password))
        }

        // Most new devices belong to the household or a guest, so knowing it comes first.
        Response.NEWCOMER -> {
            listOf(findStep(source), Step(R.string.step_mark_known_newcomer)) + disconnectSteps(source) +
                Step(R.string.step_change_wifi_password_newcomer)
        }

        Response.EXPOSURE -> {
            listOf(findStep(source), Step(R.string.step_turn_off_remote_access), Step(R.string.step_turn_off_upnp))
        }

        Response.WRONG_ADDRESSES -> {
            listOf(Step(R.string.step_use_mobile_data), Step(R.string.step_restart_router), Step(R.string.step_ask_provider))
        }

        Response.WIFI_COPY -> {
            listOf(Step(R.string.step_avoid_copy), Step(R.string.step_check_devices_network), Step(R.string.step_rejoin_network))
        }

        Response.NEW_ACCESS_POINT -> {
            listOf(
                Step(R.string.step_new_access_point_yours),
                Step(R.string.step_check_devices_network),
                Step(R.string.step_change_wifi_password),
            )
        }

        Response.OFFLINE -> {
            listOf(Step(R.string.step_check_power), Step(R.string.step_check_cable), Step(R.string.step_wait_reconnect))
        }

        Response.NONE -> {
            emptyList()
        }
    }

// Marking one of these sources as known silences an attack on every
// device's traffic, and a second router or extender is easy to mistake for
// a familiar device, so the app asks first.
private val CONFIRM_TRUST = setOf("arp_spoof", "rogue_dhcp", "rogue_router", "name_poisoning")

/** Whether "Mark as known" asks for confirmation for an event of this kind. */
fun trustNeedsConfirmation(kind: String): Boolean = kind in CONFIRM_TRUST

enum class ThreatLevel { LIKELY, POSSIBLE, UNLIKELY }

/** The answer to "Is this a real threat?": how likely, and one plain sentence on why. */
data class Threat(
    val level: ThreatLevel,
    @param:StringRes val reason: Int,
)

// A device that impersonates another or answers for a name. rogue_router
// is not here: Thread border routers send router advertisements legitimately.
private val IMPERSONATION_KINDS = setOf("arp_spoof", "rogue_dhcp", "name_poisoning")

/**
 * How likely an incident with events of these [kinds] is a real attack. A
 * sign-in attempt, an opened bait file, an impersonation, wrong answers for
 * websites or a copy of the home Wi-Fi is one (file_access, dns_hijack and
 * evil_twin: ADRs 0018, 0015, 0019); a scan, a decoy connection or a kind
 * this app does not know yet may be an app or a network tool; anything
 * from a device the owner marked as known, a device that only joined the
 * network, and DeKoi's own status, is probably harmless.
 */
fun threat(
    kinds: Set<String>,
    knownSource: Boolean,
): Threat =
    when {
        knownSource -> Threat(ThreatLevel.UNLIKELY, R.string.threat_known)
        kinds == setOf("new_host") -> Threat(ThreatLevel.UNLIKELY, R.string.threat_new_device)
        "login_attempt" in kinds -> Threat(ThreatLevel.LIKELY, R.string.threat_sign_in)
        "file_access" in kinds -> Threat(ThreatLevel.LIKELY, R.string.threat_file)
        kinds.any { it in IMPERSONATION_KINDS } -> Threat(ThreatLevel.LIKELY, R.string.threat_impersonation)
        "dns_hijack" in kinds -> Threat(ThreatLevel.LIKELY, R.string.threat_dns)
        "evil_twin" in kinds -> Threat(ThreatLevel.LIKELY, R.string.threat_wifi_copy)
        "new_access_point" in kinds -> Threat(ThreatLevel.POSSIBLE, R.string.threat_new_access_point)
        "port_forward" in kinds -> Threat(ThreatLevel.POSSIBLE, R.string.threat_port_forward)
        DEKOI_STATUS_KINDS.containsAll(kinds) -> Threat(ThreatLevel.UNLIKELY, R.string.threat_status)
        else -> Threat(ThreatLevel.POSSIBLE, R.string.threat_probing)
    }

@StringRes
fun threatLabel(level: ThreatLevel): Int =
    when (level) {
        ThreatLevel.LIKELY -> R.string.threat_likely
        ThreatLevel.POSSIBLE -> R.string.threat_possible
        ThreatLevel.UNLIKELY -> R.string.threat_unlikely
    }

/** One short line on how serious it is, for the top of an alert. */
@StringRes
fun severityLabel(severity: Severity): Int =
    when (severity) {
        Severity.CRITICAL -> R.string.severity_critical
        Severity.WARNING -> R.string.severity_warning
        Severity.INFO -> R.string.severity_info
    }

/** The device kind in plain words, for "looks like a TV". */
@StringRes
fun kindName(kind: HostKind): Int =
    when (kind) {
        HostKind.ROUTER -> R.string.kind_router
        HostKind.SWITCH -> R.string.kind_switch
        HostKind.ACCESS_POINT -> R.string.kind_access_point
        HostKind.COMPUTER -> R.string.kind_computer
        HostKind.PHONE -> R.string.kind_phone
        HostKind.TABLET -> R.string.kind_tablet
        HostKind.TV -> R.string.kind_tv
        HostKind.MEDIA_PLAYER -> R.string.kind_media_player
        HostKind.SPEAKER -> R.string.kind_speaker
        HostKind.GAME_CONSOLE -> R.string.kind_game_console
        HostKind.PRINTER -> R.string.kind_printer
        HostKind.CAMERA -> R.string.kind_camera
        HostKind.STORAGE -> R.string.kind_storage
        HostKind.SMART_HOME -> R.string.kind_smart_home
        HostKind.WEARABLE -> R.string.kind_wearable
        HostKind.SERVER -> R.string.kind_server
        HostKind.UNKNOWN -> R.string.kind_unknown
    }

/** What a host appears to be, as in "Looks like a TV": DeKoi infers the kind, so it never says "is". */
@StringRes
fun looksLike(kind: HostKind): Int =
    when (kind) {
        HostKind.ROUTER -> R.string.looks_like_router
        HostKind.SWITCH -> R.string.looks_like_switch
        HostKind.ACCESS_POINT -> R.string.looks_like_access_point
        HostKind.COMPUTER -> R.string.looks_like_computer
        HostKind.PHONE -> R.string.looks_like_phone
        HostKind.TABLET -> R.string.looks_like_tablet
        HostKind.TV -> R.string.looks_like_tv
        HostKind.MEDIA_PLAYER -> R.string.looks_like_media_player
        HostKind.SPEAKER -> R.string.looks_like_speaker
        HostKind.GAME_CONSOLE -> R.string.looks_like_game_console
        HostKind.PRINTER -> R.string.looks_like_printer
        HostKind.CAMERA -> R.string.looks_like_camera
        HostKind.STORAGE -> R.string.looks_like_storage
        HostKind.SMART_HOME -> R.string.looks_like_smart_home
        HostKind.WEARABLE -> R.string.looks_like_wearable
        HostKind.SERVER -> R.string.looks_like_server
        HostKind.UNKNOWN -> R.string.looks_like_unknown
    }

/**
 * A device of this kind as the subject of an alert title, "A phone". The
 * same words as SUBJECTS in backend/supabase/functions/notify/notify.ts, so
 * the alert reads like the notification that led to it (ADR 0011).
 */
@StringRes
fun subject(kind: HostKind): Int =
    when (kind) {
        HostKind.ROUTER -> R.string.subject_router
        HostKind.SWITCH -> R.string.subject_switch
        HostKind.ACCESS_POINT -> R.string.subject_access_point
        HostKind.COMPUTER -> R.string.subject_computer
        HostKind.PHONE -> R.string.subject_phone
        HostKind.TABLET -> R.string.subject_tablet
        HostKind.TV -> R.string.subject_tv
        HostKind.MEDIA_PLAYER -> R.string.subject_media_player
        HostKind.SPEAKER -> R.string.subject_speaker
        HostKind.GAME_CONSOLE -> R.string.subject_game_console
        HostKind.PRINTER -> R.string.subject_printer
        HostKind.CAMERA -> R.string.subject_camera
        HostKind.STORAGE -> R.string.subject_storage
        HostKind.SMART_HOME -> R.string.subject_smart_home
        HostKind.WEARABLE -> R.string.subject_wearable
        HostKind.SERVER -> R.string.subject_server
        HostKind.UNKNOWN -> R.string.subject_unknown
    }
