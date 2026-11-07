package no.cyberfish.dekoi.ui

import android.text.format.DateUtils
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "5 minutes ago", in the phone's language. */
fun relativeTime(
    time: Instant,
    now: Instant,
): String =
    DateUtils
        .getRelativeTimeSpanString(time.toEpochMilli(), now.toEpochMilli(), DateUtils.MINUTE_IN_MILLIS)
        .toString()

/** Date and time in the phone's format and time zone. */
fun absoluteTime(time: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(time)
