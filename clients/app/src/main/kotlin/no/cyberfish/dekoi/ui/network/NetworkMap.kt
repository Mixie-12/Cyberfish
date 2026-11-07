package no.cyberfish.dekoi.ui.network

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.R
import no.cyberfish.dekoi.Severity
import no.cyberfish.dekoi.isOnline
import no.cyberfish.dekoi.ui.Dimens
import no.cyberfish.dekoi.ui.LocalReduceMotion
import no.cyberfish.dekoi.ui.Route
import no.cyberfish.dekoi.ui.components.BrandTile
import no.cyberfish.dekoi.ui.components.HudFrame
import no.cyberfish.dekoi.ui.components.kindIcon
import no.cyberfish.dekoi.ui.motion.sharedKindTile
import no.cyberfish.dekoi.ui.tabular
import java.time.Instant
import kotlin.math.round

private val GLYPH = 22.dp
private val DASH = 4.dp
private val HUB = 2.dp
private val BADGE_SHIFT = 14.dp

// In dp, like MapLayout: room around the outermost node for captions, and
// how far beyond it the cluster names sit.
private const val MARGIN = 24f
private const val LABEL_DISTANCE = 44f

// The radar is drawn in the accent, faintly: rings barely there, spokes a
// little stronger, node edges stronger still.
private const val RING_ALPHA = 0.12f
private const val SPOKE_ALPHA = 0.3f
private const val EDGE_ALPHA = 0.5f

private const val ZOOM_MS = 300
private const val ZOOM_FROM = 0.85f

/**
 * The devices around the router, as DeKoi sees them (docs/design.md,
 * "Network"). Pinch to zoom, drag once zoomed in, double-tap to fit again;
 * a cluster bubble opens its cluster. For TalkBack the map is one node that
 * reads [summary] and offers "Show as list" ([onShowList]).
 */
@Composable
internal fun NetworkMap(
    entries: List<HostEntry>,
    dekois: List<Device>,
    networks: List<Network>,
    now: Instant,
    summary: String,
    onNavigate: (Route) -> Unit,
    onShowList: () -> Unit,
) {
    val string: (Int) -> String = LocalResources.current::getString
    var focus by remember { mutableStateOf<Group?>(null) }
    var view by remember { mutableStateOf(MapView()) }
    var viewport by remember { mutableStateOf(Size.Zero) }
    val rings = mapRings(entries, dekois, networks, focus)
    val layout = layoutRings(rings.center, rings.inner, rings.outer)
    val labels =
        if (rings.byRoom) {
            clusterLabels(layout.placed, { (it as? MapNode.Host)?.entry?.cluster(byRoom = true)?.title(string) }, LABEL_DISTANCE)
        } else {
            emptyList()
        }
    // The map's half side in dp at its natural size, and the scale that fits it into the frame.
    val extent = layout.radius + MARGIN + if (labels.isEmpty()) 0f else LABEL_DISTANCE
    val extentPx = with(LocalDensity.current) { extent.dp.toPx() }
    val fit = if (viewport.width > 0f) minOf(1f, viewport.width / (2 * extentPx)) else 1f
    val appear = rememberAppear(focus)
    val transform =
        rememberTransformableState { centroid, zoomChange, panChange, _ ->
            view = view.transformed(centroid - viewport.center, zoomChange, panChange, 2 * extentPx * fit, viewport)
        }
    val focusOn = { group: Group? ->
        focus = group
        view = MapView()
    }
    val description = stringResource(R.string.network_map_description, summary)
    val showList = stringResource(R.string.network_show_list)
    val radar = Radar(MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spaceS)) {
        focus?.let { FocusBar(it.title(string)) { focusOn(null) } }
        HudFrame(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .onSizeChanged { viewport = it.toSize() }
                .clearAndSetSemantics {
                    contentDescription = description
                    customActions =
                        listOf(
                            CustomAccessibilityAction(showList) {
                                onShowList()
                                true
                            },
                        )
                }.transformable(transform, canPan = { view.zoom > 1f })
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { view = MapView() }) },
            contentPadding = 0.dp,
        ) {
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.background)),
                ),
            )
            Box(
                Modifier
                    .align(Alignment.Center)
                    .requiredSize((2 * extent).dp)
                    .graphicsLayer {
                        val scale = fit * view.zoom * appear.value
                        scaleX = scale
                        scaleY = scale
                        translationX = view.pan.x
                        translationY = view.pan.y
                        alpha = (appear.value - ZOOM_FROM) / (1f - ZOOM_FROM)
                    },
            ) {
                Canvas(Modifier.matchParentSize()) { drawRadar(layout, radar) { it.solid(now) } }
                val origin = Offset(extent, extent)
                for (placed in layout.placed) {
                    val node = placed.node
                    Node(node, origin + placed.at, emphasis = placed.ring == 0, now, string) {
                        when (node) {
                            is MapNode.Host -> onNavigate(node.entry.host.detail)
                            is MapNode.Dekoi -> onNavigate(Route.Dekoi(node.device.id))
                            is MapNode.Bubble -> focusOn(node.group)
                        }
                    }
                }
                labels.forEach { (text, at) -> ClusterLabel(text, origin + at) }
            }
        }
        Text(
            stringResource(R.string.network_map_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scales the map up into place when it first shows and whenever [focus] changes; at once when animations are off. */
@Composable
private fun rememberAppear(focus: Group?): Animatable<Float, *> {
    val reduceMotion = LocalReduceMotion.current
    // Starts small, so the first frame is not drawn at full size and then snapped back.
    val appear = remember { Animatable(if (reduceMotion) 1f else ZOOM_FROM) }
    LaunchedEffect(focus) {
        if (reduceMotion) return@LaunchedEffect
        appear.snapTo(ZOOM_FROM)
        appear.animateTo(1f, tween(ZOOM_MS, easing = FastOutSlowInEasing))
    }
    return appear
}

/** Spokes are solid to what DeKoi saw recently and dashed to the rest. */
private fun MapNode.solid(now: Instant): Boolean =
    when (this) {
        is MapNode.Host -> entry.online
        is MapNode.Dekoi -> device.isOnline(now)
        is MapNode.Bubble -> true
    }

private class Radar(
    accent: Color,
) {
    val ring = accent.copy(alpha = RING_ALPHA)
    val spoke = accent.copy(alpha = SPOKE_ALPHA)
}

/** A faint ring through every orbit, and a spoke from the centre to each node, stopping at the tiles. */
private fun DrawScope.drawRadar(
    layout: MapLayout<MapNode>,
    radar: Radar,
    solid: (MapNode) -> Boolean,
) {
    val unit = 1.dp.toPx()
    val line = Dimens.hairline.toPx()
    val tile = Dimens.tile.toPx() / 2
    val dash = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx()))
    layout.placed
        .map { round(it.at.getDistance()) }
        .filter { it > 0f }
        .distinct()
        .forEach { drawCircle(radar.ring, it * unit, center, style = Stroke(line)) }
    val hub = layout.placed.any { it.ring == 0 }
    if (!hub) drawCircle(radar.spoke, HUB.toPx(), center)
    for (placed in layout.placed) {
        if (placed.ring == 0) continue
        val direction = placed.at / placed.at.getDistance()
        val start = center + direction * (if (hub) tile else 0f)
        val end = center + placed.at * unit - direction * tile
        drawLine(radar.spoke, start, end, line, pathEffect = if (solid(placed.node)) null else dash)
    }
}

