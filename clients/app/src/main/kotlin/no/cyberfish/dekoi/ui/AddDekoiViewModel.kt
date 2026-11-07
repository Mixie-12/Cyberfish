package no.cyberfish.dekoi.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.Backend
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.shared.ClaimCode

private const val TOO_MANY_REQUESTS = 429

/**
 * Claiming the code of a card prepared with DeKoi Setup (ADR 0017). Scoped
 * to the Add DeKoi screen, so a claim in flight survives rotation and the
 * screen starts over each time it opens.
 */
class AddDekoiViewModel(
    private val backend: Backend,
) : ViewModel() {
    private val linkedIds = MutableStateFlow<String?>(null)

    /** The device the code linked to this account, or null before that. */
    val linked: StateFlow<String?> = linkedIds.asStateFlow()

    private val claims = MutableStateFlow(false)

    /** True while a code is being claimed. */
    val claiming: StateFlow<Boolean> = claims.asStateFlow()

    private val problems = MutableStateFlow<Int?>(null)

    /** Why the last claim failed, or null. */
    val problem: StateFlow<Int?> = problems.asStateFlow()

    private var lastCode: ClaimCode? = null

    fun claim(code: ClaimCode) {
        if (claims.value) return
        claims.value = true
        problems.value = null
        lastCode = code
        viewModelScope.launch {
            try {
                linkedIds.value = backend.claim(code)
            } catch (error: RestException) {
                problems.value = if (error.statusCode == TOO_MANY_REQUESTS) R.string.add_too_many else R.string.add_refused
            } catch (_: HttpRequestException) {
                problems.value = R.string.error_network
            } catch (_: HttpRequestTimeoutException) {
                problems.value = R.string.error_network
            } finally {
                claims.value = false
            }
        }
    }

    /** Claims the last code again. */
    fun retry() {
        lastCode?.let(::claim)
    }
}
