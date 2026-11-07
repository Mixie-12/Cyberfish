package no.cyberfish.dekoi.ui.components

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens

private val MISSING_SKELETON = 200.dp

/**
 * A screen: an optional back button, a large title with an optional line
 * under it (such as a [StaleLabel]), then scrolling content. Tab roots pass
 * the [listState] the tab bar scrolls to the top on reselect.
 */
@Composable
fun Screen(
    title: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onBack: (() -> Unit)? = null,
    subtitle: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        state = listState,
        contentPadding = PaddingValues(start = Dimens.gutter, end = Dimens.gutter, top = Dimens.spaceM, bottom = Dimens.spaceXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.section),
    ) {
        item {
            Column {
                if (onBack != null) {
                    // Line the arrow up with the title under it.
                    IconButton(onClick = onBack, modifier = Modifier.offset(x = -Dimens.spaceM)) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                }
                Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
                subtitle?.invoke()
            }
        }
        content()
    }
}

/** A pushed screen whose subject is gone: a skeleton until the data has loaded, then [message]. */
@Composable
fun MissingScreen(
    @StringRes title: Int,
    loaded: Boolean,
    @DrawableRes glyph: Int,
    @StringRes message: Int,
    onBack: () -> Unit,
) {
    Screen(stringResource(title), onBack = onBack) {
        item {
            if (loaded) {
                EmptyState(glyph, stringResource(message))
            } else {
                Skeleton(Modifier.fillMaxWidth().height(MISSING_SKELETON), MaterialTheme.shapes.large)
            }
        }
    }
}

/** Confirms an action that cannot be undone; [confirm] names it and shows in the critical colour. */
@Composable
fun ConfirmDialog(
    @StringRes title: Int,
    @StringRes body: Int,
    @StringRes confirm: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            GuardAgainstOverlays()
            TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Keeps another app's window from tricking the owner into confirming
 * (tapjacking): from Android 12 other apps' overlays are hidden while the
 * dialog shows, and on every version a touch that passes through one is
 * ignored. Call it inside the dialog, whose window it guards.
 */
@Composable
private fun GuardAgainstOverlays() {
    val window = (LocalView.current.parent as DialogWindowProvider).window
    SideEffect {
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
    }
}
