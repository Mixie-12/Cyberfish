package no.cyberfish.dekoi.ui.network

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import no.cyberfish.dekoi.Device
import no.cyberfish.dekoi.HostKind
import no.cyberfish.dekoi.Network
import no.cyberfish.dekoi.NetworkHost
import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLayoutTest {
    private val seen = Instant.parse("2026-11-07T12:00:00Z")
    private val dekoi = Device(id = "d", name = "DeKoi", createdAt = seen)

    private fun network(
        cidr: String,
        source: String,
        gateway: String?,
    ) = Network("d", cidr, source, gateway, seen, seen)

    private fun MapNode.mac(): String =
        when (this) {
            is MapNode.Host -> entry.host.mac
            is MapNode.Dekoi -> "dekoi"
            is MapNode.Bubble -> "$group:${hosts.size}"
        }

    private fun rings(
        hosts: List<NetworkHost>,
        focus: Group? = null,
    ) = mapRings(entries(hosts), emptyList(), emptyList(), focus)

    @Test
    fun `the router of DeKoi's own network is the centre`() {
        val hosts =
            entries(
                listOf(
                    host("other-router", kind = "router", ip = "10.0.0.1"),
                    host("home-router", kind = "router", ip = "192.168.1.1"),
                ),
            )
        val networks = listOf(network("10.0.0.0/24", "dhcp_route", "10.0.0.1"), network("192.168.1.0/24", "interface", "192.168.1.1"))
        val rings = mapRings(hosts, listOf(dekoi), networks, focus = null)
        assertEquals("home-router", rings.center?.mac())
        assertEquals(listOf("dekoi", "other-router"), rings.inner.map { it.mac() })
    }

    @Test
    fun `without a known gateway any router is the centre, and without one there is none`() {
        assertEquals("r", rings(listOf(host("tv", kind = "tv"), host("r", kind = "router"))).center?.mac())
        assertNull(rings(listOf(host("tv", kind = "tv"))).center)
    }

    @Test
    fun `network gear and DeKoi sit inside, everything else outside, clustered by kind`() {
        val hosts =
            entries(
                listOf(
                    host("tv-2", kind = "tv", hostname = "b"),
                    host("ap", kind = "access_point"),
                    host("phone", kind = "phone"),
                    host("tv-1", kind = "tv", hostname = "a"),
                    host("switch", kind = "switch"),
                ),
            )
        val rings = mapRings(hosts, listOf(dekoi), emptyList(), focus = null)
        assertEquals(listOf("dekoi", "switch", "ap"), rings.inner.map { it.mac() })
        assertEquals(listOf("phone", "tv-1", "tv-2"), rings.outer.map { it.mac() })
        assertEquals(false, rings.byRoom)
    }

    @Test
    fun `once a host has a room, the outer ring is clustered by room, then kind`() {
        val hosts =
            listOf(
                host("tv", kind = "tv", room = "Living room"),
                host("phone", kind = "phone"),
                host("speaker", kind = "speaker", room = "Kitchen"),
                host("console", kind = "game_console", room = "Living room"),
            )
        val rings = rings(hosts)
        assertEquals(true, rings.byRoom)
        assertEquals(listOf("speaker", "tv", "console", "phone"), rings.outer.map { it.mac() })
        assertEquals(listOf("tv", "console"), rings(hosts, focus = Group.Room("Living room")).outer.map { it.mac() })
    }

    @Test
    fun `a big network shows one bubble per cluster, and a focused cluster shows its hosts`() {
        val phones = (1..30).map { host("phone-$it", kind = "phone") }
        val tvs = (1..11).map { host("tv-$it", kind = "tv") }
        assertEquals(
            listOf("${Group.Kind(HostKind.PHONE)}:30", "${Group.Kind(HostKind.TV)}:11"),
            rings(phones + tvs).outer.map { it.mac() },
        )
        assertEquals(11, rings(phones + tvs, focus = Group.Kind(HostKind.TV)).outer.size)
        assertEquals(40, rings(phones + tvs.drop(1)).outer.size)
    }

    @Test
    fun `the centre is at the origin and the rings start at the top`() {
        val layout = layoutRings("router", listOf("dekoi"), listOf("tv"))
        val at = layout.placed.associate { it.node to it.at }
        assertEquals(Offset.Zero, at["router"])
        assertClose(Offset(0f, -80f), at.getValue("dekoi"))
        // A single outer node sits half a turn on, at the bottom.
        assertClose(Offset(0f, 152f), at.getValue("tv"))
        assertEquals(152f + 20f, layout.radius, 0.01f)
    }

    @Test
    fun `no two tiles or captions overlap, however many hosts there are`() {
        for (count in listOf(0, 1, 5, 14, 15, 40, 120)) {
            val layout = layoutRings("centre", (1..6).map { "inner-$it" }, (1..count).map { "outer-$it" })
            assertEquals(count + 7, layout.placed.size)
            for ((i, a) in layout.placed.withIndex()) {
                for (b in layout.placed.drop(i + 1)) {
                    assertTrue((a.at - b.at).getDistance() >= 60f, "$count hosts: ${a.node} and ${b.node} overlap")
                }
            }
        }
    }

    @Test
    fun `a crowded inner ring grows instead of overlapping`() {
        val layout = layoutRings(null, (1..20).map { "gear-$it" }, emptyList())
        val radius =
            layout.placed
                .first()
                .at
                .getDistance()
        assertTrue(radius > 80f)
        assertEquals(radius + 20f, layout.radius, 0.01f)
    }

    @Test
    fun `outer hosts fill as few orbits as hold them, evenly`() {
        assertEquals(emptyList(), orbitSizes(0, 152f))
        assertEquals(listOf(14), orbitSizes(14, 152f))
        assertEquals(listOf(8, 12), orbitSizes(20, 152f))
        val many = orbitSizes(120, 152f)
        assertEquals(120, many.sum())
        assertTrue(many.zipWithNext().all { (inner, outer) -> inner <= outer })
    }

    @Test
    fun `cluster names sit outside the map, in line with the middle of their cluster`() {
        val layout = layoutRings(null, emptyList(), listOf("a-1", "a-2", "a-3", "b-1", "none"))
        val labels = clusterLabels(layout.placed, { it.substringBefore('-').takeIf { name -> name != "none" } }, 40f)
        assertEquals(listOf("a", "b"), labels.map { it.first })
        val middleOfA = layout.placed.first { it.node == "a-2" }.at
        val label = labels.first().second
        assertEquals(152f + 40f, label.getDistance(), 0.01f)
        assertClose(middleOfA / middleOfA.getDistance(), label / label.getDistance())
        assertEquals(emptyList(), clusterLabels(layoutRings("centre", listOf("x"), emptyList<String>()).placed, { it }, 40f))
    }

    @Test
    fun `zooming keeps the point under the fingers in place`() {
        val viewport = Size(300f, 300f)
        val view = MapView().transformed(Offset(50f, 0f), 2f, Offset.Zero, content = 300f, viewport = viewport)
        assertEquals(2f, view.zoom)
        // The content point under the fingers was 50 px right of the centre; at zoom 2 it would be 100 px.
        assertClose(Offset(-50f, 0f), view.pan)
    }

    @Test
    fun `zoom stays between fitting the map and four times that, and the map stays on screen`() {
        val viewport = Size(300f, 300f)
        assertEquals(1f, MapView().transformed(Offset.Zero, 0.5f, Offset.Zero, 300f, viewport).zoom)
        assertEquals(4f, MapView().transformed(Offset.Zero, 10f, Offset.Zero, 300f, viewport).zoom)
        assertEquals(Offset.Zero, MapView().transformed(Offset.Zero, 1f, Offset(80f, 80f), 300f, viewport).pan)
        val zoomed = MapView(zoom = 2f).transformed(Offset.Zero, 1f, Offset(1000f, -1000f), 300f, viewport)
        assertEquals(Offset(150f, -150f), zoomed.pan)
    }

    private fun assertClose(
        expected: Offset,
        actual: Offset,
    ) = assertTrue(abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f, "expected $expected, was $actual")
}
