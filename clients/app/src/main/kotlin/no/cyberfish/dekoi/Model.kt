package no.cyberfish.dekoi

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.OffsetDateTime

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
)

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

/** The owner's own settings (owner_settings). Without a stored row, each one has its default, as in the table. */
@Serializable
data class OwnerSettings(
    @SerialName("weekly_summary") val weeklySummary: Boolean = true,
)
