package no.cyberfish.dekoi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.requestGoogleIdToken
import no.cyberfish.dekoi.shared.DesignTokens

@Composable
fun SignInScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp)) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(24.dp)).background(Color(DesignTokens.BRAND))) {
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
            Text(stringResource(R.string.signin_tagline), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            SecondaryText(stringResource(R.string.signin_body))
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    failure = null
                    scope.launch {
                        try {
                            viewModel.signIn(requestGoogleIdToken(context, BuildConfig.GOOGLE_WEB_CLIENT_ID))
                        } catch (_: GetCredentialCancellationException) {
                            // The owner closed the account picker; nothing to report.
                        } catch (_: NoCredentialException) {
                            failure = R.string.signin_no_account
                        } catch (_: GetCredentialException) {
                            failure = R.string.signin_error
                        } catch (_: GoogleIdTokenParsingException) {
                            failure = R.string.signin_error
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.signin_button), style = MaterialTheme.typography.labelLarge)
            }
            failure?.let {
                Text(stringResource(it), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}
