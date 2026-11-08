package no.cyberfish.dekoi.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.Backend
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Event
import no.cyberfish.dekoi.GoogleIdToken
import no.cyberfish.dekoi.Incident
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import no.cyberfish.dekoi.NetworkInsights
import no.cyberfish.dekoi.OwnerSettings
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.newAlerts
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.unseenBatches
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How often the open app loads again when no push says so; pushes can be late or lost (ADR 0032). */
private val LIVE_REFRESH: Duration = 20.seconds

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
    private val publishWidget: suspend (OwnerData) -> Unit,
    /** An alert was pushed to this phone (DekoiApplication.alertPushes). */
    private val alertPushes: Flow<Unit>,
) : ViewModel() {
    val session: StateFlow<SessionStatus> get() = backend.session

    private val owner = MutableStateFlow(OwnerData())
    val data: StateFlow<OwnerData> = owner.asStateFlow()

    // The home-screen widget shows the data last loaded, and nothing once
    // signed out. One collector, so the writes keep their order.
    init {
        val signedOut = session.filterIsInstance<SessionStatus.NotAuthenticated>().map { OwnerData() }
        viewModelScope.launch { merge(owner.filter { it.loaded }, signedOut).collect { publishWidget(it) } }
    }

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
        run(onFailure = ::showProblem) {
            try {
                owner.value = load()
            } finally {
                refreshes.value = false
            }
        }
    }

    private val arrivals = MutableStateFlow<List<Incident>>(emptyList())

    /** Alerts that arrived while the app was open, newest first, to announce once; empty when none. */
    val arrived: StateFlow<List<Incident>> = arrivals.asStateFlow()

    fun arrivalsAnnounced() {
        arrivals.value = emptyList()
    }

    /**
     * Keeps the data current while the app is resumed (ADR 0032): loads at
     * once, then on each alert pushed to this phone and every [LIVE_REFRESH],
     * until cancelled. The first load catches up on what came while the app
     * was away, which had its notifications then; alerts in later loads go
     * to [arrived].
     */
    suspend fun watch() {
        var caughtUp = false
        val ticks =
            flow {
                while (true) {
                    delay(LIVE_REFRESH)
                    emit(Unit)
                }
            }
        // Conflated, so a push during a load brings one more load, and pushes
        // are collected the whole time.
        merge(alertPushes, ticks).onStart { emit(Unit) }.conflate().collect {
            attempt(::showProblem) {
                val loaded = load()
                val alerts = newAlerts(owner.value.events.takeIf { caughtUp }, loaded.events, loaded.trustedMacs)
                owner.value = loaded
                caughtUp = true
                if (alerts.isNotEmpty()) arrivals.value = alerts
            }
        }
    }

    /** Claims the code of a card this phone prepared (WriteCardScreen); onDone receives the message to show. */
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

    /** Reloads only the device list, as adding a DeKoi does every few seconds while the owner waits. */
    fun refreshDevices() =
        run(onFailure = ::showProblem) {
            owner.value = owner.value.copy(devices = backend.devices(), problem = null)
        }

    fun signIn(token: GoogleIdToken) =
        run {
            backend.signInWithGoogle(token.idToken, token.rawNonce)
            owner.value = load()
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
            ownerSettings.value = null
            insightData.value = null
        }

    fun deleteAccount() =
        run {
            backend.deleteAccount()
            owner.value = OwnerData()
            ownerSettings.value = null
            insightData.value = null
        }

    private suspend fun load() =
        OwnerData(backend.devices(), backend.events(), backend.hosts(), backend.networks(), updatedAt = Instant.now())

    /** Runs [block]; a failure goes to [onFailure], by default a snackbar. */
    private fun run(
        onFailure: (Int) -> Unit = ::show,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch { attempt(onFailure, block) }
    }

    private suspend fun attempt(
        onFailure: (Int) -> Unit,
        block: suspend () -> Unit,
    ) {
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

    private fun show(
        @StringRes message: Int,
    ) {
        messages.value = message
    }

    /** A failed load: shown above the data the app still has. */
    private fun showProblem(
        @StringRes problem: Int,
    ) {
        owner.value = owner.value.copy(problem = problem)
    }

    private val ownerSettings = MutableStateFlow<OwnerSettings?>(null)

    /** The owner's settings; null until loaded, and again after signing out. */
    val settings: StateFlow<OwnerSettings?> = ownerSettings.asStateFlow()

    fun loadSettings() = run { ownerSettings.value = backend.settings() }

    fun setWeeklySummary(on: Boolean) =
        run {
            backend.setWeeklySummary(on)
            ownerSettings.value = OwnerSettings(weeklySummary = on)
        }

    fun setNameAndRoom(
        host: NetworkHost,
        label: String?,
        room: String?,
    ) = run {
        backend.setNameAndRoom(host, label, room)
        owner.value = owner.value.copy(hosts = backend.hosts())
    }

    /**
     * Marks events seen. The app shows them as seen at once; if the backend
     * refuses, the next refresh brings them back as unseen.
     */
    fun acknowledge(ids: List<Long>) {
        markSeen(ids)
        run { backend.acknowledge(ids) }
    }

    /** The Alerts tab's "Mark all as read": every unseen event, in batches the backend takes, then a reload. */
    fun acknowledgeAll() {
        val batches = unseenBatches(owner.value.events)
        if (batches.isEmpty()) return
        markSeen(batches.flatten())
        run {
            batches.forEach { backend.acknowledge(it) }
            owner.value = load()
        }
    }

    private fun markSeen(ids: List<Long>) {
        val seenAt = Instant.now()
        val marked = ids.toSet()
        owner.value =
            owner.value.copy(events = owner.value.events.map { if (it.id in marked && it.seenAt == null) it.copy(seenAt = seenAt) else it })
    }

    fun setWifiName(
        device: Device,
        name: String?,
    ) = run {
        backend.setWifiName(device, name)
        owner.value = owner.value.copy(devices = backend.devices())
    }

    private val insightData = MutableStateFlow<NetworkInsights?>(null)

    /** The network insights of the DeKoi last opened on the Insights screen; null until loaded, and again after signing out. */
    val insights: StateFlow<NetworkInsights?> = insightData.asStateFlow()

    fun loadInsights(deviceId: String) =
        run {
            insightData.value = backend.insights(deviceId)
        }

    fun setSpeedTest(
        device: Device,
        on: Boolean,
    ) = run {
        backend.setSpeedTest(device, on)
        owner.value = owner.value.copy(devices = backend.devices())
    }
}
