package no.cyberfish.dekoi.burner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import no.cyberfish.dekoi.shared.ClaimCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupTest {
    private val drive = Drive("\\\\.\\PhysicalDrive2", "SDXC Card", 63864569856)
    private val wifi = Network.WiFi("Home", "password", "NO")

    // Nothing here starts a coroutine; write() is never called, as it would start Imager.
    private fun setup() = Setup(CoroutineScope(Job()))

    @Test
    fun `a new window starts on the welcome screen`() {
        assertEquals(Screen.Welcome, setup().screen.value)
    }

    @Test
    fun `the steps are numbered in the order of the flow`() {
        val labels =
            listOf("01 / CHOOSE THE CARD", "02 / CHOOSE THE NETWORK", "03 / CONFIRM", "04 / WRITE THE CARD", "05 / ADD IT TO THE APP")
        assertEquals(labels, Step.entries.map { it.label })
    }

    @Test
    fun `every screen shows the step it belongs to`() {
        val expected =
            mapOf(
                Screen.Welcome to null,
                Screen.NotWindows to null,
                Screen.NoImager(installFailed = true) to null,
                Screen.Installing to null,
                Screen.Looking to Step.Card,
                Screen.ChooseCard(listOf(drive)) to Step.Card,
                Screen.ListFailed("Get-Disk exited with 1") to Step.Card,
                Screen.ChooseNetwork(drive, Network.Cable) to Step.Connection,
                Screen.Confirm(drive, wifi) to Step.Confirm,
                Screen.Writing to Step.Write,
                Screen.WriteFailed("Raspberry Pi Imager exit code 1") to Step.Write,
                Screen.Done(ClaimCode.generate(), cable = true) to Step.Link,
            )
        for ((screen, step) in expected) assertEquals(step, screen.step, screen.toString())
    }

    @Test
    fun `cable is chosen until the owner picks Wi-Fi, and going back keeps the Wi-Fi`() {
        val setup = setup()
        setup.choose(drive)
        assertEquals(Screen.ChooseNetwork(drive, Network.Cable), setup.screen.value)

        setup.confirm(drive, wifi)
        assertEquals(Screen.Confirm(drive, wifi), setup.screen.value)
        setup.choose(drive, wifi)
        assertEquals(Screen.ChooseNetwork(drive, wifi), setup.screen.value)
    }

    @Test
    fun `the next steps name the DeKoi and ask for the cable only when it uses one`() {
        val code = ClaimCode.generate()
        val cable = nextSteps(code, cable = true)
        val wireless = nextSteps(code, cable = false)

        for (steps in listOf(cable, wireless)) {
            assertTrue(steps.first().contains("Add DeKoi"))
            assertTrue(steps.any { code.shortName in it })
        }
        assertTrue(cable.last().contains("network cable"))
        assertFalse(wireless.any { "cable" in it })
    }
}
