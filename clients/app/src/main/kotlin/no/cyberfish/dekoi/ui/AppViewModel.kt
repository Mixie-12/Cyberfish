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
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.ClaimCode

data class OwnerData(
    val devices: List<Device> = emptyList(),
    val events: List<Event> = emptyList(),
    val hosts: List<NetworkHost> = emptyList(),
    val loaded: Boolean = false,
) {
    val trustedMacs: Set<String> get() = hosts.filter { it.trusted }.map { it.mac }.toSet()

    fun hostFor(mac: String?): NetworkHost? = mac?.let { hosts.firstOrNull { host -> host.mac == it } }
}

class AppViewModel(
    private val backend: Backend,
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

    fun refresh() =
        run {
            owner.value = OwnerData(backend.devices(), backend.events(), backend.hosts(), loaded = true)
        }

    fun signIn(token: GoogleIdToken) =
        run {
            backend.signInWithGoogle(token.idToken, token.rawNonce)
            owner.value = OwnerData(backend.devices(), backend.events(), backend.hosts(), loaded = true)
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
            owner.value = OwnerData(backend.devices(), backend.events(), backend.hosts(), loaded = true)
        }

    fun setTrusted(
        host: NetworkHost,
        trusted: Boolean,
    ) = run {
        backend.setTrusted(host, trusted)
        owner.value = owner.value.copy(hosts = backend.hosts())
    }

    fun signOut() =
        run {
            backend.signOut()
            owner.value = OwnerData()
        }

    fun deleteAccount() =
        run {
            backend.deleteAccount()
            owner.value = OwnerData()
        }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (_: RestException) {
                show(R.string.error_generic)
            } catch (_: HttpRequestException) {
                show(R.string.error_network)
            } catch (_: HttpRequestTimeoutException) {
                show(R.string.error_network)
            }
        }
    }

    private fun show(
        @StringRes message: Int,
    ) {
        messages.value = message
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
