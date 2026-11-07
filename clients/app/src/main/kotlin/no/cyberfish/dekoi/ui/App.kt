package no.cyberfish.dekoi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.alertBadgeCount
import no.cyberfish.dekoi.isSetUp
import no.cyberfish.dekoi.shared.DesignTokens
import no.cyberfish.dekoi.ui.components.Screen
import no.cyberfish.dekoi.ui.features.FeaturesScreen
import no.cyberfish.dekoi.ui.learn.LearnScreen
import no.cyberfish.dekoi.ui.learn.LearnTopicScreen
import no.cyberfish.dekoi.ui.motion.Direction
import no.cyberfish.dekoi.ui.motion.SharedElementHost
import no.cyberfish.dekoi.ui.motion.rememberNavTransitions
import no.cyberfish.dekoi.ui.motion.screen
import no.cyberfish.dekoi.ui.network.HostDetailScreen
import no.cyberfish.dekoi.ui.network.InsightsScreen
import no.cyberfish.dekoi.ui.network.NetworkScreen
import no.cyberfish.dekoi.ui.network.NetworksScreen
import no.cyberfish.dekoi.ui.network.PlaceDevicesScreen
import no.cyberfish.dekoi.ui.onboarding.OnboardingOnce
import no.cyberfish.dekoi.ui.onboarding.OnboardingScreen
import no.cyberfish.dekoi.ui.onboarding.rememberWelcome
import java.time.Instant

/** Every place in the app. The four tab roots, then the screens pushed on a tab. */
sealed interface Route {
    @Serializable data object Home : Route

    @Serializable data object Alerts : Route

    @Serializable data object Network : Route

    @Serializable data object Settings : Route

    @Serializable data class AlertDetail(
        val eventId: Long,
    ) : Route

    /** Alerts narrowed to one source device (hardware address), decoy service or day (ISO date); null means any. */
    @Serializable data class FilteredAlerts(
        val mac: String? = null,
        val service: String? = null,
        val day: String? = null,
    ) : Route

    /** A host on the network, identified as in network_hosts. */
    @Serializable data class HostDetail(
        val deviceId: String,
        val mac: String,
    ) : Route

    @Serializable data object Networks : Route

    @Serializable data class Dekoi(
        val deviceId: String,
    ) : Route

    @Serializable data object AddDekoi : Route

    /** Writing a DeKoi card with this phone (ADR 0014, 0017). */
    @Serializable data object WriteCard : Route

    @Serializable data object Licences : Route

    /** The tour, shown once per tour version after sign-in (ui/onboarding/FirstRun.kt); without the tab bar. */
    @Serializable data object Onboarding : Route

    @Serializable data object Learn : Route

    /** A Learn topic by its stable id (ui/learn/LearnTopic.kt). */
    @Serializable data class LearnTopic(
        val id: String,
    ) : Route

    /** Network health, DNS, open ports and speed of one DeKoi (ADR 0015). */
    @Serializable data class Insights(
        val deviceId: String,
    ) : Route

    /** Putting the devices without a room into rooms, one at a time. */
    @Serializable data object PlaceDevices : Route

    /** Everything DeKoi does and how each part is doing on one DeKoi; null means the one Home speaks for. */
    @Serializable data class Features(
        val deviceId: String? = null,
    ) : Route
}

private enum class Tab(
    val route: Route,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    HOME(Route.Home, R.string.tab_home, R.drawable.ic_home, R.drawable.ic_home_fill),
    ALERTS(Route.Alerts, R.string.tab_alerts, R.drawable.ic_notifications, R.drawable.ic_notifications_fill),
    NETWORK(Route.Network, R.string.tab_network, R.drawable.ic_lan, R.drawable.ic_lan_fill),
    SETTINGS(Route.Settings, R.string.tab_settings, R.drawable.ic_settings, R.drawable.ic_settings_fill),
}

/** The tab root this entry shows, or null for a pushed screen. */
private fun NavBackStackEntry.rootTab(): Tab? = Tab.entries.firstOrNull { destination.hasRoute(it.route::class) }

private val TAB_INDICATOR = 2.dp
private val TAB_INDICATOR_WIDTH = 32.dp
private val TAB_LABEL_MIN = 6.sp

@Composable
fun DekoiApp(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    SharedElementHost {
        when (val current = session) {
            is SessionStatus.Authenticated -> SignedIn(viewModel, current.session.user?.email)
            is SessionStatus.RefreshFailure -> SignedIn(viewModel, null)
            is SessionStatus.NotAuthenticated -> SignInScreen(viewModel)
            SessionStatus.Initializing -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        }
    }
}

// Set by the tab bar for the transition it starts; a plain field, read once when the transition begins.
private class TabSwitch {
    var active = false
}

