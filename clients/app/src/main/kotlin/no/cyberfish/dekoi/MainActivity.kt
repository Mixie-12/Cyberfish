package no.cyberfish.dekoi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import no.cyberfish.dekoi.ui.AppViewModel
import no.cyberfish.dekoi.ui.DekoiApp
import no.cyberfish.dekoi.ui.DekoiTheme
import no.cyberfish.dekoi.widget.publishWidget

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        val app = application as DekoiApplication
        viewModelFactory { initializer { AppViewModel(app.backend, app::pushToken) { publishWidget(app, it) } } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark only, so the bars keep light icons in the system's light mode too.
        val bars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        if (savedInstanceState == null) openEventFrom(intent)
        setContent {
            DekoiTheme {
                DekoiApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openEventFrom(intent)
    }

    // A tapped notification: shown by the system with the FCM data as extras, or by PushService.
    private fun openEventFrom(intent: Intent) {
        eventIdFrom(intent.getStringExtra(EVENT_ID_EXTRA))?.let(viewModel::open)
    }
}
