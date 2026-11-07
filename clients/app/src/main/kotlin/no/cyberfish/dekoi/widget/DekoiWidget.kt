package no.cyberfish.dekoi.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.cyberfish.dekoi.EVENT_ID_EXTRA
import no.cyberfish.dekoi.MainActivity
import no.cyberfish.dekoi.Protection
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.OwnerData
import no.cyberfish.dekoi.ui.relativeTime
import java.time.Instant

private val BACKGROUND = Color(DesignTokens.BACKGROUND)
private val BLUE = Color(DesignTokens.BLUE)
private val WHITE = Color(DesignTokens.WHITE)
private val TEXT = Color(DesignTokens.TEXT)
private val MID_GREY = Color(DesignTokens.MID_GREY)

// A hairline, not a fill: blue stays an accent.
private const val HAIRLINE_ALPHA = 0.4f

// 2x2 and 4x2 on a typical launcher grid; the latest alert's title needs the wide one.
private val SMALL = DpSize(110.dp, 110.dp)
private val WIDE = DpSize(250.dp, 110.dp)

/** Writes what the widget shows for [data] (nothing until it has loaded) and redraws every placed widget. */
suspend fun publishWidget(
    context: Context,
    data: OwnerData,
) = withContext(Dispatchers.IO) {
    WidgetStore(context).write(widgetSnapshot(data))
    DekoiWidget().updateAll(context)
}

class DekoiWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DekoiWidget()
}

/** Protection at a glance, from the snapshot the app last wrote (WidgetStore). */
class DekoiWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val store = WidgetStore(context)
        val initial = store.read()
        provideContent {
            // An update does not restart provideGlance while it runs, so the snapshot is observed.
            val snapshot by remember { store.snapshots() }.collectAsState(initial)
            Content(snapshot, Instant.now())
        }
    }
}

@Composable
private fun Content(
    snapshot: WidgetSnapshot?,
    now: Instant,
) {
    val context = LocalContext.current
    Column(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(BACKGROUND)
            .cornerRadius(16.dp)
            .padding(14.dp)
            .clickable(actionStartActivity(openIntent(context, snapshot?.latestEventId))),
    ) {
        // Without a size the frame Glance puts around RemoteViews fills the column.
        AndroidRemoteViews(header(context), GlanceModifier.fillMaxWidth().wrapContentHeight())
        Spacer(GlanceModifier.height(8.dp))
        Box(GlanceModifier.fillMaxWidth().height(1.dp).background(BLUE.copy(alpha = HAIRLINE_ALPHA))) {}
        Spacer(GlanceModifier.height(10.dp))
        if (snapshot == null) {
            Line(context.getString(R.string.widget_signed_out), TEXT, 12.sp, maxLines = 4)
        } else {
            Status(context, snapshot, now)
        }
    }
}

@Composable
private fun ColumnScope.Status(
    context: Context,
    snapshot: WidgetSnapshot,
    now: Instant,
) {
    Line(context.getString(stateWord(snapshot.protection)), stateColor(snapshot), 16.sp, maxLines = 2, bold = true)
    val alerts =
        if (snapshot.alerts == 0) {
            context.getString(R.string.widget_no_alerts)
        } else {
            context.resources.getQuantityString(R.plurals.tab_alerts_badge, snapshot.alerts, snapshot.alerts)
        }
    Line(alerts, TEXT, 12.sp)
    val title = snapshot.latestTitle
    if (title != null && LocalSize.current.width >= WIDE.width) {
        val subject = snapshot.latestSubject
        Line(if (subject != null) context.getString(title, context.getString(subject)) else context.getString(title), TEXT, 12.sp)
    }
    Spacer(GlanceModifier.defaultWeight())
    Line(context.getString(R.string.updated_ago, relativeTime(snapshot.updatedAt, now)), MID_GREY, 11.sp, maxLines = 2)
}

@Composable
private fun Line(
    text: String,
    color: Color,
    size: TextUnit,
    maxLines: Int = 1,
    bold: Boolean = false,
) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Text(text, style = TextStyle(ColorProvider(color), size, weight, fontFamily = FontFamily.Monospace), maxLines = maxLines)
}

/** The mark and the label: Glance text has no letter spacing, a platform TextView does. */
private fun header(context: Context): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_header).apply {
        setTextColor(R.id.widget_mark, WHITE.toArgb())
        setTextColor(R.id.widget_label, BLUE.toArgb())
    }

/** The app, or the newest alert over the Alerts tab, as a tapped notification opens it. */
private fun openIntent(
    context: Context,
    eventId: Long?,
): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        .apply { eventId?.let { putExtra(EVENT_ID_EXTRA, it.toString()) } }

@StringRes
private fun stateWord(protection: Protection): Int =
    when (protection) {
        Protection.PROTECTED -> R.string.widget_protected
        Protection.ATTENTION -> R.string.widget_attention
        Protection.OFFLINE -> R.string.widget_offline
        Protection.SETTING_UP -> R.string.widget_setting_up
        Protection.NO_DEVICE -> R.string.widget_no_device
    }

/** As on Home: the state's colour, and for attention the most serious recent alert's. */
private fun stateColor(snapshot: WidgetSnapshot): Color =
    when (snapshot.protection) {
        Protection.PROTECTED -> Color(DesignTokens.SAFE.color)
        Protection.ATTENTION -> Color(severityTone(snapshot.highest ?: Severity.WARNING).color)
        Protection.OFFLINE -> Color(DesignTokens.OFFLINE)
        Protection.SETTING_UP, Protection.NO_DEVICE -> BLUE
    }

private fun severityTone(severity: Severity): DesignTokens.Tone =
    when (severity) {
        Severity.CRITICAL -> DesignTokens.CRITICAL
        Severity.WARNING -> DesignTokens.WARNING
        Severity.INFO -> DesignTokens.INFO
    }