@Composable
private fun SignedIn(
    viewModel: AppViewModel,
    email: String?,
) {
    val navController = rememberNavController()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val openEvent by viewModel.openEvent.collectAsStateWithLifecycle()
    val entry by navController.currentBackStackEntryAsState()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val tabSwitch = remember { TabSwitch() }
    val listStates = Tab.entries.associateWith { rememberLazyListState() }
    // Here rather than on Home, so it is not read again on every return to Home.
    val welcome = rememberWelcome()
    // A tab root names its tab; a pushed screen belongs to the tab it was pushed on.
    var lastTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val currentTab = entry?.rootTab() ?: lastTab

    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(resources.getString(it))
            viewModel.dismissMessage()
        }
    }
    LaunchedEffect(Unit) { viewModel.registerPush() }
    LaunchedEffect(entry?.id) {
        tabSwitch.active = false
        lastTab = currentTab
    }

    fun selectTab(tab: Tab) {
        if (tab == currentTab) {
            val list = listStates.getValue(tab)
            // A root that comes back from under a pushed screen is not laid out, so an
            // animated scroll has nothing to move and stops where it is: it opens at the top instead.
            if (navController.popBackStack(tab.route, inclusive = false)) {
                list.requestScrollToItem(0)
            } else {
                scope.launch { list.scrollToTop(reduceMotion) }
            }
            return
        }
        tabSwitch.active = true
        lastTab = tab
        navController.navigate(tab.route) {
            popUpTo(Route.Home) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // A tapped notification opens the alert over the Alerts tab.
    LaunchedEffect(openEvent) {
        val eventId = openEvent ?: return@LaunchedEffect
        if (currentTab != Tab.ALERTS) selectTab(Tab.ALERTS)
        navController.popBackStack(Route.Alerts, inclusive = false)
        navController.navigate(Route.AlertDetail(eventId))
        viewModel.eventOpened()
    }
    NotificationPermission(setUp = data.devices.any { it.isSetUp })
    OnboardingOnce { navController.navigate(Route.Onboarding) }

    // Screens link to tab roots as well as pushed screens; a tab root switches tabs.
    val navigate: (Route) -> Unit = { route ->
        val tab = Tab.entries.firstOrNull { it.route == route }
        if (tab != null) selectTab(tab) else navController.navigate(route)
    }
    val back: () -> Unit = { navController.popBackStack() }
    val now = Instant.now()
    val transitions = rememberNavTransitions { from, to -> tabSwitch.active || (from.rootTab() != null && to.rootTab() != null) }

    Scaffold(
        bottomBar = {
            if (entry?.destination?.hasRoute(Route.Onboarding::class) != true) {
                TabBar(currentTab, alertBadgeCount(data.events, data.trustedMacs), ::selectTab)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(
            navController,
            startDestination = Route.Home,
            modifier = Modifier.padding(padding),
            enterTransition = { transitions.enter(this, Direction.FORWARD) },
            exitTransition = { transitions.exit(this, Direction.FORWARD) },
            popEnterTransition = { transitions.enter(this, Direction.BACK) },
            popExitTransition = { transitions.exit(this, Direction.BACK) },
            predictivePopEnterTransition = { transitions.enter(this, Direction.BACK_GESTURE) },
            predictivePopExitTransition = { transitions.exit(this, Direction.BACK_GESTURE) },
        ) {
            screen<Route.Home> {
                HomeScreen(data, now, refreshing, listStates.getValue(Tab.HOME), welcome, viewModel::refresh, navigate)
            }
            screen<Route.Alerts> {
                AlertsScreen(
                    data,
                    now,
                    Route.FilteredAlerts(),
                    listStates.getValue(Tab.ALERTS),
                    viewModel::refresh,
                    navigate,
                    onBack = null,
                )
            }
            screen<Route.FilteredAlerts> { entry ->
                AlertsScreen(data, now, entry.toRoute(), rememberLazyListState(), viewModel::refresh, navigate, onBack = back)
            }
            screen<Route.AlertDetail> { entry ->
                AlertDetailScreen(
                    data,
                    now,
                    entry.toRoute<Route.AlertDetail>().eventId,
                    onAcknowledge = viewModel::acknowledge,
                    onTrust = viewModel::setTrusted,
                    onNavigate = navigate,
                    onBack = back,
                )
            }
            screen<Route.Network> {
                NetworkScreen(data, now, listStates.getValue(Tab.NETWORK), navigate)
            }
            screen<Route.HostDetail> { entry ->
                val route = entry.toRoute<Route.HostDetail>()
                HostDetailScreen(data, now, route.deviceId, route.mac, viewModel::setTrusted, viewModel::setNameAndRoom, navigate, back)
            }
            screen<Route.Networks> {
                NetworksScreen(data, back)
            }
            screen<Route.Insights> { entry ->
                val insights by viewModel.insights.collectAsStateWithLifecycle()
                InsightsScreen(
                    data,
                    insights,
                    now,
                    entry.toRoute<Route.Insights>().deviceId,
                    onLoad = viewModel::loadInsights,
                    onSpeedTest = viewModel::setSpeedTest,
                    onNavigate = navigate,
                    onBack = back,
                )
            }
            screen<Route.Features> { entry ->
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadSettings() }
                FeaturesScreen(
                    data,
                    now,
                    entry.toRoute<Route.Features>().deviceId,
                    settings?.weeklySummary,
                    onNavigate = navigate,
                    // Wi-Fi Guard and the weekly summary are near the top of Settings.
                    onOpenSettings = {
                        navigate(Route.Settings)
                        listStates.getValue(Tab.SETTINGS).requestScrollToItem(0)
                    },
                    onBack = back,
                )
            }
            screen<Route.Dekoi> { entry ->
                DekoiScreen(
                    data,
                    now,
                    entry.toRoute<Route.Dekoi>().deviceId,
                    onRename = viewModel::rename,
                    onRemove = {
                        viewModel.remove(it)
                        back()
                    },
                    onNavigate = navigate,
                    onBack = back,
                )
            }
            screen<Route.AddDekoi> {
                AddDekoiScreen(data, now, onRefresh = viewModel::refreshDevices, onNavigate = navigate, onBack = back)
            }
            screen<Route.WriteCard> {
                WriteCardScreen(onClaim = viewModel::claim, onBack = back)
            }
            screen<Route.Licences> {
                LicencesScreen(back)
            }
            screen<Route.Onboarding> {
                OnboardingScreen(
                    needsDekoi = data.loaded && data.devices.isEmpty(),
                    onDone = back,
                    onAddDekoi = { navController.navigate(Route.AddDekoi) { popUpTo<Route.Onboarding> { inclusive = true } } },
                )
            }
            screen<Route.Learn> {
                LearnScreen(onOpen = { navigate(Route.LearnTopic(it.id)) }, onBack = back)
            }
            screen<Route.LearnTopic> { entry ->
                LearnTopicScreen(entry.toRoute<Route.LearnTopic>().id, back)
            }
            screen<Route.Settings> {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { viewModel.loadSettings() }
                SettingsScreen(
                    email,
                    data.devices,
                    listStates.getValue(Tab.SETTINGS),
                    onSignOut = viewModel::signOut,
                    onDeleteAccount = viewModel::deleteAccount,
                    onNavigate = navigate,
                    weeklySummary = settings?.weeklySummary,
                    onWeeklySummary = viewModel::setWeeklySummary,
                    onSetWifiName = viewModel::setWifiName,
                )
            }
            screen<Route.PlaceDevices> {
                PlaceDevicesScreen(data, now, viewModel::setNameAndRoom, back)
            }
        }
    }
}

private suspend fun LazyListState.scrollToTop(reduceMotion: Boolean) {
    if (reduceMotion) scrollToItem(0) else animateScrollToItem(0)
}

// A hairline along the top and a blue line over the chosen tab; no pill.
@Composable
private fun TabBar(
    current: Tab,
    alertBadge: Int,
    onSelect: (Tab) -> Unit,
) {
    val hairline = MaterialTheme.colorScheme.outlineVariant
    val blue = MaterialTheme.colorScheme.primary
    val white = Color(DesignTokens.WHITE)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val colors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = white,
            selectedTextColor = white,
            indicatorColor = Color.Transparent,
            unselectedIconColor = muted,
            unselectedTextColor = muted,
        )
    val labelStyle = MaterialTheme.typography.label
    NavigationBar(
        Modifier.drawWithContent {
            drawContent()
            drawTopLine(hairline, Dimens.hairline.toPx(), size.width)
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                modifier =
                    Modifier.drawWithContent {
                        drawContent()
                        if (selected) drawTopLine(blue, TAB_INDICATOR.toPx(), TAB_INDICATOR_WIDTH.toPx())
                    },
                colors = colors,
                icon = {
                    BadgedBox(badge = {
                        if (tab == Tab.ALERTS && alertBadge > 0) {
                            val description = pluralStringResource(R.plurals.tab_alerts_badge, alertBadge, alertBadge)
                            Badge(Modifier.semantics { contentDescription = description }) { Text(alertBadge.toString()) }
                        }
                    }) {
                        Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
                    }
                },
                // Monospace is wide: at large font scales a label shrinks to fit its tab instead of breaking mid-word.
                label = {
                    Text(
                        stringResource(tab.label).headingCase(),
                        style = labelStyle,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = TAB_LABEL_MIN, maxFontSize = labelStyle.fontSize),
                    )
                },
            )
        }
    }
}

/** A line of [length] centred along the top edge. */
private fun DrawScope.drawTopLine(
    color: Color,
    width: Float,
    length: Float,
) {
    val start = (size.width - length) / 2
    drawLine(color, Offset(start, width / 2), Offset(start + length, width / 2), width)
}
