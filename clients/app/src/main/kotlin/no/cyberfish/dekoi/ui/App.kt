package no.cyberfish.dekoi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.alertBadgeCount
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

private const val PUSH_MS = 300
private const val TAB_MS = 200
private const val TAB_OUT_MS = TAB_MS * 35 / 100
private const val TAB_SCALE = 0.92f
private val PUSH_DISTANCE = 30.dp

@Composable
fun DekoiApp(viewModel: AppViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    when (val current = session) {
        is SessionStatus.Authenticated -> SignedIn(viewModel, current.session.user?.email)
        is SessionStatus.RefreshFailure -> SignedIn(viewModel, null)
        is SessionStatus.NotAuthenticated -> SignInScreen(viewModel)
        SessionStatus.Initializing -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
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
            navController.popBackStack(tab.route, inclusive = false)
            scope.launch { listStates.getValue(tab).scrollToTop(reduceMotion) }
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
    NotificationPermission(hasDevice = data.devices.isNotEmpty())

    // Screens link to tab roots as well as pushed screens; a tab root switches tabs.
    val navigate: (Route) -> Unit = { route ->
        val tab = Tab.entries.firstOrNull { it.route == route }
        if (tab != null) selectTab(tab) else navController.navigate(route)
    }
    val back: () -> Unit = { navController.popBackStack() }
    val now = Instant.now()
    val transitions =
        Transitions(with(LocalDensity.current) { PUSH_DISTANCE.roundToPx() }, LocalLayoutDirection.current, reduceMotion, tabSwitch)

    Scaffold(
        bottomBar = { TabBar(currentTab, alertBadgeCount(data.events, data.trustedMacs, now), ::selectTab) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        NavHost(
            navController,
            startDestination = Route.Home,
            modifier = Modifier.padding(padding),
            enterTransition = { transitions.enter(this, forward = true) },
            exitTransition = { transitions.exit(this, forward = true) },
            popEnterTransition = { transitions.enter(this, forward = false) },
            popExitTransition = { transitions.exit(this, forward = false) },
        ) {
            composable<Route.Home> {
                HomeScreen(data, now, refreshing, listStates.getValue(Tab.HOME), viewModel::refresh, navigate)
            }
            composable<Route.Alerts> {
                AlertsScreen(data, now, Route.FilteredAlerts(), listStates.getValue(Tab.ALERTS), navigate, onBack = null)
            }
            composable<Route.FilteredAlerts> { entry ->
                AlertsScreen(data, now, entry.toRoute(), rememberLazyListState(), navigate, onBack = back)
            }
            composable<Route.AlertDetail> { entry ->
                AlertDetailScreen(data, entry.toRoute<Route.AlertDetail>().eventId, viewModel::setTrusted, navigate, back)
            }
            composable<Route.Network> {
                NetworkScreen(data, now, listStates.getValue(Tab.NETWORK), navigate)
            }
            composable<Route.HostDetail> { entry ->
                val route = entry.toRoute<Route.HostDetail>()
                HostDetailScreen(data, now, route.deviceId, route.mac, viewModel::setTrusted, navigate, back)
            }
            composable<Route.Networks> {
                NetworksScreen(data, back)
            }
            composable<Route.Dekoi> { entry ->
                DekoiScreen(
                    data,
                    now,
                    entry.toRoute<Route.Dekoi>().deviceId,
                    onRename = viewModel::rename,
                    onRemove = {
                        viewModel.remove(it)
                        back()
                    },
                    onBack = back,
                )
            }
            composable<Route.AddDekoi> {
                AddDekoiScreen(onClaim = viewModel::claim, onBack = back)
            }
            composable<Route.Settings> {
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
        }
    }
}

private suspend fun LazyListState.scrollToTop(reduceMotion: Boolean) {
    if (reduceMotion) scrollToItem(0) else animateScrollToItem(0)
}

/**
 * Pushing a screen slides it in along the reading direction with a fade
 * (shared axis X); changing tabs fades through (docs/design.md, "Tokens").
 */
private class Transitions(
    private val distance: Int,
    layoutDirection: LayoutDirection,
    private val reduceMotion: Boolean,
    private val tabSwitch: TabSwitch,
) {
    private val reading = if (layoutDirection == LayoutDirection.Ltr) 1 else -1

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.changesTab() =
        tabSwitch.active || (initialState.rootTab() != null && targetState.rootTab() != null)

    fun enter(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        forward: Boolean,
    ): EnterTransition =
        when {
            reduceMotion -> {
                EnterTransition.None
            }

            scope.changesTab() -> {
                fadeIn(tween(TAB_MS - TAB_OUT_MS, delayMillis = TAB_OUT_MS)) +
                    scaleIn(tween(TAB_MS - TAB_OUT_MS, delayMillis = TAB_OUT_MS), TAB_SCALE)
            }

            else -> {
                slideInHorizontally(tween(PUSH_MS)) { distance * reading * if (forward) 1 else -1 } + fadeIn(tween(PUSH_MS))
            }
        }

    fun exit(
        scope: AnimatedContentTransitionScope<NavBackStackEntry>,
        forward: Boolean,
    ): ExitTransition =
        when {
            reduceMotion -> ExitTransition.None
            scope.changesTab() -> fadeOut(tween(TAB_OUT_MS))
            else -> slideOutHorizontally(tween(PUSH_MS)) { -distance * reading * if (forward) 1 else -1 } + fadeOut(tween(PUSH_MS))
        }
}

@Composable
private fun TabBar(
    current: Tab,
    alertBadge: Int,
    onSelect: (Tab) -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Tab.entries.forEach { tab ->
            val selected = tab == current
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
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
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
