package no.cyberfish.dekoi

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Channel ids are shared with backend/supabase/functions/notify/notify.ts,
// which picks one by severity (ADR 0011). Never rename one: users' channel
// settings are stored under the id.
const val URGENT_CHANNEL = "alerts_urgent"
const val ALERT_CHANNEL = "alerts"
const val UPDATES_CHANNEL = "updates"

/** The channel a message asked for, if this app knows it. */
fun channelFor(requested: String?): String = requested?.takeIf { it in CHANNELS } ?: ALERT_CHANNEL

private val CHANNELS = setOf(URGENT_CHANNEL, ALERT_CHANNEL, UPDATES_CHANNEL)

/** Must match the tag notify.ts sets, so opening an alert in the app can clear its notification. */
fun notificationTag(eventId: Long): String = "event-$eventId"

/** Clears the notification of an alert the owner has now seen in the app. */
fun cancelAlertNotification(
    context: Context,
    eventId: Long,
) {
    context.getSystemService(NotificationManager::class.java).cancel(notificationTag(eventId), 0)
}

/** The only field of a push payload; see the notify Edge Function. */
const val EVENT_ID_EXTRA = "event_id"

/** Firebase options from the build settings, or null when push is off (a debug build without them). */
fun firebaseOptions(
    projectId: String,
    appId: String,
    apiKey: String,
    senderId: String,
): FirebaseOptions? {
    if (listOf(projectId, appId, apiKey, senderId).any { it.isBlank() }) return null
    return FirebaseOptions
        .Builder()
        .setProjectId(projectId)
        .setApplicationId(appId)
        .setApiKey(apiKey)
        .setGcmSenderId(senderId)
        .build()
}

/** The event a notification points at. Any app can start MainActivity with extras, so only a positive id counts. */
fun eventIdFrom(extra: String?): Long? = extra?.toLongOrNull()?.takeIf { it > 0 }

/** Ask once, after the first DeKoi is linked; a refusal is final. Only Android 13 and later ask at all. */
fun shouldAskForNotifications(
    sdk: Int,
    granted: Boolean,
    askedBefore: Boolean,
    hasDevice: Boolean,
): Boolean = sdk >= Build.VERSION_CODES.TIRAMISU && !granted && !askedBefore && hasDevice

suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            when {
                error != null -> continuation.resumeWithException(error)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resume(task.result)
            }
        }
    }
