package no.cyberfish.dekoi

import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Must match ALERT_CHANNEL in backend/supabase/functions/notify/notify.ts. */
const val ALERT_CHANNEL = "alerts"

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
