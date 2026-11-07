package no.cyberfish.dekoi.ui.learn

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes
import no.cyberfish.dekoi.R

/**
 * One topic of the Learn library: a title, a one-line summary, a few short
 * paragraphs, what DeKoi does about it, steps for the owner and a drawing. [id] goes
 * into routes and links, so it never changes once shipped.
 */
enum class LearnTopic(
    val id: String,
    @param:StringRes val title: Int,
    @param:StringRes val summary: Int,
    @param:ArrayRes val body: Int,
    @param:StringRes val dekoi: Int,
    @param:ArrayRes val steps: Int,
    val scene: Scene,
) {
    HONEYPOT(
        "honeypot",
        R.string.learn_honeypot_title,
        R.string.learn_honeypot_summary,
        R.array.learn_honeypot_body,
        R.string.learn_honeypot_dekoi,
        R.array.learn_honeypot_steps,
        Scene.DECOY,
    ),
    SIGN_IN(
        "sign_in",
        R.string.learn_sign_in_title,
        R.string.learn_sign_in_summary,
        R.array.learn_sign_in_body,
        R.string.learn_sign_in_dekoi,
        R.array.learn_sign_in_steps,
        Scene.TOUCH,
    ),
    PORT_SCAN(
        "port_scan",
        R.string.learn_port_scan_title,
        R.string.learn_port_scan_summary,
        R.array.learn_port_scan_body,
        R.string.learn_port_scan_dekoi,
        R.array.learn_port_scan_steps,
        Scene.PROBE,
    ),
    NETWORK_SWEEP(
        "network_sweep",
        R.string.learn_network_sweep_title,
        R.string.learn_network_sweep_summary,
        R.array.learn_network_sweep_body,
        R.string.learn_network_sweep_dekoi,
        R.array.learn_network_sweep_steps,
        Scene.SWEEP,
    ),
    IMPERSONATION(
        "impersonation",
        R.string.learn_impersonation_title,
        R.string.learn_impersonation_summary,
        R.array.learn_impersonation_body,
        R.string.learn_impersonation_dekoi,
        R.array.learn_impersonation_steps,
        Scene.IMPOSTOR,
    ),
    FAKE_SETTINGS(
        "fake_settings",
        R.string.learn_fake_settings_title,
        R.string.learn_fake_settings_summary,
        R.array.learn_fake_settings_body,
        R.string.learn_fake_settings_dekoi,
        R.array.learn_fake_settings_steps,
        Scene.SETTINGS,
    ),
    WIFI_COPY(
        "wifi_copy",
        R.string.learn_wifi_copy_title,
        R.string.learn_wifi_copy_summary,
        R.array.learn_wifi_copy_body,
        R.string.learn_wifi_copy_dekoi,
        R.array.learn_wifi_copy_steps,
        Scene.WIFI_COPY,
    ),
    NEW_DEVICE(
        "new_device",
        R.string.learn_new_device_title,
        R.string.learn_new_device_summary,
        R.array.learn_new_device_body,
        R.string.learn_new_device_dekoi,
        R.array.learn_new_device_steps,
        Scene.NEW_DEVICE,
    ),
    PORT_FORWARD(
        "port_forward",
        R.string.learn_port_forward_title,
        R.string.learn_port_forward_summary,
        R.array.learn_port_forward_body,
        R.string.learn_port_forward_dekoi,
        R.array.learn_port_forward_steps,
        Scene.EXPOSED,
    ),
    DNS_TAMPERING(
        "dns_tampering",
        R.string.learn_dns_tampering_title,
        R.string.learn_dns_tampering_summary,
        R.array.learn_dns_tampering_body,
        R.string.learn_dns_tampering_dekoi,
        R.array.learn_dns_tampering_steps,
        Scene.DNS,
    ),
    HOME_SAFETY(
        "home_safety",
        R.string.learn_home_safety_title,
        R.string.learn_home_safety_summary,
        R.array.learn_home_safety_body,
        R.string.learn_home_safety_dekoi,
        R.array.learn_home_safety_steps,
        Scene.STEPS,
    ),
    ;

    companion object {
        fun of(id: String): LearnTopic? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The topic that explains an event kind, or null for a kind from a newer
 * backend. Besides the kinds devices send today, this covers the ones
 * decided in ADR 0015 (`new_host`, `port_forward`, `dns_hijack`), 0018
 * (`file_access`) and 0019 (`evil_twin`).
 */
fun learnTopicFor(kind: String): LearnTopic? =
    when (kind) {
        "connection", "file_access", "device_offline", "device_online" -> LearnTopic.HONEYPOT
        "login_attempt" -> LearnTopic.SIGN_IN
        "port_scan", "ids_alert" -> LearnTopic.PORT_SCAN
        "host_scan" -> LearnTopic.NETWORK_SWEEP
        "arp_spoof", "name_poisoning" -> LearnTopic.IMPERSONATION
        "rogue_dhcp", "rogue_router" -> LearnTopic.FAKE_SETTINGS
        "evil_twin", "new_access_point" -> LearnTopic.WIFI_COPY
        "new_host" -> LearnTopic.NEW_DEVICE
        "port_forward" -> LearnTopic.PORT_FORWARD
        "dns_hijack" -> LearnTopic.DNS_TAMPERING
        else -> null
    }
