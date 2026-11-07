package no.cyberfish.dekoi

import androidx.annotation.StringRes

/**
 * What an event means, in words a non-technical owner understands. The one
 * place that turns event kinds and decoy services into text; all wording
 * lives in string resources so it can be translated.
 */
data class EventText(
    @param:StringRes val title: Int,
    @param:StringRes val service: Int?,
    @param:StringRes val explanation: Int,
    @param:StringRes val advice: Int,
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

fun eventText(event: Event): EventText =
    when (event.kind) {
        "login_attempt" -> {
            EventText(R.string.event_login_title, serviceName(event.service), R.string.event_login_why, R.string.event_intrusion_advice)
        }

        "connection" -> {
            EventText(
                R.string.event_connection_title,
                serviceName(event.service),
                R.string.event_connection_why,
                R.string.event_intrusion_advice,
            )
        }

        "port_scan" -> {
            EventText(R.string.event_scan_title, null, R.string.event_scan_why, R.string.event_intrusion_advice)
        }

        "host_scan" -> {
            EventText(R.string.event_host_scan_title, null, R.string.event_host_scan_why, R.string.event_intrusion_advice)
        }

        "arp_spoof" -> {
            EventText(R.string.event_arp_spoof_title, null, R.string.event_arp_spoof_why, R.string.event_arp_spoof_advice)
        }

        "rogue_dhcp" -> {
            EventText(R.string.event_rogue_dhcp_title, null, R.string.event_rogue_dhcp_why, R.string.event_rogue_advice)
        }

        "rogue_router" -> {
            EventText(R.string.event_rogue_router_title, null, R.string.event_rogue_router_why, R.string.event_rogue_advice)
        }

        "name_poisoning" -> {
            EventText(
                R.string.event_name_poisoning_title,
                null,
                R.string.event_name_poisoning_why,
                R.string.event_intrusion_advice,
            )
        }

        "ids_alert" -> {
            EventText(R.string.event_ids_title, null, R.string.event_ids_why, R.string.event_intrusion_advice)
        }

        "new_host" -> {
            EventText(R.string.event_new_host_title, null, R.string.event_new_host_why, R.string.event_new_host_advice)
        }

        "file_access" -> {
            EventText(
                R.string.event_file_access_title,
                serviceName(event.service),
                R.string.event_file_access_why,
                R.string.event_file_access_advice,
            )
        }

        "device_offline" -> {
            EventText(R.string.event_offline_title, null, R.string.event_offline_why, R.string.event_offline_advice)
        }

        "device_online" -> {
            EventText(R.string.event_online_title, null, R.string.event_online_why, R.string.event_online_advice)
        }

        else -> {
            EventText(R.string.event_other_title, null, R.string.event_other_why, R.string.event_intrusion_advice)
        }
    }
