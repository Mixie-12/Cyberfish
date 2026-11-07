package no.cyberfish.dekoi

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * What an event means, in words a non-technical owner understands. The one
 * place that turns event kinds, decoy services and ports into text; all
 * wording lives in string resources so it can be translated. Nothing here
 * takes text from the device: only kinds, services, ports and counts.
 */
data class EventText(
    /** With [aboutSource], %1$s is the source as a [subject]: "A phone tried to sign in to your DeKoi", as notify.ts says it. */
    @param:StringRes val title: Int,
    /** What it did without a subject, for the list of an incident's events: "Tried to sign in". */
    @param:StringRes val action: Int,
    /** Why it matters, in one sentence under the title. */
    @param:StringRes val consequence: Int,
    /** What it means, for "What happened". */
    @param:StringRes val explanation: Int,
    val aboutSource: Boolean = true,
)

@StringRes
fun serviceName(service: String?): Int =
    when (service) {
        "ftp" -> R.string.service_ftp
        "http" -> R.string.service_http
        "ssh" -> R.string.service_ssh
        "telnet" -> R.string.service_telnet
        "mysql", "redis" -> R.string.service_database
        "rdp" -> R.string.service_rdp
        "vnc" -> R.string.service_vnc
        "smb" -> R.string.service_file_share
        else -> R.string.service_other
    }

/** What usually answers on a well-known port, in plain words; null for the rest. */
@StringRes
fun portName(port: Int): Int? =
    when (port) {
        21 -> R.string.service_ftp
        22 -> R.string.service_ssh
        23 -> R.string.service_telnet
        80, 443, 8080, 8443 -> R.string.port_web
        139, 445 -> R.string.port_file_sharing
        1433, 3306, 5432, 6379, 27017 -> R.string.service_database
        3389 -> R.string.service_rdp
        5900 -> R.string.service_vnc
        else -> null
    }

fun eventText(event: Event): EventText =
    when (event.kind) {
        "login_attempt" -> {
            EventText(R.string.event_login_title, R.string.event_login_action, R.string.event_login_consequence, R.string.event_login_why)
        }

        "connection" -> {
            EventText(
                R.string.event_connection_title,
                R.string.event_connection_action,
                R.string.event_probe_consequence,
                R.string.event_connection_why,
            )
        }

        "port_scan" -> {
            EventText(R.string.event_scan_title, R.string.event_scan_action, R.string.event_probe_consequence, R.string.event_scan_why)
        }

        "host_scan" -> {
            EventText(
                R.string.event_host_scan_title,
                R.string.event_host_scan_action,
                R.string.event_host_scan_consequence,
                R.string.event_host_scan_why,
            )
        }

        "arp_spoof" -> {
            EventText(
                R.string.event_arp_spoof_title,
                R.string.event_arp_spoof_action,
                R.string.event_arp_spoof_consequence,
                R.string.event_arp_spoof_why,
            )
        }

        "rogue_dhcp" -> {
            EventText(
                R.string.event_rogue_dhcp_title,
                R.string.event_rogue_dhcp_action,
                R.string.event_redirect_consequence,
                R.string.event_rogue_dhcp_why,
            )
        }

        "rogue_router" -> {
            EventText(
                R.string.event_rogue_router_title,
                R.string.event_rogue_router_action,
                R.string.event_redirect_consequence,
                R.string.event_rogue_router_why,
            )
        }

        "name_poisoning" -> {
            EventText(
                R.string.event_name_poisoning_title,
                R.string.event_name_poisoning_action,
                R.string.event_name_poisoning_consequence,
                R.string.event_name_poisoning_why,
            )
        }

        "ids_alert" -> {
            EventText(R.string.event_ids_title, R.string.event_ids_action, R.string.event_ids_consequence, R.string.event_ids_why)
        }

        "new_host" -> {
            EventText(
                R.string.event_new_host_title,
                R.string.event_new_host_action,
                R.string.event_new_host_consequence,
                R.string.event_new_host_why,
            )
        }

        "file_access" -> {
            EventText(
                R.string.event_file_access_title,
                R.string.event_file_access_action,
                R.string.event_file_access_consequence,
                R.string.event_file_access_why,
                aboutSource = false,
            )
        }

        "port_forward" -> {
            EventText(
                R.string.event_port_forward_title,
                R.string.event_port_forward_action,
                R.string.event_port_forward_consequence,
                R.string.event_port_forward_why,
            )
        }

        // The source of a dns_hijack event is the resolver, and of the Wi-Fi
        // watch's events a network nearby, not a device the inventory names.
        "dns_hijack" -> {
            EventText(
                R.string.event_dns_hijack_title,
                R.string.event_dns_hijack_action,
                R.string.event_dns_hijack_consequence,
                R.string.event_dns_hijack_why,
                aboutSource = false,
            )
        }

        "evil_twin" -> {
            EventText(
                R.string.event_evil_twin_title,
                R.string.event_evil_twin_action,
                R.string.event_evil_twin_consequence,
                R.string.event_evil_twin_why,
                aboutSource = false,
            )
        }

        "new_access_point" -> {
            EventText(
                R.string.event_new_access_point_title,
                R.string.event_new_access_point_action,
                R.string.event_new_access_point_consequence,
                R.string.event_new_access_point_why,
                aboutSource = false,
            )
        }

        "device_offline" -> {
            EventText(
                R.string.event_offline_title,
                R.string.event_offline_action,
                R.string.event_offline_consequence,
                R.string.event_offline_why,
                aboutSource = false,
            )
        }

        "device_online" -> {
            EventText(
                R.string.event_online_title,
                R.string.event_online_action,
                R.string.event_online_consequence,
                R.string.event_online_why,
                aboutSource = false,
            )
        }

        else -> {
            EventText(
                R.string.event_other_title,
                R.string.event_other_action,
                R.string.event_other_consequence,
                R.string.event_other_why,
                aboutSource = false,
            )
        }
    }

