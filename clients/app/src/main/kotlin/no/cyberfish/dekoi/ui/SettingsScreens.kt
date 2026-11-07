package no.cyberfish.dekoi.ui

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.annotation.RawRes
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.toUpperCase
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.LifecycleResumeEffect
import no.cyberfish.dekoi.ALERT_CHANNEL
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.UPDATES_CHANNEL
import no.cyberfish.dekoi.URGENT_CHANNEL
import no.cyberfish.dekoi.status
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.ConfirmDialog
import no.cyberfish.dekoi.ui.components.InsetRow
import no.cyberfish.dekoi.ui.components.InsetSection
import no.cyberfish.dekoi.ui.components.KeyValueRow
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.motion.rememberHaptics
import java.time.Instant

@Composable
fun SettingsScreen(
    email: String?,
    devices: List<Device>,
    listState: LazyListState,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    onNavigate: (Route) -> Unit,
    weeklySummary: Boolean?,
    onWeeklySummary: (Boolean) -> Unit,
    onSetWifiName: (Device, String?) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val haptics = rememberHaptics()
    val now = Instant.now()
    Screen(stringResource(R.string.tab_settings), listState = listState) {
        item {
            InsetSection(title = stringResource(R.string.settings_account_title)) {
                InsetRow(email.orEmpty(), subtitle = stringResource(R.string.settings_account))
                InsetRow(
                    stringResource(R.string.settings_sign_out),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = onSignOut,
                    chevron = false,
                )
            }
        }
        item {
            InsetSection(title = stringResource(R.string.settings_devices)) {
                devices.forEach { device ->
                    InsetRow(
                        device.name,
                        subtitle = stringResource(statusWord(device.status(now))),
                        leading = { BrandTile() },
                        onClick = { onNavigate(Route.Dekoi(device.id)) },
                    )
                }
                AddDekoiRow(R.string.action_add_dekoi) { onNavigate(Route.AddDekoi) }
            }
        }
        if (devices.isNotEmpty()) item { WifiNameSection(devices, now, onSetWifiName) }
        item { Notifications() }
        item { WeeklySummary(weeklySummary, onWeeklySummary) }
        item { Privacy() }
        item {
            InsetSection(title = stringResource(R.string.settings_about)) {
                KeyValueRow(stringResource(R.string.settings_version), BuildConfig.VERSION_NAME)
                InsetRow(stringResource(R.string.features_title), onClick = { onNavigate(Route.Features()) })
                InsetRow(stringResource(R.string.learn_title), onClick = { onNavigate(Route.Learn) })
                InsetRow(stringResource(R.string.action_take_tour), onClick = { onNavigate(Route.Onboarding) })
                InsetRow(stringResource(R.string.licences_title), onClick = { onNavigate(Route.Licences) })
            }
        }
        item {
            InsetSection(footer = stringResource(R.string.settings_delete_footer)) {
                InsetRow(
                    stringResource(R.string.settings_delete),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirming = true },
                    chevron = false,
                )
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = R.string.settings_delete_confirm_title,
            body = R.string.settings_delete_confirm_body,
            confirm = R.string.action_delete,
            onConfirm = {
                haptics.confirm()
                confirming = false
                onDeleteAccount()
            },
            onDismiss = { confirming = false },
        )
    }
}

/** A row of text that wraps freely, for sentences an InsetRow would cut short at large font sizes. */
@Composable
private fun ProseRow(
    text: String,
    color: Color = Color.Unspecified,
) {
    val separator = MaterialTheme.colorScheme.outlineVariant
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier =
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val width = Dimens.hairline.toPx()
                    drawLine(separator, Offset(0f, width / 2), Offset(size.width, width / 2), width)
                }.padding(vertical = Dimens.spaceM),
    )
}

/** What the backend keeps about an owner (backend/supabase/migrations, ADR 0015 for the public address). */
private val stored =
    listOf(
        R.string.settings_privacy_email,
        R.string.settings_privacy_hosts,
        R.string.settings_privacy_makers,
        R.string.settings_privacy_user_names,
        R.string.settings_privacy_public_address,
        R.string.settings_privacy_push,
    )

@Composable
private fun Privacy() {
    InsetSection(title = stringResource(R.string.settings_privacy_title), footer = stringResource(R.string.settings_privacy_kept)) {
        ProseRow(stringResource(R.string.settings_privacy_intro), MaterialTheme.colorScheme.onSurfaceVariant)
        stored.forEach { ProseRow(stringResource(it)) }
    }
}

/** A notification channel (Push.kt), named as the system settings name it, and what else it carries. */
private class Channel(
    val id: String,
    @param:StringRes val name: Int,
    @param:StringRes val alsoCarries: Int? = null,
)

private val channels =
    listOf(
        Channel(URGENT_CHANNEL, R.string.notification_channel_urgent),
        Channel(ALERT_CHANNEL, R.string.notification_channel_alerts, R.string.settings_channel_alerts),
        Channel(UPDATES_CHANNEL, R.string.notification_channel_updates),
    )

/**
 * Whether each kind of notification reaches this phone. The owner changes
 * them in the system settings, which each row opens; the state is read
 * again on return.
 */
