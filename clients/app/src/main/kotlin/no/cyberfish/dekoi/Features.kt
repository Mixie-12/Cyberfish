package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant

/**
 * What DeKoi does, in the order the Features screen shows it (ADR 0003,
 * 0010, 0013, 0015, 0018, 0019). [kinds] are the event kinds each one
 * finds, the first the one its Learn topic explains; [monitors] are the
 * checks it runs on, as the DeKoi reports them (ADR 0013).
 */
enum class Feature(
    val kinds: List<String>,
    val monitors: List<MonitorKind>,
) {
    DECOY(listOf("connection", "login_attempt", "file_access"), listOf(MonitorKind.DECOYS)),
    IDS(listOf("ids_alert"), listOf(MonitorKind.IDS)),
    SCANS(listOf("port_scan", "host_scan"), listOf(MonitorKind.PORT_SCAN, MonitorKind.ARP_WATCH)),
    ARP_GUARD(listOf("arp_spoof"), listOf(MonitorKind.ARP_WATCH)),
    ROUTER_GUARD(listOf("rogue_dhcp", "rogue_router"), listOf(MonitorKind.DHCP_WATCH, MonitorKind.ROUTER_WATCH)),
    NAME_TRAP(listOf("name_poisoning"), listOf(MonitorKind.NAME_POISONING)),
    WIFI_GUARD(listOf("evil_twin", "new_access_point"), emptyList()),
    NETWORK_MAP(emptyList(), listOf(MonitorKind.INVENTORY)),
    NEW_DEVICES(listOf("new_host"), listOf(MonitorKind.INVENTORY)),
    INTERNET_HEALTH(listOf("dns_hijack"), emptyList()),
    OPEN_PORTS(listOf("port_forward"), emptyList()),
    WEEKLY_SUMMARY(emptyList(), emptyList()),
}

/** How a feature is doing, as far as the data shows: nothing counts as working that the DeKoi has not reported. */
enum class FeatureState {
    ON,

    /** Running, and learning what is normal before it warns (ADR 0015, 0019). */
    LEARNING,
    STARTING,
    NEEDS_WIFI_NAME,

    /** The DeKoi's software does not report this yet; it arrives with an automatic update. */
    WAITING_FOR_UPDATE,

    /** The DeKoi is offline. */
    PAUSED,
    OFF,
    FAILED,

    /** A value from newer software, or the owner's setting before it has loaded. */
    UNKNOWN,
    ;

    /** Running, learning included. */
    val isOn: Boolean get() = this == ON || this == LEARNING
}

/** How long a DeKoi that started or updated may take to report a check for the first time; the slowest reports hourly. */
val FIRST_REPORT: Duration = Duration.ofHours(2)

/** The backend reports new devices once a DeKoi's first inventory is this old (ADR 0015). */
val NEW_DEVICE_LEARNING: Duration = Duration.ofHours(24)

/**
 * How [feature] is doing on this DeKoi. [hosts] are the owner's hosts and
 * [weeklySummary] the owner's setting, null until it has loaded. Software
 * from before ADR 0013 reports no monitors: only what it shows working in
 * other ways (its decoys, its device list) counts as on.
 */
fun Device.featureState(
    feature: Feature,
    hosts: List<NetworkHost>,
    weeklySummary: Boolean?,
    now: Instant,
): FeatureState {
    val mine = hosts.filter { it.deviceId == id }
    return when (feature) {
        // The backend sends the summary, whether or not the DeKoi is online.
        Feature.WEEKLY_SUMMARY -> {
            when (weeklySummary) {
                true -> FeatureState.ON
                false -> FeatureState.OFF
                null -> FeatureState.UNKNOWN
            }
        }

        // The owner can give the name while the DeKoi is offline.
        Feature.WIFI_GUARD -> {
            if (wifiName == null) FeatureState.NEEDS_WIFI_NAME else whileOnline(now) { wifiGuardState(now) }
        }

        // public_ip is set by every hourly report of ADR 0015, whatever else it holds.
        Feature.INTERNET_HEALTH, Feature.OPEN_PORTS -> {
            whileOnline(now) { reportedState(publicIp != null, now) }
        }

        Feature.NEW_DEVICES -> {
            whileOnline(now) { monitorState(feature, mine).learningNewDevices(mine, now) }
        }

        else -> {
            whileOnline(now) { monitorState(feature, mine) }
        }
    }
}

