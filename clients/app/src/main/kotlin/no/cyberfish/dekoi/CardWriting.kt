package no.cyberfish.dekoi

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.Image
import java.io.Closeable

/** A USB card reader, by its device name in UsbManager. */
data class Reader(
    val id: String,
    val name: String,
)

/** A card in a reader the owner chose, open until it is written or let go. */
class OpenCard(
    val reader: Reader,
    val device: BlockDeviceDriver,
    connection: Closeable,
) : Closeable by connection {
    val name: String get() = reader.name
    val bytes: Long get() = device.blocks * device.blockSize
}

/** The steps of writing a card with this phone (ADR 0014, 0017). */
sealed interface WriteState {
    data object Connect : WriteState

    /** The readers found; empty when none is connected. */
    data class Choose(
        val readers: List<Reader>,
    ) : WriteState

    /** Android asks the owner about the chosen reader, then the card is read. */
    data object Opening : WriteState

    /** Waiting for the owner to confirm "Erase this card". */
    data class Confirm(
        val name: String,
        val bytes: Long,
    ) : WriteState

    data class Writing(
        val downloaded: Long = 0,
        val written: Long = 0,
        val checked: Long = 0,
    ) : WriteState

    /** The card is written and verified; its code waits to be linked to the account. */
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
        /** What the reader, the phone or the download reported, from [technicalDetail]. */
        val detail: String? = null,
    ) : WriteState
}

/**
 * Writing one card, shared by the screen and WriteCardService. Each step
 * only follows the one before it, so a late or repeated call (a second tap,
 * progress after a failure) changes nothing. The open card is held here
 * until the service takes it to write.
 */
class CardWriting(
    private val image: Image,
) {
    private val states = MutableStateFlow<WriteState>(WriteState.Connect)
    val state: StateFlow<WriteState> = states.asStateFlow()
    private var card: OpenCard? = null

    /** Shows the readers connected now. */
    @Synchronized
    fun look(readers: List<Reader>) {
        val current = states.value
        if (current is WriteState.Connect || current is WriteState.Choose) states.value = WriteState.Choose(readers)
    }

    /** True when the owner may open a reader now; then it is opening. */
    @Synchronized
    fun opening(): Boolean {
        if (states.value !is WriteState.Choose) return false
        states.value = WriteState.Opening
        return true
    }

    /** The chosen card is open: the owner confirms it, or it is refused. */
    @Synchronized
    fun opened(opened: OpenCard) {
        if (states.value != WriteState.Opening) return opened.close()
        if (!suitableCard(opened.device.blockSize, opened.bytes, image)) {
            opened.close()
            states.value = WriteState.Failed(WriteFailure.UNSUITABLE, opened.bytes)
            return
        }
        card = opened
        states.value = WriteState.Confirm(opened.name, opened.bytes)
    }

    /** The owner confirmed: hands the card to the writer, or null if nothing is confirmed. */
    @Synchronized
    fun erase(): OpenCard? {
        if (states.value !is WriteState.Confirm) return null
        val confirmed = card
        card = null
        states.value = WriteState.Writing()
        return confirmed
    }

    @Synchronized
    fun progress(writing: WriteState.Writing) {
        if (states.value is WriteState.Writing) states.value = writing
    }

    @Synchronized
    fun written(code: ClaimCode) {
        if (states.value is WriteState.Writing) states.value = WriteState.Written(code)
    }

    /**
     * Stops with [failure]. The technical details hold [cause], when there is
     * one, and whether the phone still saw the reader afterwards, when known.
     */
    @Synchronized
    fun failed(
        failure: WriteFailure,
        cause: Throwable? = null,
        readerConnected: Boolean? = null,
    ) {
        val current = states.value
        if (current is WriteState.Opening || current is WriteState.Confirm || current is WriteState.Writing) {
            release()
            val presence = readerConnected?.let { if (it) "Reader still connected" else "Reader no longer connected" }
            val detail = listOfNotNull(cause?.let(::technicalDetail), presence).joinToString("\n").ifEmpty { null }
            states.value = WriteState.Failed(failure, detail = detail)
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
     * Starts over and lets go of an open card. A card being written, or a
     * written card whose code is not linked yet, is kept.
     */
    @Synchronized
    fun reset() {
        val current = states.value
        if (current is WriteState.Writing || current is WriteState.Written || current is WriteState.Linking) return
        release()
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

    private fun release() {
        card?.close()
        card = null
    }
}