@Composable
private fun Notifications() {
    val context = LocalContext.current
    val manager = context.getSystemService(NotificationManager::class.java)
    var allowed by remember { mutableStateOf(allowedChannels(manager)) }
    LifecycleResumeEffect(manager) {
        allowed = allowedChannels(manager)
        onPauseOrDispose { }
    }
    val on = stringResource(R.string.settings_notifications_on)
    val off = stringResource(R.string.settings_notifications_off)
    InsetSection(
        title = stringResource(R.string.settings_notifications),
        footer = stringResource(R.string.settings_notifications_footer),
    ) {
        channels.forEach { channel ->
            InsetRow(
                stringResource(channel.name),
                subtitle = channel.alsoCarries?.let { stringResource(it) },
                trailing = {
                    Text(
                        if (channel.id in allowed) on else off,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { context.startActivity(notificationSettings(context, manager, channel.id)) },
            )
        }
    }
}

/** The channels that can show: the app may notify at all and the owner has not turned the channel off. */
private fun allowedChannels(manager: NotificationManager): Set<String> {
    if (!manager.areNotificationsEnabled()) return emptySet()
    return channels.map { it.id }.filterTo(mutableSetOf()) { id ->
        val importance = manager.getNotificationChannel(id)?.importance ?: NotificationManager.IMPORTANCE_NONE
        importance != NotificationManager.IMPORTANCE_NONE
    }
}

/** A channel's own settings; the app's settings when notifications are off for the whole app, since a channel cannot be turned on then. */
private fun notificationSettings(
    context: Context,
    manager: NotificationManager,
    channel: String,
): Intent {
    val app = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (!manager.areNotificationsEnabled()) return app
    return Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
}

/**
 * What the app is built with, grouped by licence: the Gradle dependencies
 * (`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`)
 * and the fonts of ADR 0020. Licences that require their text to travel
 * with the app have it in res/raw, the fonts' copied from UX/UI; Google's
 * terms are named with their address.
 */
private class Licence(
    @param:StringRes val name: Int,
    val libraries: List<String>,
    @param:RawRes val text: Int? = null,
    val terms: String? = null,
)

private val licences =
    listOf(
        Licence(
            R.string.licence_apache,
            listOf(
                "Android Jetpack: Compose, Material 3, Activity, Lifecycle, Navigation, Credentials",
                "Kotlin, kotlinx.coroutines, kotlinx.serialization, kotlinx-datetime, kotlinx-io, kotlinx.collections.immutable",
                "Ktor",
                "OkHttp, Okio",
                "Firebase Cloud Messaging",
                "Kermit",
                "Multiplatform Settings",
                "cryptography-kotlin",
                "kaml, snakeyaml-engine-kmp",
                "urlencoder",
                "Guava ListenableFuture",
                "JSpecify, JetBrains annotations, Error Prone annotations, javax.inject",
                "Material Symbols",
            ),
            text = R.raw.licence_apache_2_0,
        ),
        Licence(R.string.licence_mit, listOf("supabase-kt"), text = R.raw.licence_supabase_kt),
        Licence(R.string.licence_mit, listOf("SLF4J"), text = R.raw.licence_slf4j),
        Licence(R.string.licence_ofl, listOf("Michroma"), text = R.raw.licence_ofl_michroma),
        Licence(R.string.licence_ofl, listOf("JetBrains Mono"), text = R.raw.licence_ofl_jetbrains_mono),
        Licence(
            R.string.licence_android_sdk,
            listOf("Google Play services", "Google Identity Services"),
            terms = "https://developer.android.com/studio/terms",
        ),
        Licence(R.string.licence_ml_kit, listOf("Google code scanner"), terms = "https://developers.google.com/ml-kit/terms"),
    )

@Composable
fun LicencesScreen(onBack: () -> Unit) {
    Screen(stringResource(R.string.licences_title), onBack = onBack) {
        item {
            Text(
                stringResource(R.string.licences_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        licences.forEach { licence -> item { LicenceSection(licence) } }
    }
}

@Composable
private fun LicenceSection(licence: Licence) {
    var open by rememberSaveable { mutableStateOf(false) }
    val reduceMotion = LocalReduceMotion.current
    InsetSection(
        modifier = if (reduceMotion) Modifier else Modifier.animateContentSize(expandSpring()),
        title = stringResource(licence.name),
        footer = licence.terms,
    ) {
        licence.libraries.forEach { ProseRow(it) }
        licence.text?.let { text ->
            InsetRow(
                stringResource(if (open) R.string.licences_hide else R.string.licences_show),
                titleColor = MaterialTheme.colorScheme.primary,
                onClick = { open = !open },
                chevron = false,
            )
            if (open) LicenceText(text)
        }
    }
}

@Composable
private fun LicenceText(
    @RawRes text: Int,
) {
    val resources = LocalResources.current
    val content = remember(text) { resources.openRawResource(text).bufferedReader().use { it.readText() } }
    SelectionContainer {
        Text(
            content,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Dimens.spaceL),
        )
    }
}

/** Stored with the account, so it applies to every phone; off and disabled until the setting has loaded. */
@Composable
private fun WeeklySummary(
    on: Boolean?,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    InsetSection(footer = stringResource(R.string.settings_weekly_summary_footer)) {
        InsetRow(
            stringResource(R.string.settings_weekly_summary),
            modifier =
                Modifier.toggleable(on == true, enabled = on != null, role = Role.Switch) {
                    haptics.toggle(it)
                    onChange(it)
                },
            trailing = { Switch(checked = on == true, onCheckedChange = null, enabled = on != null) },
        )
    }
}
