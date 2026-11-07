package no.cyberfish.dekoi

import java.time.Duration
import java.time.Instant

/** The longest name and room the backend stores for a host, in characters (ADR 0013). */
const val LABEL_MAX = 40
const val ROOM_MAX = 32

/**
 * What the owner typed, as the backend accepts it: no control characters,
 * at most [max] characters, without cutting a character in half.
 */
fun ownerText(
    typed: String,
    max: Int,
): String {
    val kept = typed.filterNot { it.isISOControl() }
    val end = kept.offsetByCodePoints(0, minOf(max, kept.codePointCount(0, kept.length)))
    return kept.substring(0, end)
}

/** [ownerText] when saving: surrounding spaces dropped, and nothing left means none. */
fun savedOwnerText(
    typed: String,
    max: Int,
): String? = ownerText(typed, max).trim().ifEmpty { null }

/** A host first seen within this window counts as new (docs/design.md, "Network"). */
private val NEW_WINDOW = Duration.ofDays(7)

// The agent sweeps the network every 15 minutes, and a sleeping phone may
// not answer a few sweeps in a row.
private val SEEN_RECENTLY = Duration.ofHours(1)

fun NetworkHost.isNew(now: Instant): Boolean = Duration.between(firstSeenAt, now) < NEW_WINDOW

fun NetworkHost.isOnline(now: Instant): Boolean = Duration.between(lastSeenAt, now) < SEEN_RECENTLY

/** The most serious alert each host caused in the last 24 hours, by hardware address; known hosts are left out. */
fun attentionByMac(
    events: List<Event>,
    trustedMacs: Set<String>,
    now: Instant,
): Map<String, Severity> =
    buildMap {
        for (event in recentIntrusions(events, trustedMacs, now)) {
            val mac = event.srcMac ?: continue
            // Severity lists the most serious first.
            merge(mac, event.severity, ::minOf)
        }
    }
