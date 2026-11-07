package no.cyberfish.dekoi.ui.network

import kotlin.test.Test
import kotlin.test.assertEquals

private val COMMON = listOf("Living room", "Kitchen", "Bedroom", "Office", "Hallway", "Garage", "Other")

class RoomsTest {
    private fun names(areas: List<RoomArea>) = areas.map { area -> area.room to area.entries.map { it.name } }

    private fun rooms(rows: List<List<RoomArea>>) = rows.map { row -> row.map { it.room } }

    private fun area(
        room: String?,
        devices: Int,
    ) = RoomArea(room, entries((1..devices).map { host("$room $it", room = room) }))

    @Test
    fun `common rooms keep their order, then the owner's own rooms alphabetically, then devices not placed`() {
        val hosts =
            listOf(
                host("01", hostname = "laptop"),
                host("02", hostname = "tv", room = "Living room"),
                host("03", hostname = "printer", room = "Office"),
                host("04", hostname = "console", room = "Kid's room"),
                host("05", hostname = "fridge", room = "Kitchen"),
                host("06", hostname = "nas", room = "attic"),
                host("07", hostname = "bike", room = "Other"),
            )
        assertEquals(
            listOf(
                "Living room" to listOf("tv"),
                "Kitchen" to listOf("fridge"),
                "Office" to listOf("printer"),
                "Other" to listOf("bike"),
                "attic" to listOf("nas"),
                "Kid's room" to listOf("console"),
                null to listOf("laptop"),
            ),
            names(roomAreas(entries(hosts), COMMON)),
        )
    }

    @Test
    fun `a room is one area whatever its case, named as the common room`() {
        val hosts =
            listOf(
                host("01", hostname = "tv", room = "living room"),
                host("02", hostname = "speaker", room = "Living room"),
                host("03", hostname = "lamp", room = "Den"),
                host("04", hostname = "radio", room = "den"),
            )
        val areas = roomAreas(entries(hosts), COMMON)
        assertEquals(listOf("Living room", "Den"), areas.map { it.room })
        assertEquals(listOf(listOf("speaker", "tv"), listOf("lamp", "radio")), areas.map { area -> area.entries.map { it.name } })
    }

    @Test
    fun `devices in an area are in kind order, network gear first, then by name`() {
        val hosts =
            listOf(
                host("01", kind = "tv", hostname = "b tv", room = "Office"),
                host("02", kind = "computer", hostname = "laptop", room = "Office"),
                host("03", kind = "tv", hostname = "a tv", room = "Office"),
                host("04", kind = "router", hostname = "router", room = "Office"),
            )
        assertEquals(listOf("router", "laptop", "a tv", "b tv"), roomAreas(entries(hosts), COMMON).single().entries.map { it.name })
    }

    @Test
    fun `before anything is placed there is only the area of devices not placed`() {
        val hosts = listOf(host("01", kind = "tv", hostname = "tv"), host("02", kind = "router", hostname = "router"))
        assertEquals(listOf(null to listOf("router", "tv")), names(roomAreas(entries(hosts), COMMON)))
    }

    @Test
    fun `once everything is placed there is no area of devices not placed`() {
        val hosts = listOf(host("01", hostname = "tv", room = "Living room"), host("02", hostname = "fridge", room = "Kitchen"))
        assertEquals(listOf("Living room", "Kitchen"), roomAreas(entries(hosts), COMMON).map { it.room })
    }

    @Test
    fun `no devices means no areas`() {
        assertEquals(emptyList(), roomAreas(emptyList(), COMMON))
        assertEquals(emptyList(), floorPlan(emptyList(), columns = 2))
    }

    @Test
    fun `devices not placed are those without a room, in the order of the Rooms view`() {
        val hosts =
            listOf(
                host("01", kind = "tv", hostname = "tv"),
                host("02", kind = "phone", hostname = "phone", room = "Bedroom"),
                host("03", kind = "router", hostname = "router"),
            )
        val all = entries(hosts)
        assertEquals(listOf("router", "tv"), notPlaced(all).map { it.name })
        assertEquals(roomAreas(all, COMMON).last().entries, notPlaced(all))
    }

    @Test
    fun `room choices are the common rooms, then the owner's own once each, alphabetically`() {
        assertEquals(
            COMMON + listOf("attic", "Den"),
            roomChoices(listOf("Den", "kitchen", "attic", "den", "Kitchen"), COMMON),
        )
        assertEquals(COMMON, roomChoices(emptyList(), COMMON))
    }

    @Test
    fun `small rooms share a row, two at most`() {
        val areas = listOf(area("Living room", 1), area("Kitchen", 2), area("Bedroom", 4), area("Office", 3), area("Hallway", 1))
        assertEquals(
            listOf(listOf("Living room", "Kitchen"), listOf("Bedroom", "Office"), listOf("Hallway")),
            rooms(floorPlan(areas, columns = 2)),
        )
    }

    @Test
    fun `a room with more than four devices and the devices not placed take a whole row`() {
        val areas = listOf(area("Living room", 1), area("Kitchen", 5), area("Bedroom", 2), area("Office", 2), area(null, 1))
        assertEquals(
            listOf(listOf("Living room"), listOf("Kitchen"), listOf("Bedroom", "Office"), listOf(null)),
            rooms(floorPlan(areas, columns = 2)),
        )
    }

    @Test
    fun `in one column every area has a row of its own`() {
        val areas = listOf(area("Living room", 1), area("Kitchen", 1), area(null, 1))
        assertEquals(listOf(listOf("Living room"), listOf("Kitchen"), listOf(null)), rooms(floorPlan(areas, columns = 1)))
    }
}
