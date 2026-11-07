package no.cyberfish.dekoi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import no.cyberfish.dekoi.ui.AppViewModel
import no.cyberfish.dekoi.ui.DekoiApp
import no.cyberfish.dekoi.ui.DekoiTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        val app = application as DekoiApplication
        viewModelFactory { initializer { AppViewModel(app.backend, app::pushToken) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