/** [state] while the DeKoi is online; a DeKoi setting up is starting everything, an offline one runs nothing. */
private inline fun Device.whileOnline(
    now: Instant,
    state: () -> FeatureState,
): FeatureState =
    when (status(now)) {
        DekoiStatus.SETTING_UP -> FeatureState.STARTING
        DekoiStatus.OFFLINE -> FeatureState.PAUSED
        DekoiStatus.ONLINE -> state()
    }

/** The most concerning state of the feature's monitors; waiting for an update while one is not reported. */
private fun Device.monitorState(
    feature: Feature,
    hosts: List<NetworkHost>,
): FeatureState {
    if (monitors.isEmpty()) return if (worksWithoutMonitors(feature, hosts)) FeatureState.ON else FeatureState.WAITING_FOR_UPDATE
    val reported = feature.monitors.map { kind -> monitors.firstOrNull { it.kind == kind }?.state }
    if (reported.isEmpty() || null in reported) return FeatureState.WAITING_FOR_UPDATE
    return when (reported.filterNotNull().max()) {
        MonitorState.OK -> FeatureState.ON
        MonitorState.STARTING -> FeatureState.STARTING
        MonitorState.OFF -> FeatureState.OFF
        MonitorState.UNKNOWN -> FeatureState.UNKNOWN
        MonitorState.FAILED -> FeatureState.FAILED
    }
}

/** What software from before ADR 0013 shows of a feature working: decoys it runs, devices it listed. */
private fun Device.worksWithoutMonitors(
    feature: Feature,
    hosts: List<NetworkHost>,
): Boolean =
    when (feature) {
        Feature.DECOY -> services.isNotEmpty()
        Feature.NETWORK_MAP, Feature.NEW_DEVICES -> hosts.isNotEmpty()
        else -> false
    }

/** The first day after the first inventory is the household, not newcomers. */
private fun FeatureState.learningNewDevices(
    hosts: List<NetworkHost>,
    now: Instant,
): FeatureState {
    val first = hosts.minOfOrNull { it.firstSeenAt } ?: return this
    return if (this == FeatureState.ON && Duration.between(first, now) < NEW_DEVICE_LEARNING) FeatureState.LEARNING else this
}

/** What the DeKoi reports of Wi-Fi Guard. NO_NAME is a name it has not picked up yet: it asks every few minutes. */
private fun Device.wifiGuardState(now: Instant): FeatureState =
    when (wifiWatch) {
        WifiWatch.WATCHING -> FeatureState.ON
        WifiWatch.LEARNING -> FeatureState.LEARNING
        WifiWatch.NO_NAME -> FeatureState.STARTING
        WifiWatch.NO_COUNTRY, WifiWatch.NO_RADIO, WifiWatch.UPLINK -> FeatureState.OFF
        null -> if (wifiWatchName == null) reportedState(reported = false, now) else FeatureState.UNKNOWN
    }

/** A check that reports by itself: on once it has, starting for a while after the DeKoi started or updated. */
private fun Device.reportedState(
    reported: Boolean,
    now: Instant,
): FeatureState =
    when {
        reported -> FeatureState.ON
        startedRecently(now) -> FeatureState.STARTING
        else -> FeatureState.WAITING_FOR_UPDATE
    }

/** Whether the DeKoi first started or last updated its software within [FIRST_REPORT]. */
fun Device.startedRecently(now: Instant): Boolean =
    listOfNotNull(registeredAt, softwareUpdatedAt).any { Duration.between(it, now) < FIRST_REPORT }

/** The newest event this DeKoi reported that [feature] finds, or null. */
fun lastFound(
    feature: Feature,
    deviceId: String,
    events: List<Event>,
): Event? = events.filter { it.deviceId == deviceId && it.kind in feature.kinds }.maxByOrNull { it.occurredAt }
