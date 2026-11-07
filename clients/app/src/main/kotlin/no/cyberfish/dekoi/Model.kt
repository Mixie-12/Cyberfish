package no.cyberfish.dekoi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

// Rows as the backend returns them (backend/supabase/migrations). Values in
// Event and NetworkHost come from the honeypot and are attacker-controlled:
// they are only ever shown as plain text.

@Serializable
data class Device(
    val id: String,
    val name: String,
    @SerialName("agent_version") val agentVersion: String? = null,
    @SerialName("local_ip") val localIp: String? = null,
    val services: List<String> = emptyList(),
    val online: Boolean = false,
    @SerialName("registered_at") @Serializable(TimestampSerializer::class) val registeredAt: Instant? = null,
    @SerialName("last_seen_at") @Serializable(TimestampSerializer::class) val lastSeenAt: Instant? = null,
    @SerialName("created_at") @Serializable(TimestampSerializer::class) val createdAt: Instant,
    /** Empty until the DeKoi runs software that reports them (ADR 0013). */
    val monitors: List<Monitor> = emptyList(),
    val images: List<ImageVersion> = emptyList(),
)

/** One check a DeKoi runs and how it is doing, from its heartbeat (ADR 0013). */
@Serializable
data class Monitor(
    @SerialName("name") val kindName: String,
    @SerialName("state") val stateName: String? = null,
    val detail: MonitorDetail = MonitorDetail(),
) {
    val kind: MonitorKind get() = MonitorKind.of(kindName)
    val state: MonitorState get() = MonitorState.of(stateName)
}

/** The small numbers and dates a monitor reports. */
@Serializable
data class MonitorDetail(
    val rules: Int? = null,
    @SerialName("ruleset_date") val rulesetDateText: String? = null,
    val hosts: Int? = null,
) {
    /** When the attack patterns were published; null when not reported or not a date. */
    val rulesetDate: LocalDate? get() = rulesetDateText?.let { parseOrNull { LocalDate.parse(it.take(ISO_DATE_LENGTH)) } }
}

/** A container image on a DeKoi and when it last changed (ADR 0013). */
@Serializable
data class ImageVersion(
    val name: String,
    val version: String,
    @SerialName("updated_at") val updatedAtText: String? = null,
) {
    val updatedAt: Instant? get() = updatedAtText?.let { parseOrNull { OffsetDateTime.parse(it).toInstant() } }
}

private const val ISO_DATE_LENGTH = 10

// The device writes these values into JSON itself. One it wrote in another
// format counts as not reported instead of failing the whole device list.
private fun <T> parseOrNull(parse: () -> T): T? =
    try {
        parse()
    } catch (_: DateTimeParseException) {
        null
    }

/** What a monitor watches for (ADR 0013 vocabulary). */
enum class MonitorKind {
    DECOYS,
    PORT_SCAN,
    ARP_WATCH,
    DHCP_WATCH,
    ROUTER_WATCH,
    NAME_POISONING,
    IDS,
    INVENTORY,
    OTHER,
    ;

    companion object {
        fun of(name: String?): MonitorKind = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OTHER
    }
}

/**
 * How a monitor is doing (ADR 0013 vocabulary), from fine to the most
 * concerning. A value from newer software is not known rather than fine.
 */
enum class MonitorState {
    OK,
    STARTING,
    OFF,
    UNKNOWN,
    FAILED,
    ;

    companion object {
        fun of(name: String?): MonitorState = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

@Serializable
data class Event(
    val id: Long,
    @SerialName("device_id") val deviceId: String,
    @SerialName("occurred_at") @Serializable(TimestampSerializer::class) val occurredAt: Instant,
    val kind: String,
    val service: String? = null,
    @SerialName("src_ip") val srcIp: String? = null,
    @SerialName("src_mac") val srcMac: String? = null,
    @SerialName("dst_port") val dstPort: Int? = null,
    val count: Int = 1,
    val detail: EventDetail = EventDetail(),
    @SerialName("severity") val severityName: String? = null,
) {
    val severity: Severity get() = Severity.of(severityName)
}

/** How serious an event is; the backend derives it from the event kind (ADR 0010). */
enum class Severity {
    CRITICAL,
    WARNING,
    INFO,
    ;

    companion object {
        // A value from a newer backend counts as a warning instead of failing.
        fun of(name: String?): Severity = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: WARNING
    }
}

/** What a host on the network looks like to DeKoi (ADR 0010 vocabulary). */
enum class HostKind {
    ROUTER,
    SWITCH,
    ACCESS_POINT,
    COMPUTER,
    PHONE,
    TABLET,
    TV,
    MEDIA_PLAYER,
    SPEAKER,
    GAME_CONSOLE,
    PRINTER,
    CAMERA,
    STORAGE,
    SMART_HOME,
    WEARABLE,
    SERVER,
    UNKNOWN,
    ;

    companion object {
        fun of(name: String?): HostKind = entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

/** Operating system family of a host (ADR 0010 vocabulary). */
enum class HostOs {
    ANDROID,
    IOS,
    IPADOS,
    MACOS,
    WINDOWS,
    LINUX,
    CHROMEOS,
    TVOS,
    WEBOS,
    TIZEN,
    FIRE_OS,
    OTHER,
    ;

    companion object {
        fun of(name: String?): HostOs? = name?.let { entries.firstOrNull { os -> os.name.equals(it, ignoreCase = true) } ?: OTHER }
    }
}

@Serializable
data class EventDetail(
    val username: String? = null,
    val path: String? = null,
    @SerialName("user_agent") val userAgent: String? = null,
    @SerialName("client_version") val clientVersion: String? = null,
    val command: String? = null,
    val ports: List<Int> = emptyList(),
    val targets: Int? = null,
    @SerialName("claimed_ip") val claimedIp: String? = null,
    @SerialName("previous_mac") val previousMac: String? = null,
    @SerialName("server_ip") val serverIp: String? = null,
    val protocol: String? = null,
    val signature: String? = null,
    val category: String? = null,
)

@Serializable
data class NetworkHost(
    @SerialName("device_id") val deviceId: String,
    val mac: String,
    val ip: String,
    val vendor: String? = null,
    @SerialName("is_private_mac") val isPrivateMac: Boolean = false,
    val trusted: Boolean = false,
    @SerialName("first_seen_at") @Serializable(TimestampSerializer::class) val firstSeenAt: Instant,
    @SerialName("last_seen_at") @Serializable(TimestampSerializer::class) val lastSeenAt: Instant,
    val hostname: String? = null,
    val model: String? = null,
    @SerialName("kind") val kindName: String? = null,
    @SerialName("os") val osName: String? = null,
    val services: List<String> = emptyList(),
) {
    val kind: HostKind get() = HostKind.of(kindName)
    val os: HostOs? get() = HostOs.of(osName)
}

/** A network DeKoi has evidence of: its own, or one it saw routes or traffic for. */
@Serializable
data class Network(
    @SerialName("device_id") val deviceId: String,
    val cidr: String,
    val source: String,
    val gateway: String? = null,
    @SerialName("first_seen_at") @Serializable(TimestampSerializer::class) val firstSeenAt: Instant,
    @SerialName("last_seen_at") @Serializable(TimestampSerializer::class) val lastSeenAt: Instant,
)

/** PostgREST timestamps carry an offset ("2026-11-07T10:00:00.123+00:00"). */
object TimestampSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Timestamp", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = OffsetDateTime.parse(decoder.decodeString()).toInstant()

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) = encoder.encodeString(value.toString())
}
