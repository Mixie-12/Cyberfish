package no.cyberfish.dekoi.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

/**
 * The snapshot the app last wrote, in app-private storage. The widget reads
 * only this; it never sees the session or talks to the backend.
 */
class WidgetStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Resource ids, and this format, hold only for the install that wrote
    // them, so the key names that install and a write drops older keys.
    @Suppress("DEPRECATION") // The flags overload starts at API 33; minSdk is 29.
    private val key = "snapshot-" + context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    fun read(): WidgetSnapshot? = preferences.getString(key, null)?.let { Json.decodeFromString<WidgetSnapshot>(it) }

    /** Null removes the snapshot, so the widget shows nothing about the network. */
    fun write(snapshot: WidgetSnapshot?) {
        val editor = preferences.edit().clear()
        snapshot?.let { editor.putString(key, Json.encodeToString(it)) }
        editor.apply()
    }

    /** The snapshot now and after every write. */
    fun snapshots(): Flow<WidgetSnapshot?> =
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            send(read())
            awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
        }

    private companion object {
        const val FILE = "widget"
    }
}
