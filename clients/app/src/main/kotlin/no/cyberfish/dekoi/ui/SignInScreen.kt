package no.cyberfish.dekoi.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.BuildConfig
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.requestGoogleIdToken

private val HERO_HEIGHT = 240.dp
private val MARK_GAP = 20.dp
private val FLOAT_DISTANCE = 6.dp
private const val FLOAT_MS = 3000
private val GLOW = 16.dp
private const val MARK = "><>"
private const val REFLECTION_ALPHA = 0.2f
private const val SEA_LINES = 7
private const val SEA_ALPHA = 0.35f

/** A stroke of the horizon line: wide and faint ones make the glow around the bright one. */
private class HorizonStroke(
    val width: Dp,
    val alpha: Float,
)

private val horizonStrokes = listOf(HorizonStroke(12.dp, 0.06f), HorizonStroke(4.dp, 0.2f), HorizonStroke(1.dp, 1f))

/**
 * Signing in, the brand's first impression (ADR 0020): the mark floating
 * over the horizon, the wordmark and tagline, and "Continue with Google".
 * A failure, from the account picker or from the backend, shows under the
 * button.
 */
@Composable
fun SignInScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refused by viewModel.message.collectAsStateWithLifecycle()
    var failure by remember { mutableStateOf<Int?>(null) }
    var asking by remember { mutableStateOf(false) }

    fun signIn() {
        failure = null
        viewModel.dismissMessage()
        asking = true
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
            } finally {
                asking = false
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .safeDrawingPadding()
                .padding(vertical = Dimens.spaceXl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.section, Alignment.CenterVertically),
        ) {
            Hero()
            Column(
                Modifier.padding(horizontal = Dimens.spaceXl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
            ) {
                Wordmark()
                Text(stringResource(R.string.signin_tagline), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(
                    stringResource(R.string.signin_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // Outlined and lit in the accent, never a solid fill (ADR 0020).
                OutlinedButton(
                    onClick = ::signIn,
                    enabled = !asking,
                    modifier = Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget),
                    border =
                        BorderStroke(
                            Dimens.hairline,
                            if (asking) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary,
                        ),
                ) {
                    Text(stringResource(R.string.signin_button), style = MaterialTheme.typography.labelLarge)
                }
                (failure ?: refused)?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

/**
 * The mark floats slowly over the horizon, its reflection in the sea below
 * (6 s up and down, still when animations are off). Decorative.
 */
@Composable
private fun Hero() {
    val distance = with(LocalDensity.current) { FLOAT_DISTANCE.toPx() }
    val float =
        if (LocalReduceMotion.current) {
            null
        } else {
            rememberInfiniteTransition(label = "float").animateFloat(
                0f,
                1f,
                infiniteRepeatable(tween(FLOAT_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "float",
            )
        }
    Box(Modifier.fillMaxWidth().height(HERO_HEIGHT).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Horizon(Modifier.fillMaxSize())
        // Centred on the horizon: the mark above it, the reflection below.
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MARK_GAP * 2)) {
            FishMark(Modifier.graphicsLayer { translationY = -(float?.value ?: 0f) * distance })
            FishMark(
                Modifier.graphicsLayer {
                    translationY = (float?.value ?: 0f) * distance
                    scaleY = -1f
                    alpha = REFLECTION_ALPHA
                },
            )
        }
    }
}

/** Stand-in for the shared FishMark (ADR 0020): the `><>` mark with a glow in the accent. */
@Composable
private fun FishMark(modifier: Modifier = Modifier) {
    val glow = with(LocalDensity.current) { GLOW.toPx() }
    Text(
        MARK,
        modifier = modifier,
        style = MaterialTheme.typography.displayMedium.copy(shadow = Shadow(MaterialTheme.colorScheme.primary, blurRadius = glow)),
    )
}

/** Stand-in for the shared Wordmark (ADR 0020). */
@Composable
private fun Wordmark() {
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
}

/**
 * Stand-in for the shared Horizon (ADR 0020): a glowing line across the
 * middle that fades at both ends, and faint lines below it that spread out
 * toward the bottom, like a dark sea.
 */
@Composable
private fun Horizon(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        fun line(
            y: Float,
            width: Float,
            alpha: Float,
        ) {
            val brush = Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = alpha), Color.Transparent))
            drawLine(brush, Offset(0f, y), Offset(size.width, y), width)
        }
        val horizon = size.height / 2
        horizonStrokes.forEach { line(horizon, it.width.toPx(), it.alpha) }
        for (i in 1..SEA_LINES) {
            val depth = i.toFloat() / (SEA_LINES + 1)
            line(horizon + horizon * depth * depth, Dimens.hairline.toPx(), SEA_ALPHA * (1 - depth))
        }
    }
}
