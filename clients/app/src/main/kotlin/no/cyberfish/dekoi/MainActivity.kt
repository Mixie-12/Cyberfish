package no.cyberfish.dekoi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import no.cyberfish.dekoi.ui.AppViewModel
import no.cyberfish.dekoi.ui.DekoiApp
import no.cyberfish.dekoi.ui.DekoiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val backend = (application as DekoiApplication).backend
        setContent {
            DekoiTheme {
                DekoiApp(viewModel(factory = viewModelFactory { initializer { AppViewModel(backend) } }))
            }
        }
    }
}
