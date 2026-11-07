package no.cyberfish.dekoi.widget

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.TimestampSerializer
import no.cyberfish.dekoi.alertsToLookAt
import no.cyberfish.dekoi.eventText
import no.cyberfish.dekoi.highestSeverity
import no.cyberfish.dekoi.protection
import no.cyberfish.dekoi.recentIntrusions
import no.cyberfish.dekoi.subject
import no.cyberfish.dekoi.ui.OwnerData
import java.time.Instant

/**
 * What the home-screen widget shows, worked out by the app from the data it
 * loaded. Only states, counts and the app's own wording: nothing a host on
 * the network could have written.
 */
@Serializable
data class WidgetSnapshot(
    val protection: Protection,
    /** The most serious recent intrusion; it colours the attention state, as on Home. */
    val highest: Severity?,
    /** The Alerts tab badge. */
    val alerts: Int,
    /** The newest of those alerts: its kind-based title, the subject the title names, and its id for the tap to open. */
    @param:StringRes val latestTitle: Int?,
    @param:StringRes val latestSubject: Int?,
    val latestEventId: Long?,
    @Serializable(TimestampSerializer::class) val updatedAt: Instant,
)

/** The snapshot for [data] as of its last refresh, or null until it has loaded. */
fun widgetSnapshot(data: OwnerData): WidgetSnapshot? {
    val now = data.updatedAt ?: return null
    val trusted = data.trustedMacs
    val alerts = alertsToLookAt(data.events, trusted)
    val latest = alerts.firstOrNull()?.lead
    val text = latest?.let(::eventText)
    // A title about another device names what it looks like, as in the app.
    val source = latest?.takeIf { text?.aboutSource == true }?.let { data.hostFor(it.srcMac)?.kind ?: HostKind.UNKNOWN }
    return WidgetSnapshot(
        protection = protection(data.devices, data.events, trusted, now),
        highest = highestSeverity(recentIntrusions(data.events, trusted, now)),
        alerts = alerts.size,
        latestTitle = text?.title,
        latestSubject = source?.let(::subject),
        latestEventId = latest?.id,
        updatedAt = now,
    )
}
