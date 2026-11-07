package no.cyberfish.dekoi

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.shared.CardContent
import no.cyberfish.dekoi.shared.ClaimCode
import no.cyberfish.dekoi.shared.CloudInit
import no.cyberfish.dekoi.shared.Image
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import no.cyberfish.dekoi.shared.Network as CardNetwork

/**
 * Writes the card the owner confirmed (ADR 0014, 0017), in the foreground so
 * that it goes on while they use other apps. Type connectedDevice: it drives
 * a USB device the owner allowed through UsbManager.requestPermission.
 * Started with startService from the screen, so that a reader unplugged
 * before the start ends in a failure the owner sees, not in a crash for a
 * foreground start that never came.
 */
class WriteCardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val writing = (application as DekoiApplication).cardWriting
        // A repeated start (a second tap) finds nothing confirmed and leaves the running write alone.
        val card = writing.erase()
        if (card == null) {
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, notification(R.string.write_notification_writing, ongoing = true), TYPE)
        } catch (error: SecurityException) {
            // Android allows the type only while DeKoi may use a connected USB device: the reader is gone.
            return stop(card, writing, error)
        } catch (error: IllegalStateException) {
            // DeKoi went to the background in the moment between the tap and this.
            return stop(card, writing, error)
        }
        job = scope.launch { card.use { write(it, writing) } }
        return START_NOT_STICKY
    }

    private fun stop(
        card: OpenCard,
        writing: CardWriting,
        error: RuntimeException,
    ): Int {
        Log.w(CARD_LOG_TAG, "Writing could not start", error)
        card.close()
        writing.failed(WriteFailure.CARD_ERROR, error)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun write(
        card: OpenCard,
        writing: CardWriting,
    ) {
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dekoi:write-card")
        // Not counted, so releasing a lock that already timed out does nothing.
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(WAKE_LOCK_MS)
        try {
            writing.written(writeCard(card, CardContent.image, writing))
            finish(R.string.write_notification_written)
        } catch (error: IOException) {
            Log.w(CARD_LOG_TAG, "Writing the card failed", error)
            writing.failed(downloadFailure(error), error)
            finish(R.string.write_notification_failed)
        } finally {
            wakeLock.release()
        }
    }

    /** Writes and verifies the image and the cloud-init files; returns the code that is on the card. */
    private fun writeCard(
        card: OpenCard,
        image: Image,
        writing: CardWriting,
    ): ClaimCode {
        val code = ClaimCode.generate()
        download(image) { stream ->
            writeImage(stream, image, card.device) { downloaded, written -> writing.progress(WriteState.Writing(downloaded, written)) }
        }
        verifyImage(card.device, image) { checked ->
            writing.progress(WriteState.Writing(image.downloadSize, image.extractSize, checked))
        }
        val instanceId = CloudInit.instanceId(System.currentTimeMillis())
        val commandLine = readBootFile(card.device, "cmdline.txt")
        writeBootFiles(
            card.device,
            mapOf(
                "user-data" to CardContent.userData(code),
                "network-config" to CloudInit.networkConfig(CardNetwork.Cable),
                "meta-data" to CloudInit.metaData(instanceId),
                "cmdline.txt" to CloudInit.commandLine(commandLine, instanceId),
            ),
        )
        return code
    }

    /** Opens the pinned image over HTTPS and hands its bytes, exactly as published, to [consume]. */
    private fun download(
        image: Image,
        consume: (InputStream) -> Unit,
    ) {
        val connection = URI(image.url).toURL().openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        // No transparent decompression: the hash covers the file as published.
        connection.setRequestProperty("Accept-Encoding", "identity")
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val length = connection.contentLengthLong
            if (length != -1L && length != image.downloadSize) throw WriteFailed(WriteFailure.DOWNLOAD_DAMAGED)
            connection.inputStream.use(consume)
        } finally {
            connection.disconnect()
        }
    }

    /** Leaves a notification that says how it ended, and stops. */
    private fun finish(
        @StringRes text: Int,
    ) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text, ongoing = false))
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun notification(
        @StringRes text: Int,
        ongoing: Boolean,
    ): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification
            .Builder(this, UPDATES_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.write_notification_title))
            .setContentText(getString(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .apply { if (ongoing) setProgress(0, 0, true) }
            .build()
    }

    private companion object {
        // startForeground refuses 0; alerts use 0 with a tag (PushService).
        const val NOTIFICATION_ID = 1
        const val TYPE = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        const val TIMEOUT_MS = 60_000

        // Longer than any real write; the lock is released as soon as it ends.
        const val WAKE_LOCK_MS = 2 * 60 * 60 * 1000L
    }
}
