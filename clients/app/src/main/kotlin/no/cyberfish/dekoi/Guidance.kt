package no.cyberfish.dekoi

import androidx.annotation.StringRes

/**
 * What the owner should do about an event, as numbered steps. The steps
 * depend on what kind of problem it is and on what the source device looks
 * like, so "disconnect it" becomes "unplug its power" for a TV and "turn off
 * its Wi-Fi" for a phone. All wording lives in strings_guidance.xml.
 */
enum class Response {
    INTRUSION,
    IMPERSONATION,
    OFFLINE,
    NONE,
}

fun responseFor(kind: String): Response =
    when (kind) {
        "arp_spoof", "rogue_dhcp", "rogue_router" -> Response.IMPERSONATION
        "device_offline" -> Response.OFFLINE
        "device_online" -> Response.NONE
        else -> Response.INTRUSION
    }

/** How to take a device of this kind off the network, in the owner's terms. */
@StringRes
fun disconnectStep(kind: HostKind): Int =
    when (kind) {
        HostKind.PHONE, HostKind.TABLET, HostKind.COMPUTER, HostKind.WEARABLE -> R.string.step_disconnect_wifi

        HostKind.TV, HostKind.MEDIA_PLAYER, HostKind.SPEAKER, HostKind.GAME_CONSOLE,
        HostKind.PRINTER, HostKind.CAMERA, HostKind.SMART_HOME, HostKind.STORAGE, HostKind.SERVER,
        -> R.string.step_disconnect_power

        HostKind.ROUTER, HostKind.SWITCH, HostKind.ACCESS_POINT -> R.string.step_disconnect_network_gear

        HostKind.UNKNOWN -> R.string.step_disconnect_unknown
    }

/** Where to look for a device of this kind. */
@StringRes
fun findStep(kind: HostKind): Int =
    when (kind) {
        HostKind.PHONE, HostKind.TABLET, HostKind.WEARABLE -> R.string.step_find_personal
        HostKind.COMPUTER -> R.string.step_find_computer
        HostKind.TV, HostKind.MEDIA_PLAYER, HostKind.SPEAKER, HostKind.GAME_CONSOLE -> R.string.step_find_entertainment
        HostKind.PRINTER, HostKind.STORAGE, HostKind.SERVER -> R.string.step_find_office
        HostKind.CAMERA, HostKind.SMART_HOME -> R.string.step_find_smart_home
        HostKind.ROUTER, HostKind.SWITCH, HostKind.ACCESS_POINT -> R.string.step_find_network_gear
        HostKind.UNKNOWN -> R.string.step_find_unknown
    }

/**
 * The steps for an event whose source looks like [source]. A device the owner
 * already marked as known only needs the last step, in case it changed.
 */
fun steps(
    kind: String,
    source: HostKind,
): List<Int> =
    when (responseFor(kind)) {
        Response.INTRUSION -> {
            listOf(
                findStep(source),
                disconnectStep(source),
                R.string.step_change_wifi_password,
                R.string.step_change_account_passwords,
                R.string.step_mark_known,
            )
        }

        Response.IMPERSONATION -> {
            listOf(
                findStep(source),
                disconnectStep(source),
                R.string.step_restart_router,
                R.string.step_change_wifi_password,
            )
        }

        Response.OFFLINE -> {
            listOf(R.string.step_check_power, R.string.step_check_cable, R.string.step_wait_reconnect)
        }

        Response.NONE -> {
            emptyList()
        }
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
