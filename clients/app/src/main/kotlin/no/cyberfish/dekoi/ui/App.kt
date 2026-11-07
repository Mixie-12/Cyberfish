package no.cyberfish.dekoi.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.jan.supabase.auth.status.SessionStatus
import no.cyberfish.dekoi.R
import java.time.Instant

private enum class Tab(
    val route: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int,
) {
    HOME("home", R.string.tab_home, R.drawable.ic_home, R.drawable.ic_home_fill),
    ACTIVITY("activity", R.string.tab_activity, R.drawable.ic_notifications, R.drawable.ic_notifications_fill),
    NETWORK("network", R.string.tab_network, R.drawable.ic_lan, R.drawable.ic_lan_fill),
    SETTINGS("settings", R.string.tab_settings, R.drawable.ic_settings, R.drawable.ic_settings_fill),
}

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

@Composable
private fun SignedIn(
    viewModel: AppViewModel,
    email: String?,
) {
    val navController = rememberNavController()
    val data by viewModel.data.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

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
    val openEvent by viewModel.openEvent.collectAsStateWithLifecycle()
    LaunchedEffect(openEvent) {
        openEvent?.let {
            navController.navigate("event/$it")
            viewModel.eventOpened()
        }
    }
    NotificationPermission(hasDevice = data.devices.isNotEmpty())

    Scaffold(
        bottomBar = { TabBar(navController) },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        val now = Instant.now()
        NavHost(navController, startDestination = Tab.HOME.route, modifier = Modifier.padding(padding)) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    data = data,
                    now = now,
                    onRefresh = viewModel::refresh,
                    onAdd = { navController.navigate("add") },
                    onDevice = { navController.navigate("device/${it.id}") },
                    onEvent = { navController.navigate("event/$it") },
                )
            }
            composable(Tab.ACTIVITY.route) {
                ActivityScreen(data, now, onEvent = { navController.navigate("event/$it") })
            }
            composable(Tab.NETWORK.route) { NetworkScreen(data.hosts, now) }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(email, onSignOut = viewModel::signOut, onDeleteAccount = viewModel::deleteAccount)
            }
            composable("event/{id}") { entry ->
                val event = data.events.firstOrNull { it.id.toString() == entry.arguments?.getString("id") }
                if (event != null) {
                    EventScreen(event, data.hostFor(event.srcMac), onTrust = viewModel::setTrusted, onBack = navController::popBackStack)
                }
            }
            composable("device/{id}") { entry ->
                val device = data.devices.firstOrNull { it.id == entry.arguments?.getString("id") }
                if (device != null) {
                    DeviceScreen(
                        device,
                        onRename = { viewModel.rename(device, it) },
                        onRemove = {
                            viewModel.remove(device)
                            navController.popBackStack()
                        },
                        onBack = navController::popBackStack,
                    )
                }
            }
            composable("add") {
                AddDeviceScreen(onClaim = viewModel::claim, onBack = navController::popBackStack)
            }
        }
    }
}

@Composable
private fun TabBar(navController: NavHostController) {
    val current by navController.currentBackStackEntryAsState()
    val route = current?.destination?.route
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Tab.entries.forEach { tab ->
            val selected = route == tab.route
            NavigationBarItem(
                selected = selected,
                onClick = {
                    navController.navigate(tab.route) {
                        popUpTo(Tab.HOME.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null) },
                label = { Text(stringResource(tab.label)) },
            )
        }
    }
}
