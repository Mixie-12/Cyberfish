package no.cyberfish.dekoi.ui.network

/**
 * One area of the home plan: the devices the owner put in [room], or the
 * devices not placed in a room yet when [room] is null. A room is owner
 * text: plain text only.
 */
data class RoomArea(
    val room: String?,
    val entries: List<HostEntry>,
)

/** A room with more devices than this takes a whole row of the home plan. */
private const val SMALL_ROOM = 4

/**
 * Every room once, whatever its case: the [common] rooms in their order
 * (Living room first, Other last), then the owner's own rooms from [inUse]
 * in alphabetical order. The home plan shows its rooms in this order, and
 * the place flow offers them in it.
 */
fun roomChoices(
    inUse: List<String>,
    common: List<String>,
): List<String> {
    val own = inUse.filter { room -> common.none { it.equals(room, ignoreCase = true) } }.distinctBy { it.lowercase() }
    return common + own.sortedWith(String.CASE_INSENSITIVE_ORDER)
}

/** The devices without a room, in the order the Rooms view shows them and the place flow goes through them. */
fun notPlaced(entries: List<HostEntry>): List<HostEntry> = entries.filter { it.host.room == null }.sortedWith(kindOrder)

/**
 * The home plan: one area per room in use, in [roomChoices] order and
 * matched whatever its case, then the devices not placed yet. Devices are
 * in [kindOrder] within an area; areas without devices are left out.
 */
fun roomAreas(
    entries: List<HostEntry>,
    common: List<String>,
): List<RoomArea> {
    val byRoom = entries.sortedWith(kindOrder).groupBy { it.host.room?.lowercase() }
    val rooms = roomChoices(entries.mapNotNull { it.host.room }, common)
    val placed = rooms.mapNotNull { room -> byRoom[room.lowercase()]?.let { RoomArea(room, it) } }
    return placed + listOfNotNull(notPlaced(entries).ifEmpty { null }?.let { RoomArea(null, it) })
}

/**
 * The [areas] in rows of up to [columns], in order, as rooms on a floor
 * plan: a room with more than four devices, and the devices not placed
 * yet, take a row of their own; smaller rooms share a row with the small
 * room next to them.
 */
fun floorPlan(
    areas: List<RoomArea>,
    columns: Int,
): List<List<RoomArea>> =
    buildList<MutableList<RoomArea>> {
        for (area in areas) {
            val row = lastOrNull()
            if (row != null && row.size < columns && (row + area).none { it.wide }) row += area else add(mutableListOf(area))
        }
    }

private val RoomArea.wide: Boolean get() = room == null || entries.size > SMALL_ROOM
