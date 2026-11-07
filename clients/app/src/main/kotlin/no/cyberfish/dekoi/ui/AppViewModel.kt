package no.cyberfish.dekoi.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.Backend
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.GoogleIdToken
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.ClaimCode
import java.io.IOException
import java.time.Instant

data class OwnerData(
    val devices: List<Device> = emptyList(),
    val events: List<Event> = emptyList(),
    val hosts: List<NetworkHost> = emptyList(),
    val networks: List<Network> = emptyList(),
    /** When the last refresh succeeded; null until the first one has. */
    val updatedAt: Instant? = null,
    /** Why the last refresh failed, shown above the data the app still has. */
    @param:StringRes val problem: Int? = null,
) {
    val loaded: Boolean get() = updatedAt != null

    val trustedMacs: Set<String> get() = hosts.filter { it.trusted }.map { it.mac }.toSet()

    fun hostFor(mac: String?): NetworkHost? = mac?.let { hosts.firstOrNull { host -> host.mac == it } }
}

class AppViewModel(
    private val backend: Backend,
    private val pushToken: suspend () -> String?,
) : ViewModel() {
    val session: StateFlow<SessionStatus> get() = backend.session

    private val owner = MutableStateFlow(OwnerData())
    val data: StateFlow<OwnerData> = owner.asStateFlow()

    private val messages = MutableStateFlow<Int?>(null)

    /** A string resource to show once, or null. */
    val message: StateFlow<Int?> = messages.asStateFlow()

    fun dismissMessage() {
        messages.value = null
    }

    private val openEvents = MutableStateFlow<Long?>(null)

    /** An event a tapped notification asked to show, or null. */
    val openEvent: StateFlow<Long?> = openEvents.asStateFlow()

    fun open(eventId: Long) {
        openEvents.value = eventId
    }

    fun eventOpened() {
        openEvents.value = null
    }

    /** Called whenever the app is open and signed in, so a rotated token always reaches the backend. */
    fun registerPush() =
        run {
            pushToken()?.let { backend.registerPushToken(it) }
        }

    private val refreshes = MutableStateFlow(false)

    /** True while a refresh the owner asked for is running. */
    val refreshing: StateFlow<Boolean> = refreshes.asStateFlow()

    fun refresh() {
        refreshes.value = true
        run(onFailure = { owner.value = owner.value.copy(problem = it) }) {
            try {
                owner.value = load()
            } finally {
                refreshes.value = false
            }
        }
    }

    fun signIn(token: GoogleIdToken) =
        run {
            backend.signInWithGoogle(token.idToken, token.rawNonce)
            owner.value = load()
        }

    /** Claims the code; onDone receives the message to show. */
    fun claim(
        code: ClaimCode,
        onDone: (Int) -> Unit,
    ) {
        viewModelScope.launch {
            val result =
                try {
                    backend.claim(code)
                    owner.value = owner.value.copy(devices = backend.devices())
                    R.string.add_done
                } catch (error: RestException) {
                    if (error.statusCode == TOO_MANY_REQUESTS) R.string.add_too_many else R.string.add_refused
                } catch (_: HttpRequestException) {
                    R.string.error_network
                } catch (_: HttpRequestTimeoutException) {
                    R.string.error_network
                }
            onDone(result)
        }
    }

    fun rename(
        device: Device,
        name: String,
    ) = run {
        backend.rename(device, name)
        owner.value = owner.value.copy(devices = backend.devices())
    }

    fun remove(device: Device) =
        run {
            backend.remove(device)
            owner.value = load()
        }

    fun setTrusted(
        host: NetworkHost,
        trusted: Boolean,
    ) = run {
        backend.setTrusted(host, trusted)
        owner.value = owner.value.copy(hosts = backend.hosts())
    }

    /** Stops pushes to this phone first; a failure leaves the user signed in rather than alerting a signed-out phone. */
    fun signOut() =
        run {
            pushToken()?.let { backend.unregisterPushToken(it) }
            backend.signOut()
            owner.value = OwnerData()
        }

    fun deleteAccount() =
        run {
            backend.deleteAccount()
            owner.value = OwnerData()
        }

    private suspend fun load() =
        OwnerData(backend.devices(), backend.events(), backend.hosts(), backend.networks(), updatedAt = Instant.now())

    /** Runs [block]; a failure goes to [onFailure], by default a snackbar. */
    private fun run(
        onFailure: (Int) -> Unit = ::show,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: RestException) {
                onFailure(R.string.error_generic)
            } catch (_: HttpRequestException) {
                onFailure(R.string.error_network)
            } catch (_: HttpRequestTimeoutException) {
                onFailure(R.string.error_network)
            } catch (_: IOException) {
                // FCM could not hand out a token, usually for want of a connection.
                onFailure(R.string.error_network)
            }
        }
    }

    private fun show(
        @StringRes message: Int,
    ) {
        messages.value = message
    }

    fun setWifiName(
        device: Device,
        name: String?,
    ) = run {
        backend.setWifiName(device, name)
        owner.value = owner.value.copy(devices = backend.devices())
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
