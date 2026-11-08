package no.cyberfish.dekoi.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DekoiWatchingTest {
    private val dwells = listOf(4000, 0, 2000)

    @Test
    fun `the light stays on each row for its dwell, in order, and passes rows without one`() {
        assertEquals(Light(0, 0f), lightAt(dwells, 0f))
        assertEquals(Light(0, 0.5f), lightAt(dwells, 2000f))
        assertEquals(Light(2, 0f), lightAt(dwells, 4000f))
        assertEquals(Light(2, 0.75f), lightAt(dwells, 5500f))
    }

    @Test
    fun `the light starts its round again at the first row`() {
        assertEquals(Light(0, 0f), lightAt(dwells, 6000f))
    }

    @Test
    fun `no light without a row to visit`() {
        assertNull(lightAt(listOf(0, 0), 100f))
        assertNull(lightAt(emptyList(), 0f))
    }
}
