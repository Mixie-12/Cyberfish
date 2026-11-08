package no.cyberfish.dekoi

import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.shared.ClaimCode
import java.io.IOException

/** A memory card Android has mounted, in a card reader or the phone's own slot. */
data class Card(
    /** The volume's UUID, which also names it in Android's storage provider. */
    val uuid: String,
    val name: String,
    /** The size of its file system, as Android reports it; 0 when it does not. */
    val bytes: Long,
    /** Mounted read-only: the card's lock switch is down. */
    val locked: Boolean,
)

/** Why preparing a card stopped; each has its own message for the owner. */
enum class WriteFailure {
    DENIED,
    TOO_SMALL,
    TOO_LARGE,
    LOCKED,
    NETWORK,
    DOWNLOAD_DAMAGED,
    CARD_ERROR,
    NOT_VERIFIED,
}

/** Preparing stopped; the owner is told [failure]. */
class WriteFailed(
    val failure: WriteFailure,
    cause: Throwable? = null,
) : IOException(failure.name, cause)

/**
 * Runs a step whose errors mean [failure]: every exception the network,
 * the phone's storage or Android's storage provider throws (IOException,
 * SecurityException, IllegalArgumentException, IllegalStateException)
 * becomes [WriteFailed], so none closes the app and each is told as what
 * the step was doing.
 */
internal inline fun <T> failingWith(
    failure: WriteFailure,
    step: () -> T,
): T =
    try {
        step()
    } catch (error: WriteFailed) {
        throw error
    } catch (error: IOException) {
        throw WriteFailed(failure, error)
    } catch (error: RuntimeException) {
        throw WriteFailed(failure, error)
    }

// The chain and length of a technical detail.
private const val DETAIL_CAUSES = 6
private const val DETAIL_LENGTH = 1000

/**
 * Why preparing stopped, for the technical details under the failure:
 * each cause's type and message, outermost first, as plain text.
 */
fun technicalDetail(error: Throwable): String =
    generateSequence(error) { it.cause }
        .take(DETAIL_CAUSES)
        .joinToString("\n") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
        .filter { it == '\n' || !it.isISOControl() }
        .take(DETAIL_LENGTH)

// Android formats a card of up to 32,896 MiB as FAT32 and a larger one as
// exFAT (vold, PublicVolume::doFormat), which the Raspberry Pi cannot start
// from (ADR 0031). Cards hold a few percent less than their nominal size,
// so 8 GB cards pass and 4 GB cards do not.
private const val MIN_CARD_BYTES = 7_000_000_000L
private const val MAX_CARD_BYTES = 32_896L * 1024 * 1024

/** Why [card] cannot be prepared, or null when it can. */
fun cardProblem(card: Card): WriteFailure? =
    when {
        card.locked -> WriteFailure.LOCKED
        card.bytes < MIN_CARD_BYTES -> WriteFailure.TOO_SMALL
        card.bytes > MAX_CARD_BYTES -> WriteFailure.TOO_LARGE
        else -> null
    }

// The provider StorageVolume.createOpenDocumentTreeIntent opens. A volume's
// root folder there is "<UUID>:" and any other folder "<UUID>:<path>"
// (ExternalStorageProvider.getDocIdForFile).
private const val STORAGE_PROVIDER = "com.android.externalstorage.documents"

/**
 * True for the root folder of [card] in Android's storage provider: never
 * a folder on it, and never another volume, such as the phone's storage.
 */
fun isCardRoot(
    authority: String?,
    treeDocumentId: String,
    card: Card,
): Boolean = authority == STORAGE_PROVIDER && treeDocumentId == "${card.uuid}:"

/** The steps of preparing a card with this phone (ADR 0017, 0031). */
sealed interface WriteState {
    data object Connect : WriteState

    /** The cards found; empty when none is connected. */
    data class Choose(
        val cards: List<Card>,
    ) : WriteState

    /** Android's picker asks the owner whether DeKoi may use [card]. */
    data class Opening(
        val card: Card,
    ) : WriteState

    /** Waiting for the owner to confirm "Erase this card". */
    data class Confirm(
        val card: Card,
        /** The card's root folder, as Android's picker granted it. */
        val tree: String,
    ) : WriteState

    data class Writing(
        val downloaded: Long = 0,
        val written: Long = 0,
        val checked: Long = 0,
        /** Bytes to copy to the card; 0 until the bundle is downloaded. */
        val total: Long = 0,
    ) : WriteState

    /** The card is prepared and verified; its code waits to be linked to the account. */
    data class Written(
        val code: ClaimCode,
        /** Why the last attempt to link it failed; null before the first one. */
        @param:StringRes val problem: Int? = null,
    ) : WriteState {
        // Keeps the code out of anything that prints the state.
        override fun toString() = "Written(problem=$problem)"
    }

