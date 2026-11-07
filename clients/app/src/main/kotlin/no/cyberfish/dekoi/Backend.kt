package no.cyberfish.dekoi

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import no.cyberfish.dekoi.shared.ClaimCode
import java.net.HttpURLConnection
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Everything the app asks of the backend (docs/architecture.md, "App API").
 * Reads go through RLS; the only writes are the column grants and the _v1
 * functions the backend publishes.
 */
class Backend(
    url: String,
    publishableKey: String,
) {
    private val client =
        createSupabaseClient(url, publishableKey) {
            install(Auth)
            install(Postgrest)
        }

    val session: StateFlow<SessionStatus> get() = client.auth.sessionStatus

    suspend fun signInWithGoogle(
        idToken: String,
        rawNonce: String,
    ) {
        client.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            nonce = rawNonce
        }
    }

    suspend fun signOut() = client.auth.signOut()

    /** Returns the device id. */
    suspend fun claim(code: ClaimCode): String =
        client.postgrest
            .rpc(
                "claim_device_v1",
                buildJsonObject {
                    put("claim_code", code.value)
                    put("name", code.shortName)
                },
            ).decodeAs()

    suspend fun devices(): List<Device> = client.from("devices").select { order("created_at", Order.ASCENDING) }.decodeList()

    suspend fun events(): List<Event> =
        client
            .from("events")
            .select {
                order("occurred_at", Order.DESCENDING)
                limit(EVENT_LIMIT)
            }.decodeList()

    suspend fun hosts(): List<NetworkHost> = client.from("network_hosts").select { order("first_seen_at", Order.DESCENDING) }.decodeList()

    suspend fun networks(): List<Network> = client.from("networks").select { order("cidr", Order.ASCENDING) }.decodeList()

    suspend fun rename(
        device: Device,
        name: String,
    ) {
        client.from("devices").update({ set("name", name) }) { filter { eq("id", device.id) } }
    }

    suspend fun remove(device: Device) {
        client.from("devices").delete { filter { eq("id", device.id) } }
    }

    suspend fun setTrusted(
        host: NetworkHost,
        trusted: Boolean,
    ) {
        client.from("network_hosts").update({ set("trusted", trusted) }) {
            filter {
                eq("device_id", host.deviceId)
                eq("mac", host.mac)
            }
        }
    }

    /**
     * Registers this phone for pushes in its language. A backend that
     * answers 404 predates register_push_token_v2 (PostgREST's "function
     * not found"); v1 then registers the phone, in English.
     */
    suspend fun registerPushToken(token: String) {
        try {
            client.postgrest.rpc(
                "register_push_token_v2",
                buildJsonObject {
                    put("token", token)
                    put("language", pushLanguage(Locale.getDefault()))
                },
            )
        } catch (error: RestException) {
            if (error.statusCode != HttpURLConnection.HTTP_NOT_FOUND) throw error
            client.postgrest.rpc("register_push_token_v1", buildJsonObject { put("token", token) })
        }
    }

    suspend fun unregisterPushToken(token: String) {
        client.postgrest.rpc("unregister_push_token_v1", buildJsonObject { put("token", token) })
    }

    /** Deletes the account and everything in it, then forgets the session here. */
    suspend fun deleteAccount() {
        client.postgrest.rpc("delete_account_v1")
        client.auth.signOut(SignOutScope.LOCAL)
    }

    /** RLS returns only the caller's row, if they have one. */
    suspend fun settings(): OwnerSettings =
        client
            .from("owner_settings")
            .select(Columns.list("weekly_summary"))
            .decodeList<OwnerSettings>()
            .firstOrNull() ?: OwnerSettings()

    /** The row's user_id defaults to the caller; weekly_summary is the only column the app may write. */
    suspend fun setWeeklySummary(on: Boolean) {
        client.from("owner_settings").upsert(buildJsonObject { put("weekly_summary", on) }) { onConflict = "user_id" }
    }

    /** The owner's name for a host and the room it is in; null clears either (ADR 0013). */
    suspend fun setNameAndRoom(
        host: NetworkHost,
        label: String?,
        room: String?,
    ) {
        client.from("network_hosts").update({
            set("label", label)
            set("room", room)
        }) {
            filter {
                eq("device_id", host.deviceId)
                eq("mac", host.mac)
            }
        }
    }

    /** The home network's name for the Wi-Fi watch (ADR 0019); null turns the watch off. */
    suspend fun setWifiName(
        device: Device,
        name: String?,
    ) {
        client.from("devices").update({ if (name == null) setToNull("wifi_name") else set("wifi_name", name) }) {
            filter { eq("id", device.id) }
        }
    }

    /** The last week of one DeKoi's network health, the ports open now, and its latest speed tests (ADR 0015). */
    suspend fun insights(deviceId: String): NetworkInsights {
        val weekAgo = Instant.now().minus(Duration.ofDays(INSIGHT_DAYS)).toString()
        return NetworkInsights(
            deviceId,
            samples =
                client
                    .from("health_samples")
                    .select {
                        filter {
                            eq("device_id", deviceId)
                            gte("hour", weekAgo)
                        }
                        order("hour", Order.ASCENDING)
                    }.decodeList(),
            forwards =
                client
                    .from("port_forwards")
                    .select {
                        filter {
                            eq("device_id", deviceId)
                            eq("open", true)
                        }
                        order("internal_client", Order.ASCENDING)
                    }.decodeList(),
            speedTests =
                client
                    .from("speed_tests")
                    .select {
                        filter { eq("device_id", deviceId) }
                        order("tested_at", Order.DESCENDING)
                        limit(SPEED_TEST_LIMIT)
                    }.decodeList(),
        )
    }

    suspend fun setSpeedTest(
        device: Device,
        on: Boolean,
    ) {
        client.from("devices").update({ set("speed_test", on) }) { filter { eq("id", device.id) } }
    }

    private companion object {
        const val EVENT_LIMIT = 200L
        const val INSIGHT_DAYS = 7L
        const val SPEED_TEST_LIMIT = 14L
    }

    /** Marks events seen; the backend takes up to 500 ids and ignores events of other owners (ADR 0013). */
    suspend fun acknowledge(ids: List<Long>) {
        client.postgrest.rpc("acknowledge_events_v1", buildJsonObject { putJsonArray("event_ids") { ids.forEach { add(it) } } })
    }
}
