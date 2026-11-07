package no.cyberfish.dekoi

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OwnerSettingsTest {
    @Test
    fun `the weekly summary is on until the owner turns it off`() {
        assertTrue(OwnerSettings().weeklySummary)
        assertEquals(OwnerSettings(weeklySummary = false), Json.decodeFromString<OwnerSettings>("""{"weekly_summary": false}"""))
        assertEquals(OwnerSettings(weeklySummary = true), Json.decodeFromString<OwnerSettings>("""{"weekly_summary": true}"""))
    }
}
