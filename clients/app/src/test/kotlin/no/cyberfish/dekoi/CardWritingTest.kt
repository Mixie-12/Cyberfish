package no.cyberfish.dekoi

import me.jahnen.libaums.core.driver.BlockDeviceDriver
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class SizedCard(
    override val blocks: Long,
    override val blockSize: Int = 512,
) : BlockDeviceDriver {
    override fun init() = Unit

    override fun read(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) = error("Not read in these tests")

    override fun write(
        deviceOffset: Long,
        buffer: ByteBuffer,
    ) = error("Not written in these tests")
}

private class Connection : Closeable {
    var closed = 0

    override fun close() {
        closed++
    }
}

class CardWritingTest {
    private val writing = CardWriting(CardContent.image)
    private val reader = Reader("/dev/bus/usb/001/002", "Generic Card Reader")
    private val code = checkNotNull(ClaimCode.parse("0123456789ABCDEFGHJKMNPQRS"))

    private fun card(
        bytes: Long = 31_914_983_424,
        connection: Connection = Connection(),
        blockSize: Int = 512,
    ) = OpenCard(reader.name, SizedCard(bytes / blockSize, blockSize), connection)

    private fun confirm(card: OpenCard = card()): OpenCard {
        writing.look(listOf(reader))
        assertTrue(writing.opening())
        writing.opened(card)
        return card
    }

    private fun written() {
        confirm()
        writing.erase()
        writing.written(code)
    }

    @Test
    fun aCardGoesFromConnectToLinked() {
        assertEquals(WriteState.Connect, writing.state.value)
        writing.look(emptyList())
        assertEquals(WriteState.Choose(emptyList()), writing.state.value)
        val card = confirm()
        assertEquals(WriteState.Confirm(reader.name, 31_914_983_424), writing.state.value)

        assertSame(card, writing.erase())
        writing.progress(WriteState.Writing(10, 20, 0))
        assertEquals(WriteState.Writing(10, 20, 0), writing.state.value)
        writing.written(code)
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
    fun onlyAConfirmedCardIsHandedOverOnce() {
        assertNull(writing.erase())
        confirm()
        writing.erase()
        assertNull(writing.erase())
    }

    @Test
    fun anUnsuitableCardIsRefusedAndClosed() {
        val connection = Connection()
        confirm(card(bytes = 3_965_190_144, connection = connection))

        assertEquals(WriteState.Failed(WriteFailure.UNSUITABLE, 3_965_190_144), writing.state.value)
        assertEquals(1, connection.closed)
        assertNull(writing.erase())
    }

    @Test
    fun aCardWithLargeSectorsIsRefused() {
        confirm(card(blockSize = 4096))

        assertIs<WriteState.Failed>(writing.state.value)
        assertNull(writing.erase())
    }

    @Test
    fun aCardOpenedTooLateIsClosed() {
        val connection = Connection()
        writing.opened(card(connection = connection))

        assertEquals(WriteState.Connect, writing.state.value)
        assertEquals(1, connection.closed)
    }

    @Test
    fun aSecondTapOpensNothing() {
        writing.look(listOf(reader))
        assertTrue(writing.opening())
        assertFalse(writing.opening())
    }

    @Test
    fun aReaderPluggedInLaterDoesNotHideAFailureOrAConfirmation() {
        confirm()
        writing.look(emptyList())
        assertIs<WriteState.Confirm>(writing.state.value)

        writing.failed(WriteFailure.CARD_ERROR)
        writing.look(listOf(reader))
        assertEquals(WriteState.Failed(WriteFailure.CARD_ERROR), writing.state.value)
    }

    @Test
    fun lateCallsAfterAFailureChangeNothing() {
        confirm()
        writing.erase()
        writing.failed(WriteFailure.NETWORK)

        writing.progress(WriteState.Writing(1, 2, 3))
        writing.written(code)
        assertNull(writing.claim())
        writing.linked()

        assertEquals(WriteState.Failed(WriteFailure.NETWORK), writing.state.value)
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
        val connection = Connection()
        confirm(card(connection = connection))
        writing.enter()

        assertEquals(WriteState.Connect, writing.state.value)
        assertEquals(1, connection.closed)
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
    fun startingOverClosesAnOpenCardButKeepsAWriteAndAnUnlinkedCode() {
        val connection = Connection()
        confirm(card(connection = connection))
        writing.reset()
        assertEquals(WriteState.Connect, writing.state.value)
        assertEquals(1, connection.closed)

        confirm()
        writing.erase()
        writing.reset()
        assertIs<WriteState.Writing>(writing.state.value)
        writing.written(code)
        writing.reset()
        assertIs<WriteState.Written>(writing.state.value)
    }

    @Test
    fun aFailureWhileConfirmingClosesTheCard() {
        val connection = Connection()
        confirm(card(connection = connection))
        writing.failed(WriteFailure.CARD_ERROR)

        assertEquals(WriteState.Failed(WriteFailure.CARD_ERROR), writing.state.value)
        assertEquals(1, connection.closed)
    }

    @Test
    fun theStateNeverPrintsTheCode() {
        assertFalse(code.value in WriteState.Written(code).toString())
        assertFalse(code.value in WriteState.Linking(code).toString())
    }
}
