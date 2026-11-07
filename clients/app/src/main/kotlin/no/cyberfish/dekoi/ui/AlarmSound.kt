package no.cyberfish.dekoi.ui

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.core.content.edit
import kotlinx.coroutines.suspendCancellableCoroutine
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.motion.rememberHaptics
import kotlin.coroutines.resume

private const val PREFERENCES = "alarm"
private const val SOUND = "sound"

/**
 * Whether the alarm sound plays (ADR 0028): as the hero of an [alarm] first
 * shows on this opening of its screen, while the owner keeps the sound on
 * and the phone is set to ring, not to vibrate or silent.
 */
fun alarmSounds(
    alarm: Boolean,
    shown: Boolean,
    soundOn: Boolean,
    ringerMode: Int,
): Boolean = alarm && !shown && soundOn && ringerMode == AudioManager.RINGER_MODE_NORMAL

/**
 * Plays the alarm sound with the firm click of [no.cyberfish.dekoi.ui.motion.CriticalAlertHaptic],
 * once per opening; coming back from a screen pushed on top does not play it
 * again, and leaving the screen stops it.
 */
@Composable
fun CriticalAlertSound(alarm: Boolean) {
    val context = LocalContext.current
    var shown by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(alarm) {
        val audio = context.getSystemService(AudioManager::class.java)
        val sound = alarmSounds(alarm, shown, soundOn(preferences(context)), audio.ringerMode)
        shown = shown || alarm
        if (sound) play(context, audio)
    }
}

/**
 * A sound that asks for attention, like a reminder: it follows the
 * notification volume and Do Not Disturb, where an alarm clock's would not.
 * Music playing meanwhile is lowered, not stopped; during a call there is no
 * focus and no sound.
 */
private suspend fun play(
    context: Context,
    audio: AudioManager,
) {
    val attributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
    val player = MediaPlayer.create(context, R.raw.alarm_critical, attributes, audio.generateAudioSessionId()) ?: return
    val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attributes).build()
    try {
        if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        // Without an error listener, a playback error also ends in completion.
        suspendCancellableCoroutine { done ->
            player.setOnCompletionListener { done.resume(Unit) }
            player.start()
        }
    } finally {
        audio.abandonAudioFocusRequest(focus)
        player.release()
    }
}

/** The owner's choice, kept on this phone. */
@Composable
fun AlarmSoundSection() {
    val context = LocalContext.current
    val preferences = remember { preferences(context) }
    var on by remember { mutableStateOf(soundOn(preferences)) }
    val haptics = rememberHaptics()
    InsetSection(footer = stringResource(R.string.settings_alarm_sound_footer)) {
        InsetRow(
            stringResource(R.string.settings_alarm_sound),
            modifier =
                Modifier.toggleable(on, role = Role.Switch) {
                    haptics.toggle(it)
                    preferences.edit { putBoolean(SOUND, it) }
                    on = it
                },
            trailing = { Switch(checked = on, onCheckedChange = null) },
        )
    }
}

private fun preferences(context: Context): SharedPreferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

/** On until the owner turns it off. */
private fun soundOn(preferences: SharedPreferences): Boolean = preferences.getBoolean(SOUND, true)