/** A node with its caption under it, its tile centred on [at] (dp from the map's corner). */
@Composable
private fun Node(
    node: MapNode,
    at: Offset,
    emphasis: Boolean,
    now: Instant,
    string: (Int) -> String,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val edge = if (emphasis) accent else accent.copy(alpha = EDGE_ALPHA)
    Column(
        Modifier
            .offset((at.x - PITCH / 2).dp, at.y.dp - Dimens.tile / 2)
            .width(PITCH.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val caption =
            when (node) {
                is MapNode.Host -> {
                    val entry = node.entry
                    NodeMark(edge, entry.attention, entry.new, entry.online, Modifier.sharedKindTile(entry.host.detail)) {
                        Icon(
                            painterResource(kindIcon(entry.host.kind)),
                            contentDescription = null,
                            modifier = Modifier.size(GLYPH),
                            tint = if (entry.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    entry.name
                }

                is MapNode.Dekoi -> {
                    NodeMark(accent, attention = null, new = false, online = node.device.isOnline(now)) { BrandTile() }
                    node.device.name
                }

                is MapNode.Bubble -> {
                    val attention = node.hosts.mapNotNull { it.attention }.minOrNull()
                    NodeMark(edge, attention, node.hosts.any { it.new }, online = null) {
                        Text(node.hosts.size.toString(), style = MaterialTheme.typography.labelLarge.tabular())
                    }
                    node.group.title(string)
                }
            }
        Text(
            caption,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A round tile on the background with a hairline [edge], a halo in the
 * colour of a recent alert, a "New" badge, and a status dot unless
 * [online] is null. [tile] goes first on the round tile itself, for a
 * shared element.
 */
@Composable
private fun NodeMark(
    edge: Color,
    attention: Severity?,
    new: Boolean,
    online: Boolean?,
    tile: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.size(Dimens.tile).alertHalo(attention)) {
        Box(
            tile
                .matchParentSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background)
                .border(Dimens.hairline, edge, CircleShape),
            contentAlignment = Alignment.Center,
            content = content,
        )
        online?.let { StatusDot(it, Modifier.align(Alignment.BottomEnd)) }
        if (new) {
            Tag(
                stringResource(R.string.network_new),
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = BADGE_SHIFT, y = -BADGE_SHIFT / 2)
                    .wrapContentSize(unbounded = true)
                    .background(MaterialTheme.colorScheme.background, MaterialTheme.shapes.small),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** The name of a cluster, centred on [at] (dp from the map's corner). Rooms are owner text: plain text only. */
@Composable
private fun ClusterLabel(
    text: String,
    at: Offset,
) {
    Text(
        text.uppercase(),
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            layout(placeable.width, placeable.height) {
                placeable.place(at.x.dp.roundToPx() - placeable.width / 2, at.y.dp.roundToPx() - placeable.height / 2)
            }
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
        maxLines = 1,
    )
}

@Composable
private fun FocusBar(
    title: String,
    onShowAll: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onShowAll) { Text(stringResource(R.string.network_show_all)) }
    }
}