/** How much one event stands for, as in "14 times" or "37 openings": a plural resource and its number. */
data class Amount(
    @param:PluralsRes val words: Int,
    val count: Int,
)

/** How much [event] stands for; null when it happened once. A scan's count is the openings it tried. */
fun amount(event: Event): Amount? =
    when {
        event.kind == "port_scan" -> Amount(R.plurals.alert_openings, event.count)
        event.kind == "host_scan" -> event.detail.targets?.let { Amount(R.plurals.alert_addresses, it) }
        event.count > 1 -> Amount(R.plurals.alert_times, event.count)
        else -> null
    }

/** A specific about an incident, in the app's own words. */
sealed interface Fact {
    /** The decoys it went for, as string resources of service names. */
    data class Decoys(
        val services: List<Int>,
    ) : Fact

    /** The well-known services among the openings it tried, as string resources of plain names. */
    data class Openings(
        val services: List<Int>,
    ) : Fact

    /** It claimed the router's address. */
    data object ClaimedRouter : Fact
}

private const val MAX_NAMED_OPENINGS = 4

/** The specifics of an incident's [events]; [gateways] are the router addresses DeKoi knows. */
fun facts(
    events: List<Event>,
    gateways: Set<String>,
): List<Fact> {
    val decoys =
        events
            .filter { it.kind == "login_attempt" || it.kind == "connection" }
            .mapNotNull { event -> event.service?.let(::serviceName) }
            .distinct()
    val openings =
        events
            .filter { it.kind == "port_scan" }
            .flatMap { it.detail.ports }
            .mapNotNull(::portName)
            .distinct()
    val router = events.any { it.kind == "arp_spoof" && it.detail.claimedIp in gateways }
    return listOfNotNull(
        Fact.Decoys(decoys).takeIf { decoys.isNotEmpty() },
        Fact.Openings(openings.take(MAX_NAMED_OPENINGS)).takeIf { openings.isNotEmpty() },
        Fact.ClaimedRouter.takeIf { router },
    )
}
