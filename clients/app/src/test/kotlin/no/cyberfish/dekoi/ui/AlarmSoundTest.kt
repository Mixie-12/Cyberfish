package no.cyberfish.dekoi.ui

import android.media.AudioManager
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.ThreatLevel
import no.cyberfish.dekoi.isAlarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AlarmSoundTest {
    private val ring = AudioManager.RINGER_MODE_NORMAL

    @Test
    fun `only a critical alert that is very likely an attack sounds`() {
        val sounding =
            Severity.entries.flatMap { severity ->
                ThreatLevel.entries.mapNotNull { level ->
                    (severity to level).takeIf { alarmSounds(isAlarm(severity, level), shown = false, soundOn = true, ringerMode = ring) }
                }
            }
        assertEquals(listOf(Severity.CRITICAL to ThreatLevel.LIKELY), sounding)
    }

    @Test
    fun `it sounds once per opening`() {
        var shown = false
        val played =
            List(3) {
                alarmSounds(alarm = true, shown = shown, soundOn = true, ringerMode = ring).also { shown = true }
            }
        assertEquals(listOf(true, false, false), played)
    }

    @Test
    fun `the owner can turn it off`() {
        assertFalse(alarmSounds(alarm = true, shown = false, soundOn = false, ringerMode = ring))
    }

    @Test
    fun `silent and vibrate keep it quiet`() {
        listOf(AudioManager.RINGER_MODE_SILENT, AudioManager.RINGER_MODE_VIBRATE).forEach {
            assertFalse(alarmSounds(alarm = true, shown = false, soundOn = true, ringerMode = it))
        }
    }
}
