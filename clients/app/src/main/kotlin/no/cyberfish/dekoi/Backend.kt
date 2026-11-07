package no.cyberfish.dekoi

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import no.cyberfish.dekoi.shared.ClaimCode

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

    suspend fun registerPushToken(token: String) {
        client.postgrest.rpc("register_push_token_v1", buildJsonObject { put("token", token) })
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

    /** The home network's name for the Wi-Fi watch (ADR 0019); null turns the watch off. */
    suspend fun setWifiName(
        device: Device,
        name: String?,
    ) {
        client.from("devices").update({ if (name == null) setToNull("wifi_name") else set("wifi_name", name) }) {
            filter { eq("id", device.id) }
        }
    }

    private companion object {
        const val EVENT_LIMIT = 200L
    }
}
