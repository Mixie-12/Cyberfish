package no.cyberfish.dekoi.ui.network

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.ui.Dimens
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Above this many hosts the outer ring shows one bubble per cluster instead of every host. */
const val MAP_MAX_HOSTS = 40

/** Something the map draws. */
sealed interface MapNode {
    data class Host(
        val entry: HostEntry,
    ) : MapNode

    data class Dekoi(
        val device: Device,
    ) : MapNode

    /** Every host of one kind or room as a single bubble. */
    data class Bubble(
        val group: Group,
        val hosts: List<HostEntry>,
    ) : MapNode
}

/**
 * The gateway router in the centre, network gear and DeKoi around it, and
 * everything else outside, clustered by room once the owner has given any
 * host a room ([byRoom]), else by kind.
 */
class MapRings(
    val center: MapNode.Host?,
    val inner: List<MapNode>,
    val outer: List<MapNode>,
    val byRoom: Boolean,
)

private val NETWORK_GEAR = setOf(HostKind.ROUTER, HostKind.SWITCH, HostKind.ACCESS_POINT)

/** The cluster a host belongs to on the outer ring. */
fun HostEntry.cluster(byRoom: Boolean): Group = if (byRoom) Group.Room(host.room) else Group.Kind(host.kind)

/**
 * Sorts hosts onto the rings. The centre is the router of DeKoi's own
 * network, else of any network, else any host that looks like a router.
 * With [focus] the outer ring shows only the hosts of that cluster.
 */
fun mapRings(
    entries: List<HostEntry>,
    dekois: List<Device>,
    networks: List<Network>,
    focus: Group?,
): MapRings {
    val gateways = networks.sortedBy { it.source != "interface" }.mapNotNull { it.gateway }
    val center =
        gateways.firstNotNullOfOrNull { ip -> entries.firstOrNull { it.host.ip == ip } }
            ?: entries.firstOrNull { it.host.kind == HostKind.ROUTER }
    val rooms = entries.any { it.host.room != null }
    val order = if (rooms) roomOrder.then(kindOrder) else kindOrder
    val (gear, others) = entries.filter { it != center }.sortedWith(order).partition { it.host.kind in NETWORK_GEAR }
    val outer =
        when {
            focus != null -> others.filter { it.cluster(rooms) == focus }.map(MapNode::Host)
            entries.size > MAP_MAX_HOSTS -> others.groupBy { it.cluster(rooms) }.map { (group, hosts) -> MapNode.Bubble(group, hosts) }
            else -> others.map(MapNode::Host)
        }
    return MapRings(center?.let(MapNode::Host), dekois.map(MapNode::Dekoi) + gear.map(MapNode::Host), outer, rooms)
}

/** A node and where it sits, in dp from the centre of the map. [ring] is 0 for the centre, then 1 and 2 outwards. */
class Placed<T>(
    val node: T,
    val at: Offset,
    val ring: Int,
)

/** Every node placed, and how far from the centre the outermost tile reaches, in dp. */
class MapLayout<T>(
    val placed: List<Placed<T>>,
    val radius: Float,
)

private const val TAU = (2 * PI).toFloat()

// In dp. Tiles are 40 dp with a one-line caption under them; neighbours on
// a ring sit PITCH apart, and the rings sit far enough apart that captions
// and the spokes between them stay clear.
private val TILE = Dimens.tile.value
internal const val PITCH = 64f
private const val INNER_RADIUS = 80f
private const val RING_GAP = 72f
private const val ORBIT_GAP = 64f

/**
 * Places [center] at the origin, [inner] on a ring around it and [outer]
 * on as few orbits around that as hold them. Each ring starts at the top
 * and runs clockwise, so neighbours in the lists stay neighbours on the map.
 */
fun <T> layoutRings(
    center: T?,
    inner: List<T>,
    outer: List<T>,
): MapLayout<T> {
    val innerRadius = max(INNER_RADIUS, inner.size * PITCH / TAU)
    val placed = mutableListOf<Placed<T>>()
    center?.let { placed += Placed(it, Offset.Zero, 0) }
    placed += around(inner, innerRadius, ring = 1, turn = 0f)
    var from = 0
    orbitSizes(outer.size, innerRadius + RING_GAP).forEachIndexed { orbit, size ->
        // Half a step round, so the outer ring's spokes pass between the inner tiles.
        placed += around(outer.subList(from, from + size), innerRadius + RING_GAP + orbit * ORBIT_GAP, ring = 2, turn = 0.5f)
        from += size
    }
    return MapLayout(placed, (placed.maxOfOrNull { it.at.getDistance() } ?: 0f) + TILE / 2)
}

/** How many of [count] nodes go on each orbit, the first at radius [first]: as few orbits as hold them, filled evenly. */
fun orbitSizes(
    count: Int,
    first: Float,
): List<Int> {
    val capacities = mutableListOf<Int>()
    while (capacities.sum() < count) capacities += capacity(first + capacities.size * ORBIT_GAP)
    var left = count
    var room = capacities.sum()
    return capacities.map { capacity ->
        // Rounded up, so the last orbit takes exactly what is left.
        val share = (left * capacity + room - 1) / room
        left -= share
        room -= capacity
        share
    }
}

private fun capacity(radius: Float): Int = max(1, (TAU * radius / PITCH).toInt())

private fun <T> around(
    nodes: List<T>,
    radius: Float,
    ring: Int,
    turn: Float,
): List<Placed<T>> {
    val step = TAU / nodes.size
    return nodes.mapIndexed { i, node ->
        val angle = -TAU / 4 + step * (i + turn)
        Placed(node, Offset(radius * cos(angle), radius * sin(angle)), ring)
    }
}

/**
 * Where to write the name of each cluster on the outer ring: [distance]
 * beyond the outermost node, in line with the middle node of the cluster.
 * Nodes whose [name] is null get no label.
 */
fun <T> clusterLabels(
    placed: List<Placed<T>>,
    name: (T) -> String?,
    distance: Float,
): List<Pair<String, Offset>> {
    val outer = placed.filter { it.ring == 2 }
    val reach = (outer.maxOfOrNull { it.at.getDistance() } ?: return emptyList()) + distance
    return outer.groupBy { name(it.node) }.mapNotNull { (label, members) ->
        val middle = members[members.size / 2].at
        label?.let { it to middle * (reach / middle.getDistance()) }
    }
}

private const val MAX_ZOOM = 4f

/** How far the owner zoomed in (1 = the whole map fits) and moved the map, in pixels. */
data class MapView(
    val zoom: Float = 1f,
    val pan: Offset = Offset.Zero,
) {
    /**
     * After a gesture: zooms by [zoomChange] around [centroid] (from the
     * viewport's centre), moves by [panChange], and keeps the map's
     * [content] (its side at zoom 1) covering as much of [viewport] as it can.
     */
    fun transformed(
        centroid: Offset,
        zoomChange: Float,
        panChange: Offset,
        content: Float,
        viewport: Size,
    ): MapView {
        val zoom = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
        val moved = (pan - centroid) * (zoom / this.zoom) + centroid + panChange
        val side = content * zoom
        val limitX = max(0f, (side - viewport.width) / 2)
        val limitY = max(0f, (side - viewport.height) / 2)
        return MapView(zoom, Offset(moved.x.coerceIn(-limitX, limitX), moved.y.coerceIn(-limitY, limitY)))
    }
}
