package no.cyberfish.dekoi

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging

class DekoiApplication : Application() {
    val backend: Backend by lazy { Backend(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_KEY) }

    /** Preparing a card outlives the screen that starts it. */
    val cardWriting = CardWriting({ confirmed, onProgress -> prepareCard(this, confirmed, onProgress) })

    /** False in debug builds made without the Firebase settings. */
    var pushEnabled = false
        private set

    override fun onCreate() {
        super.onCreate()
        // Importance only applies when a channel is first created; the owner's
        // later choices are kept.
        val channels =
            listOf(
                NotificationChannel(URGENT_CHANNEL, getString(R.string.notification_channel_urgent), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(ALERT_CHANNEL, getString(R.string.notification_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(UPDATES_CHANNEL, getString(R.string.notification_channel_updates), NotificationManager.IMPORTANCE_LOW),
            ).onEach { it.lockscreenVisibility = Notification.VISIBILITY_PRIVATE }
        getSystemService(NotificationManager::class.java).createNotificationChannels(channels)

        val options =
            firebaseOptions(
                BuildConfig.FIREBASE_PROJECT_ID,
                BuildConfig.FIREBASE_APP_ID,
                BuildConfig.FIREBASE_API_KEY,
                BuildConfig.FIREBASE_SENDER_ID,
            ) ?: return
        FirebaseApp.initializeApp(this, options)
        pushEnabled = true
    }

    /**
     * This phone's FCM registration token, or null when push is off.
     * firebase-messaging 26 deprecates tokens in favour of registration by
     * Firebase installation ID (register() and onRegistered()). The notify
     * function sends to tokens; both sides move together in a later change.
     */
    @Suppress("DEPRECATION")
    suspend fun pushToken(): String? = if (pushEnabled) FirebaseMessaging.getInstance().token.await() else null
}