    /** The code is being claimed. */
    data class Linking(
        val code: ClaimCode,
    ) : WriteState {
        override fun toString() = "Linking"
    }

    data class Linked(
        val name: String,
    ) : WriteState

    data class Failed(
        val failure: WriteFailure,
        /** The card's size, when it was the problem. */
        val bytes: Long? = null,
        /** What the phone or the download reported, from [technicalDetail]. */
        val detail: String? = null,
    ) : WriteState
}

/**
 * Preparing one card. It outlives the screen that starts it: [prepare]
 * runs in [scope], so rotating the phone or leaving the screen does not
 * stop it, and a written code waits here until it is linked. Each step
 * only follows the one before it, so a late or repeated call (a second
 * tap, progress after a failure) changes nothing.
 */
class CardWriting(
    private val prepare: (WriteState.Confirm, (WriteState.Writing) -> Unit) -> ClaimCode,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val states = MutableStateFlow<WriteState>(WriteState.Connect)
    val state: StateFlow<WriteState> = states.asStateFlow()

    /** Shows the cards connected now. */
    @Synchronized
    fun look(cards: List<Card>) {
        val current = states.value
        if (current is WriteState.Connect || current is WriteState.Choose) states.value = WriteState.Choose(cards)
    }

    /**
     * True when Android's picker may open on [card] now. A card that cannot
     * be prepared is refused here, before the owner is asked anything.
     */
    @Synchronized
    fun open(card: Card): Boolean {
        if (states.value !is WriteState.Choose) return false
        val problem = cardProblem(card)
        states.value = if (problem == null) WriteState.Opening(card) else WriteState.Failed(problem, card.bytes)
        return problem == null
    }

    /** The picker's answer: the card's root folder, or null when the owner gave none or another folder. */
    @Synchronized
    fun opened(tree: String?) {
        val current = states.value as? WriteState.Opening ?: return
        states.value = if (tree == null) WriteState.Failed(WriteFailure.DENIED) else WriteState.Confirm(current.card, tree)
    }

    /** The owner confirmed: the card is prepared in the background. */
    @Synchronized
    fun erase() {
        val confirmed = states.value as? WriteState.Confirm ?: return
        states.value = WriteState.Writing()
        scope.launch {
            try {
                written(prepare(confirmed, ::progress))
            } catch (error: WriteFailed) {
                failed(error.failure, error)
            }
        }
    }

    @Synchronized
    fun progress(writing: WriteState.Writing) {
        if (states.value is WriteState.Writing) states.value = writing
    }

    @Synchronized
    fun written(code: ClaimCode) {
        if (states.value is WriteState.Writing) states.value = WriteState.Written(code)
    }

    /** Stops with [failure]; the technical details hold [cause], when there is one. */
    @Synchronized
    fun failed(
        failure: WriteFailure,
        cause: Throwable? = null,
    ) {
        val current = states.value
        if (current is WriteState.Opening || current is WriteState.Confirm || current is WriteState.Writing) {
            states.value = WriteState.Failed(failure, detail = cause?.let(::technicalDetail))
        }
    }

    /** The code to claim now, or null unless a written card waits to be linked. */
    @Synchronized
    fun claim(): ClaimCode? {
        val current = states.value as? WriteState.Written ?: return null
        states.value = WriteState.Linking(current.code)
        return current.code
    }

    @Synchronized
    fun claimFailed(
        @StringRes problem: Int,
    ) {
        val current = states.value
        if (current is WriteState.Linking) states.value = WriteState.Written(current.code, problem)
    }

    /**
     * The code is linked. Also from Written: when a claim was sent twice,
     * the refusal of one can arrive before the success of the other.
     */
    @Synchronized
    fun linked() {
        val code =
            when (val current = states.value) {
                is WriteState.Linking -> current.code
                is WriteState.Written -> current.code
                else -> return
            }
        states.value = WriteState.Linked(code.shortName)
    }

    /**
     * Starts over. A card being prepared, or a prepared card whose code is
     * not linked yet, is kept.
     */
    @Synchronized
    fun reset() {
        val current = states.value
        if (current is WriteState.Writing || current is WriteState.Written || current is WriteState.Linking) return
        states.value = WriteState.Connect
    }

    /**
     * A new visit to the screen: starts over, and a claim whose answer never
     * came (the screen that sent it is gone) may be sent again.
     */
    @Synchronized
    fun enter() {
        val current = states.value
        if (current is WriteState.Linking) states.value = WriteState.Written(current.code) else reset()
    }
}
