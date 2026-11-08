package no.cyberfish.dekoi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import no.cyberfish.dekoi.shared.ClaimCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TREE = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A"

class CardWritingTest {
    private val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))
    private val card = Card("1A2B-3C4D", "SanDisk USB drive", 31_914_983_424, locked = false)

    // What the preparation does, set by each test; it runs at once, on the test's thread.
    private var prepare: (WriteState.Confirm, (WriteState.Writing) -> Unit) -> ClaimCode = { _, _ -> code }
    private val writing = CardWriting({ confirmed, progress -> prepare(confirmed, progress) }, CoroutineScope(Dispatchers.Unconfined))

    private fun confirm(chosen: Card = card) {
        writing.look(listOf(chosen))
        assertTrue(writing.open(chosen))
        writing.opened(TREE)
    }

    private fun written() {
        confirm()
        writing.erase()
    }

    @Test
    fun aCardGoesFromConnectToLinked() {
        assertEquals(WriteState.Connect, writing.state.value)
        writing.look(emptyList())
        assertEquals(WriteState.Choose(emptyList()), writing.state.value)
        writing.look(listOf(card))
        assertTrue(writing.open(card))
        assertEquals(WriteState.Opening(card), writing.state.value)
        writing.opened(TREE)
        assertEquals(WriteState.Confirm(card, TREE), writing.state.value)

        val seen = mutableListOf<WriteState>()
        prepare = { confirmed, progress ->
            assertEquals(WriteState.Confirm(card, TREE), confirmed)
            progress(WriteState.Writing(10, 20, 0, 40))
            seen += writing.state.value
            code
        }
        writing.erase()
        assertEquals(listOf<WriteState>(WriteState.Writing(10, 20, 0, 40)), seen)
        assertEquals(WriteState.Written(code), writing.state.value)

        assertEquals(code, writing.claim())
        assertEquals(WriteState.Linking(code), writing.state.value)
        writing.claimFailed(R.string.error_network)
        assertEquals(WriteState.Written(code, R.string.error_network), writing.state.value)
        assertEquals(code, writing.claim())
        writing.linked()
        assertEquals(WriteState.Linked(code.shortName), writing.state.value)
    }

    @Test
    fun onlyAConfirmedCardIsPreparedAndOnlyOnce() {
        var runs = 0
        prepare = { _, _ ->
            runs++
            code
        }
        writing.erase()
        assertEquals(0, runs)
        confirm()
        writing.erase()
        writing.erase()
        assertEquals(1, runs)
    }

    @Test
    fun cardsOf8To32GbArePrepared() {
        assertNull(cardProblem(card.copy(bytes = 7_948_206_080)), "a real 8 GB card")
        assertNull(cardProblem(card.copy(bytes = 31_914_983_424)), "a real 32 GB card")
        assertNull(cardProblem(card.copy(bytes = 32_896L * 1024 * 1024)), "the largest card Android formats as FAT32")
        assertEquals(WriteFailure.TOO_SMALL, cardProblem(card.copy(bytes = 3_965_190_144)), "a 4 GB card")
        assertEquals(WriteFailure.TOO_SMALL, cardProblem(card.copy(bytes = 0)), "a size Android did not report")
        assertEquals(WriteFailure.TOO_LARGE, cardProblem(card.copy(bytes = 32_896L * 1024 * 1024 + 1)), "exFAT from here on")
        assertEquals(WriteFailure.TOO_LARGE, cardProblem(card.copy(bytes = 63_831_015_424)), "a 64 GB card")
        assertEquals(WriteFailure.LOCKED, cardProblem(card.copy(locked = true)))
    }

    @Test
    fun anUnsuitableCardIsRefusedBeforeThePickerOpens() {
        val large = card.copy(bytes = 63_831_015_424)
        writing.look(listOf(large))

        assertFalse(writing.open(large))
        assertEquals(WriteState.Failed(WriteFailure.TOO_LARGE, 63_831_015_424), writing.state.value)
        writing.opened(TREE)
        assertIs<WriteState.Failed>(writing.state.value)
    }

    @Test
    fun onlyTheRootOfTheChosenCardIsAccepted() {
        val provider = "com.android.externalstorage.documents"
        assertTrue(isCardRoot(provider, "1A2B-3C4D:", card))
        assertFalse(isCardRoot(provider, "1A2B-3C4D:Download", card), "a folder on the card")
        assertFalse(isCardRoot(provider, "5E6F-7A8B:", card), "another card")
        assertFalse(isCardRoot(provider, "primary:", card), "the phone's own storage")
        assertFalse(isCardRoot("com.example.documents", "1A2B-3C4D:", card), "another provider")
        assertFalse(isCardRoot(null, "1A2B-3C4D:", card))
    }

    @Test
    fun noAccessFromThePickerIsAFailure() {
        writing.look(listOf(card))
        writing.open(card)
        writing.opened(null)

        assertEquals(WriteState.Failed(WriteFailure.DENIED), writing.state.value)
    }

    @Test
    fun aSecondTapOpensNothing() {
        writing.look(listOf(card))
        assertTrue(writing.open(card))
        assertFalse(writing.open(card))
    }

    @Test
    fun aCardConnectedLaterDoesNotHideAFailureOrAConfirmation() {
        confirm()
        writing.look(emptyList())
        assertIs<WriteState.Confirm>(writing.state.value)

        writing.failed(WriteFailure.CARD_ERROR)
        writing.look(listOf(card))
        assertEquals(WriteState.Failed(WriteFailure.CARD_ERROR), writing.state.value)
    }

    @Test
    fun aFailedPreparationSaysWhatHappened() {
        prepare = { _, _ -> throw WriteFailed(WriteFailure.CARD_ERROR, IOException("A file on the card could not be deleted")) }
        written()

        assertEquals(
            WriteState.Failed(
                WriteFailure.CARD_ERROR,
                detail = "WriteFailed: CARD_ERROR\nIOException: A file on the card could not be deleted",
            ),
            writing.state.value,
        )
    }

    @Test
    fun lateCallsAfterAFailureChangeNothing() {
        prepare = { _, _ -> throw WriteFailed(WriteFailure.NETWORK) }
        written()
        val failed = writing.state.value
        assertIs<WriteState.Failed>(failed)

        writing.progress(WriteState.Writing(1, 2, 3, 4))
        writing.written(code)
        assertNull(writing.claim())
        writing.linked()

        assertEquals(failed, writing.state.value)
    }

    @Test
    fun aCodeIsClaimedOnceAtATime() {
        written()
        assertEquals(code, writing.claim())
        assertNull(writing.claim())
    }

    @Test
    fun leavingKeepsAClaimInFlight() {
        written()
        writing.claim()
        writing.reset()

        assertEquals(WriteState.Linking(code), writing.state.value)
        assertNull(writing.claim())
    }

    @Test
    fun aClaimWhoseAnswerWasLostIsSentAgainOnTheNextVisit() {
        written()
        writing.claim()
        writing.enter()

        assertEquals(WriteState.Written(code), writing.state.value)
        assertEquals(code, writing.claim())
    }

    @Test
    fun aNewVisitStartsOverLikeReset() {
        confirm()
        writing.enter()

        assertEquals(WriteState.Connect, writing.state.value)
    }

    @Test
    fun aSuccessAfterTheRefusalOfADuplicateClaimStillLinks() {
        written()
        writing.claim()
        writing.claimFailed(R.string.add_refused)
        writing.linked()

        assertEquals(WriteState.Linked(code.shortName), writing.state.value)
    }

    @Test
    fun startingOverKeepsAPreparationAndAnUnlinkedCode() {
        confirm()
        writing.reset()
        assertEquals(WriteState.Connect, writing.state.value)

        prepare = { _, _ ->
            writing.reset()
            assertIs<WriteState.Writing>(writing.state.value)
            code
        }
        written()
        writing.reset()
        assertIs<WriteState.Written>(writing.state.value)
    }

    @Test
    fun anAnswerFromThePickerAfterLeavingChangesNothing() {
        writing.look(listOf(card))
        writing.open(card)
        writing.reset()
        writing.opened(TREE)

        assertEquals(WriteState.Connect, writing.state.value)
    }

    @Test
    fun theStateNeverPrintsTheCode() {
        assertFalse(code.value in WriteState.Written(code).toString())
        assertFalse(code.value in WriteState.Linking(code).toString())
    }
}
