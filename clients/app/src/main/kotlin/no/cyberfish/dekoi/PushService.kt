package no.cyberfish.dekoi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives FCM tokens and messages. While the app is in the background, the
 * system shows notification messages itself and puts the data (event_id) into
 * the launch intent; this class shows them while the app is open.
 */
class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Deprecated("FCM registration tokens; see DekoiApplication.pushToken.")
    override fun onNewToken(token: String) {
        val backend = (application as DekoiApplication).backend
        if (backend.session.value !is SessionStatus.Authenticated) return
        scope.launch {
            try {
                backend.registerPushToken(token)
            } catch (_: RestException) {
                // The app registers the current token again each time it opens signed in.
            } catch (_: HttpRequestException) {
                // As above.
            } catch (_: HttpRequestTimeoutException) {
                // As above.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val content = message.notification ?: return
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        val eventId = eventIdFrom(message.data[EVENT_ID_EXTRA])
        val open =
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .apply { eventId?.let { putExtra(EVENT_ID_EXTRA, it.toString()) } }
        val notification =
            Notification
                .Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_tab_activity)
                .setContentTitle(content.title)
                .setContentText(content.body)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this,
                        eventId?.toInt() ?: 0,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).build()
        manager.notify(eventId?.toInt() ?: 0, notification)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
