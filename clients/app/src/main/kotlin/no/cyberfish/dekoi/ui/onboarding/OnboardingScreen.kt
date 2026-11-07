package no.cyberfish.dekoi.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.components.Horizon
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.PrimaryButton
import no.cyberfish.dekoi.ui.components.SectionLabel
import no.cyberfish.dekoi.ui.headingCase
import no.cyberfish.dekoi.ui.learn.Scene
import no.cyberfish.dekoi.ui.learn.SceneDrawing
import no.cyberfish.dekoi.ui.learn.drawingHeight

private enum class Page(
    @param:StringRes val label: Int,
    @param:StringRes val title: Int,
    @param:StringRes val body: Int,
    val scene: Scene,
) {
    DECOY(R.string.onboarding_decoy_label, R.string.onboarding_decoy_title, R.string.onboarding_decoy_body, Scene.DECOY),
    TOUCH(R.string.onboarding_touch_label, R.string.onboarding_touch_title, R.string.onboarding_touch_body, Scene.TOUCH),
    STEPS(R.string.onboarding_steps_label, R.string.onboarding_steps_title, R.string.onboarding_steps_body, Scene.STEPS),
    WATCH(R.string.onboarding_watch_label, R.string.onboarding_watch_title, R.string.onboarding_watch_body, Scene.WATCH),
}

private val HORIZON = 48.dp
private val MARK_WIDTH = 24.dp
private val MARK_HEIGHT = 2.dp

/**
 * The first-run tour: four pages, each a drawing above the horizon and a
 * few sentences. Skip leaves at any time. When the owner has no DeKoi yet,
 * the last page leads to adding one.
 */
@Composable
fun OnboardingScreen(
    needsDekoi: Boolean,
    onDone: () -> Unit,
    onAddDekoi: () -> Unit,
) {
    val pages = Page.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val last = pager.currentPage == pages.lastIndex
    val action =
        when {
            !last -> R.string.onboarding_next
            needsDekoi -> R.string.action_add_dekoi
            else -> R.string.onboarding_done
        }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The row keeps its height on the last page, where Later or Done takes Skip's place.
        Box(
            Modifier.fillMaxWidth().heightIn(min = Dimens.touchTarget).padding(horizontal = Dimens.spaceS),
            contentAlignment = Alignment.CenterEnd,
        ) {
            if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.onboarding_skip)) }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { PageContent(it + 1, pages[it]) }
        Column(
            Modifier.fillMaxWidth().padding(Dimens.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
        ) {
            PageMarks(pager.currentPage, pages.size)
            PrimaryButton(
                onClick = {
                    when {
                        !last -> scope.launch { pager.turnTo(pager.currentPage + 1, reduceMotion) }
                        needsDekoi -> onAddDekoi()
                        else -> onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(action), style = MaterialTheme.typography.labelLarge)
            }
            if (last && needsDekoi) {
                TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_later)) }
            }
        }
    }
}

private suspend fun PagerState.turnTo(
    page: Int,
    reduceMotion: Boolean,
) = if (reduceMotion) scrollToPage(page) else animateScrollToPage(page)

/** One page; it scrolls, so large text never pushes words off the screen. */
@Composable
private fun PageContent(
    number: Int,
    page: Page,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Dimens.gutter),
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceL),
    ) {
        SceneDrawing(page.scene, Modifier.fillMaxWidth().height(drawingHeight()))
        Horizon(Modifier.fillMaxWidth().height(HORIZON), still = true)
        SectionLabel(number, stringResource(page.label))
        Text(
            stringResource(page.title).headingCase(),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(page.body), style = MaterialTheme.typography.bodyLarge)
    }
}

/** Short hairlines, one per page, the current one lit; read out as "Page 2 of 4" when it changes. */
@Composable
private fun PageMarks(
    current: Int,
    count: Int,
) {
    val description = stringResource(R.string.onboarding_page, current + 1, count)
    Row(
        Modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceS),
    ) {
        repeat(count) {
            val color = if (it == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
            Box(Modifier.size(MARK_WIDTH, MARK_HEIGHT).background(color))
        }
    }
}
