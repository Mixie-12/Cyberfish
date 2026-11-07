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
)

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
