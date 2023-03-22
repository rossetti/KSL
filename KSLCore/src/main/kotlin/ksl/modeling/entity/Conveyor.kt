/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.modeling.entity

import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import org.jetbrains.kotlinx.dataframe.impl.asList

/**
 * This data class represents the origin [entryLocation] and destination [exitLocation] of a [length]
 * of a segment for a conveyor. See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
data class Segment(val entryLocation: IdentityIfc, val exitLocation: IdentityIfc, val length: Int) {
    init {
        require(entryLocation != exitLocation) { "The start and the end of the segment must be different!" }
        require(length >= 1) { "The length of the segment must be >= 1 unit" }
    }

    override fun toString(): String {
        return "(start = ${entryLocation.name} --> end = ${exitLocation.name} : length = $length)"
    }

    val name: String
        get() = "${entryLocation.name}-${exitLocation.name}"

}

/**
 * This class represents the data associated with segments of a conveyor and facilitates
 * the specification of segments for a conveyor.  See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
class Segments(val cellSize: Int = 1, val firstLocation: IdentityIfc) {
    private val mySegments = mutableListOf<Segment>()
    private val myDownStreamLocations: MutableMap<IdentityIfc, MutableList<IdentityIfc>> = mutableMapOf()

    var lastLocation: IdentityIfc = firstLocation
        private set
    var minimumSegmentLength = Integer.MAX_VALUE
        private set
    val segments: List<Segment>
        get() = mySegments

    fun toLocation(next: IdentityIfc, length: Int) {
        require(length >= 1) { "The length ($length) of the segment must be >= 1 unit" }
        require(next != lastLocation) { "The next location (${next.name}) as the last location (${lastLocation.name})" }
        require(length % cellSize == 0) { "The length of the segment ($length) was not an integer multiple of the cell size ($cellSize)" }
        val sd = Segment(lastLocation, next, length)
        mySegments.add(sd)
        if (length <= minimumSegmentLength) {
            minimumSegmentLength = length
        }
        lastLocation = next
        if (!myDownStreamLocations.containsKey(sd.entryLocation)) {
            myDownStreamLocations[sd.entryLocation] = mutableListOf()
        }
        for (loc in entryLocations) {
            myDownStreamLocations[loc]?.add(sd.exitLocation)
        }
    }

    val entryLocations: List<IdentityIfc>
        get() {
            val list = mutableListOf<IdentityIfc>()
            for (seg in mySegments) {
                list.add(seg.entryLocation)
            }
            return list
        }

    val exitLocations: List<IdentityIfc>
        get() {
            val list = mutableListOf<IdentityIfc>()
            for (seg in mySegments) {
                list.add(seg.exitLocation)
            }
            return list
        }

    val isCircular: Boolean
        get() = firstLocation == lastLocation

    fun isReachable(start: IdentityIfc, end: IdentityIfc): Boolean {
        if (!entryLocations.contains(start))
            return false
        if (!exitLocations.contains(end))
            return false
        if (isCircular)
            return true
        return myDownStreamLocations[start]!!.contains(end)
    }

    val totalLength: Int
        get() {
            var sum = 0
            for (seg in mySegments) {
                sum = sum + seg.length
            }
            return sum
        }

    fun isEmpty(): Boolean {
        return mySegments.isEmpty() || lastLocation == firstLocation
    }

    fun isNotEmpty() = !isEmpty()

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("first location = ${firstLocation.name}")
        sb.appendLine("last location = ${lastLocation.name}")
        for ((i, segment) in mySegments.withIndex()) {
            sb.appendLine("Segment: ${(i + 1)} = $segment")
        }
        sb.appendLine("total length = $totalLength")
        sb.appendLine("Downstream locations:")
        for ((loc, list) in myDownStreamLocations) {
            sb.appendLine(
                "${loc.name} : ${
                    list.joinToString(
                        separator = " -> ",
                        prefix = "[",
                        postfix = "]",
                        transform = { it.name })
                }"
            )
        }
        return sb.toString()
    }
}

/**
 *  A cell allocation represents the holding of cells on a conveyor and acts as
 *  a "ticket" to use the conveyor.  Once an entity has a cell allocation, the entity
 *  has control over the cells at the start of the segment associated with the entry
 *  point along the conveyor.  After receiving the cell allocation from a request to
 *  access the conveyor the entity can either ride on the conveyor or exit. The cell
 *  allocation blocks at the point of access until riding or exiting. When the entity
 *  asks to ride the conveyor then the item property is set to indicate
 *  that the allocation represents an item occupying cells on the conveyor. If the entity
 *  never rides the conveyor, then the item property stays null.  The property isWaitingToConvey
 *  indicates that the cell allocation has not asked to convey, but still has control
 *  over the front cells of the conveyor segment at the access point. Once the cell
 *  allocation is used to ride the conveyor, the isWaitingToConvey property will
 *  report false. The isAllocated property will report true until the cells are deallocated
 *  when exiting the conveyor.  Once the cell allocation has been deallocated,
 *  it cannot be used for further process interaction with the conveyor.
 */
interface CellAllocationIfc {
    val entity: ProcessModel.Entity
    val entryLocation: IdentityIfc
    val entryCell: Conveyor.Cell
    val conveyor: Conveyor
    val numberOfCells: Int

    /**
     * This indicates if the allocation is no longer waiting to convey
     * and is thus ready to ride on the conveyor
     */
    val isReadyToConvey: Boolean

    /**
     *  The time that the allocation was created
     */
    val creationTime: Double

    /**
     * When the request for the allocation was created
     */
    val requestCreateTime: Double

    val item: ConveyorItemIfc?

    /**
     *  True if the allocation is currently allocated some cells
     */
    val isAllocated: Boolean

    /**
     *  True if no units are allocated
     */
    val isDeallocated: Boolean
    override fun toString(): String
}

/**
 *  A conveyor item represents something that occupies cells on a conveyor.
 *  A conveyor item remembers its entry point (origin) on the
 *  conveyor and tracks its current location on the conveyor.  While moving
 *  on the conveyor the item has no current location. The current location is
 *  set when the item arrives at an exit location of the conveyor. The planned
 *  location represents the next exit location along the conveyor that the item is
 *  moving towards.
 */
interface ConveyorItemIfc {
    //TODO review and remove unneeded properties

    val status: Conveyor.ItemStatus //TODO is this really needed

    /**
     * The entity that needs to use the conveyor
     */
    val entity: ProcessModel.Entity

    /**
     *  The number of cells needed when riding on the conveyor
     */
    val numberOfCells: Int

    /**
     *  The location where the entity first accessed the conveyor
     */
    val origin: IdentityIfc

    /**
     * The current location of the entity. This is assigned
     * when the entity arrives at the end of a segment
     */
    val currentLocation: IdentityIfc?

    /**
     * The final location where the entity wants to visit on the conveyor
     */
    val destination: IdentityIfc?

    /**
     * The number of cells allocated from the conveyor to the entity
     */
    val numCellsAllocated: Int

    /**
     *  The number of cells currently occupied on the conveyor.
     *  This may be different from numCellsAllocated because an
     *  allocation does not need to convey. It can be used to block
     *  the conveyor at one of its entrance locations
     */
    val numCellsOccupied: Int

    val occupiesCells: Boolean

    /**
     *  True if the number of cells allocated is equal to the
     *  number of cells needed.
     */
    val isConveyable: Boolean //TODO is this needed. An item is only made when has the cells to ride

    /**
     *  The conveyor the entity is using
     */
    val conveyor: Conveyor

    /**
     * The object riding on the conveyor has a front (facing towards) its destination
     * and an end (facing where it originated). This cell is the furthermost cell
     * occupied towards the front (in the direction of travel)
     */
    val firstCell: Conveyor.Cell?

    /**
     *  This cell is the furthermost cell occupied by the object towards the where
     *  it originated.
     */
    val lastCell: Conveyor.Cell?

    /**
     *  True if the item has reached the last cell of the current segment
     */
    val hasReachedAnExitCell: Boolean

    /**
     *  Can be used when the entity is resumed
     */
    val resumePriority: Int

    /**
     *  While riding this is the location where the entity is heading
     */
    val plannedLocation: IdentityIfc?
        get() {
            return if (lastCell == null) {
                null
            } else {
                lastCell!!.segment.exitLocation
            }
        }

    /**
     *  True if the entity has reached its destination
     */
    val hasReachedDestination: Boolean
}

/** A conveyor consists of a series of segments. A segment has a starting location (origin) and an ending location
 * (destination) and is associated with a conveyor. The start and end of each segment represent locations along
 * the conveyor where entities can get on and off.  A conveyor has a cell size, which represents the length of
 * each cell on any segment of the conveyor. A conveyor also has a maximum permitted number of cells that can be
 * occupied by an item riding on the conveyor.  A conveyor has an [initialVelocity].  Each segment moves at the same
 * velocity.  Each segment has a specified length that is divided into a number of equally sized contiguous cells.
 * The length of any segment must be an integer multiple of the conveyor's cell size so that each segment will have
 * an integer number of cells to represent its length. If a segment consists of 12 cells and the length of the conveyor
 * is 12 feet then each cell represents 1 foot. If a segment consists of 4 cells and the length of the segment is 12 feet,
 * then each cell of the segment represents 3 feet.  Thus, a cell represents a generalized unit of distance along the segment.
 * Segments may have a different number of cells because they may have different lengths.
 *
 * The cells on a conveyor are numbered from 1 to n, with 1 at the entry of the first segment, and n at the exit of
 * the last segment, where n is the number of cells for the conveyor.
 *
 * Items that ride on the conveyor must be allocated cells and then occupy the cells while moving on the conveyor.  Items
 * can occupy more than one cell while riding on the conveyor.  For example, if the conveyor has 5 cells (1, 2, 3, 4, 5) and
 * the item needs 2 cells and is occupying cells 2 and 3, then the front cell associated with the item is cell 3 and the
 * end cell associated with the item is cell 2.
 *
 * An item trying to access the conveyor at a start of a segment, waits until its required number of contiguous cells
 * are available.  Then, the item is permitted to ride
 * on the conveyor by occupying cells on the conveyor. To occupy a cell, the item must move the distance represented by
 * the cell (essentially covering the cell).  An item occupies a cell during the time it traverses the cell's length.
 * Thus, assuming a single item, the time to move from the start of a segment
 * to the end of the segment is the time that it takes to travel through all the cells of the segment.
 *
 * A conveyor is considered circular if the entry location of the first segment is the same as the exit location of the last segment.
 *
 * To construct a conveyor use the supplied builder or specify the segment data.
 *
 * @param parent the containing model element
 * @param velocity the initial velocity of the conveyor
 * @param segments the specification of the segments
 * @param maxEntityCellsAllowed the maximum number of cells that an entity can occupy while riding on the conveyor
 * @param name the name of the conveyor
 */
class Conveyor(
    parent: ModelElement,
    segments: Segments,
    val conveyorType: Type = Type.ACCUMULATING,
    velocity: Double = 1.0,
    val maxEntityCellsAllowed: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(maxEntityCellsAllowed >= 1) { "The maximum number of cells that can be occupied by an entity must be >= 1" }
        require(segments.isNotEmpty()) { "The segment data must not be empty." }
        require(velocity > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
    }

    enum class Type {
        ACCUMULATING, NON_ACCUMULATING
    }

    enum class Status {
        MOVING, BLOCKED_ENTERING, BLOCKED_EXITING, IDLE, UNBLOCKED_ENTERING
    }

    var initialVelocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
            field = value
        }

    /**
     *  This holds the entities that are suspended because they are currently
     *  using the conveyor in some fashion that causes them to be suspended.
     *  For example, this is used to resume the entity's process
     *  after the entity reaches its destination on the conveyor. The process is
     *  then resumed and the entity can decide to exit the conveyor, experience
     *  a process while on the conveyor, or continue riding to another destination.
     *  Because this queue is internal to the conveyor, statistics are not collected.
     *
     */
    internal val conveyorHoldQ = HoldQueue(this, "${this.name}:HoldQ")

    init {
        conveyorHoldQ.waitTimeStatOption = false
        conveyorHoldQ.defaultReportingOption = false
    }

    private val segmentData: Segments = segments
    val cellSize: Int = segmentData.cellSize

    private val conveyorCells: List<Cell>
    private val entryCells = mutableMapOf<IdentityIfc, Cell>()
    private val exitCells = mutableMapOf<IdentityIfc, Cell>()
    private val accessQueues = mutableMapOf<IdentityIfc, ConveyorQ>()

    init {
        val cells = mutableListOf<Cell>()
        for (segment in segmentData.segments) {
            val numCells = segment.length / cellSize
            for (i in 1..numCells) {
                if (i == 1) {
                    val cell = Cell(CellType.ENTRY, segment.entryLocation, segment, cells)
                    entryCells[segment.entryLocation] = cell
                    accessQueues[segment.entryLocation] =
                        ConveyorQ(this, "${this.name}:${segment.entryLocation.name}:AccessQ")
                } else if (i == numCells) {
                    val cell = Cell(CellType.EXIT, segment.exitLocation, segment, cells)
                    exitCells[segment.exitLocation] = cell
                } else {
                    Cell(CellType.INNER, null, segment, cells)
                }
            }
        }
        require(cells.size >= 2) { "There must be 2 or more cells for the conveyor" }
        if (cells.size == 2){
            require(cells.first().isEntryCell && cells.last().isExitCell){"With 2 cells, there must be an entry cell and an exit cell."}
        }
        conveyorCells = cells.asList()
    }

    private val endCellTraversalAction = EndOfCellTraversalAction()

    /**
     * The event associated with the movement of the lead item on the segment
     */
    private var endCellTraversalEvent: KSLEvent<Cell>? = null

    fun accessQueueAt(location: IdentityIfc): QueueCIfc<CellRequest> {
        require(accessQueues.contains(location)) { "The origin ($location) is not a valid entry point on the conveyor" }
        return accessQueues[location]!!
    }

    var velocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The velocity of the conveyor must be > 0.0" }
            field = value
        }

    val cellTravelTime: Double
        get() = cellSize / velocity

    /**
     *  Indicates if the conveyor's segments form a loop such that
     *  the location associated with the first segment is the same
     *  as the ending location of the last segment
     */
    val isCircular = segmentData.isCircular

    /**
     *  The locations that can be used to enter (get on) the conveyor.
     */
    val entryLocations = segmentData.entryLocations

    /**
     *  The locations that can be used as points of exit on the conveyor.
     */
    val exitLocations = segmentData.exitLocations

    /**
     *  Determines if the [end] location is reachable from the [start] location.
     *  A location is reachable if the item can ride on the conveyor from
     *  the start location to the end location without exiting the conveyor.
     */
    fun isReachable(start: IdentityIfc, end: IdentityIfc): Boolean {
        return segmentData.isReachable(start, end)
    }

    /**
     * A list of all items that are currently associated with (occupying)
     * cells on the conveyor in the order from the furthest along (closest to the exit location of the last segment)
     * to the nearest item (closest to the entry location of the first segment).
     */
    fun items(): List<Item> {
        return items(conveyorCells)
    }

    /**
     *  Returns a list of items that are associated with
     *  the supplied cell list, ordered from the furthest cell to the closest cell
     *  based on the direction of travel on the conveyor.
     */
    private fun items(cellList: List<Cell>): List<Item> {
        val list = mutableSetOf<Item>()
        for (cell in cellList.reversed()) {
            val item = cell.item
            if (item != null) {
                list.add(item)
            }
        }
        return list.asList()
    }

    /**
     * If any of the cells of the conveyor is occupied by an item
     * then return true; otherwise, if there are no items occupying
     * cells return false.
     */
    fun isOccupied(): Boolean {
        for (cell in conveyorCells) {
            if (cell.isOccupied) {
                return true
            }
        }
        return false
    }

    /**
     *  Returns a list of the cells that are blocked in the
     *  order of cell number.
     */
    fun blockedCells(): List<Cell> {
        return conveyorCells.filter { it.isBlocked }
    }

    fun blockedEntryCells(): Map<IdentityIfc, Cell> {
        return entryCells.filterValues { it.isBlocked }
    }

    fun blockedExitCells(): Map<IdentityIfc, Cell> {
        return exitCells.filterValues { it.isBlocked }
    }

    /**
     *  Checks if the conveyor has any blocked cells
     */
    fun hasBlockedCells(): Boolean {
        for (cell in conveyorCells) {
            if (cell.isBlocked) {
                return true
            }
        }
        return false
    }

    fun hasNoBlockedCells(): Boolean = !hasBlockedCells()

    fun firstBlockedCellFromEnd(): Cell? {
        return conveyorCells.asReversed().firstOrNull { it.isBlocked }
    }

    /**
     *  Finds the cells that are behind a cell that is marked
     *  as blocked.  For each cell that is blocked, capture
     *  a list of cells behind the blockage but before the previous blockage. The returned
     *  list of cells may be empty if the blocking cell
     *  is the first cell of the list or if there are two consecutive blocked cells.
     *
     *  The list must not be empty and the last cell of the list must be blocked.
     *
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 11, 12 are blocked. Then, there will be map of lists as:
     *  ```
     *  1 : {empty}, because there are no cells in front of it
     *  5 : {2, 3, 4}
     *  11 : {6, 7, 8, 9, 10}
     *  12 : {empty}, because there are no cells between cell 11 and 12
     *  ```
     */
    fun cellsBehindBlockedCells(cells: List<Cell>): Map<Cell, List<Cell>> {
        require(cells.isNotEmpty()) { "The supplied cell list was empty" }
        require(cells.last().isBlocked) { "The last cell of the list was not blocked." }
        val map = mutableMapOf<Cell, List<Cell>>()
        if (cells[0].isBlocked) {
            map[cells[0]] = emptyList()
        }
        var startIndex: Int = 0
        for (i in 1..cells.lastIndex) {
            if (cells[i - 1].isBlocked && cells[i].isNotBlocked) {
                // B --> U
                startIndex = i
            } else if (cells[i - 1].isNotBlocked && cells[i].isBlocked) {
                // U --> B
                map[cells[i]] = cells.subList(startIndex, i)
            } else if (cells[i - 1].isBlocked && cells[i].isBlocked) {
                // B --> B
                map[cells[i]] = emptyList()
            }
        }
        return map
    }

    /**
     *  Finds any items that are behind a cell that is marked
     *  as blocked.  For each cell that is blocked, capture
     *  a list of the items on the cells behind the blockage but before the previous blockage. The
     *  returned list may be empty if the blocking cell is the first
     *  cell or if there are two consecutive blocked cells.  For example,
     *  suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, and 11 are blocked. Then, the cells behind the blocking cells are:
     *  ```
     *  1 : {empty}
     *  5 : {2, 3, 4}
     *  11 : {6, 7, 8, 9, 10}
     *  ```
     *  And, any items associated with these cells will be in the associated lists.
     */
    private fun itemsBehindBlockedCells(cells: List<Cell>): Map<Cell, List<Item>> {
        val map = mutableMapOf<Cell, List<Item>>()
        val ubcMap = cellsBehindBlockedCells(cells)
        for ((bc, list) in ubcMap) {
            map[bc] = items(list)
        }
        return map
    }

    /**
     *  Processing the cells in reverse order, find the first cell that is occupied and for which its next cell exists and
     *  is available.  This returns the furthest cell (towards the end of the list) that can
     *  be traversed by an item. This cell holds the lead item.  If such a cell exists, this function returns the item associated
     *  with it.
     */
    private fun firstMovableItem(cells: List<Cell>): Item? {
        return firstMovableCell(cells)?.item
    }

    /**
     *  Processing the cells in reverse order, find the first cell that is occupied and for which its next cell exists and
     *  is available.  This returns the furthest cell (towards the end of the list) that can
     *  be traversed by an item. This cell holds the lead item of the list.
     */
    private fun firstMovableCell(cells: List<Cell>): Cell? {
        return cells.asReversed().firstOrNull { it.isOccupied && (it.nextCell != null) && it.nextCell!!.isAvailable }
    }

    /**
     *  The supplied list of cells represents a sub-set of cells on the conveyor that may contain items
     *  that need to move forward by one cell. If the list is empty, nothing happens.
     *
     *  This method assumes that the last cell in the list
     *  contains an item and that the conveyor cell after the last cell is available
     *  to be occupied such that the item can be moved forward.  All items associated with the cells
     *  in the list are moved forward by one cell. There is no time taken for this movement. This
     *  is simply an assignment such that each item now occupies the cell in front of its current
     *  cell location on the conveyor.  As such, the item occupying the last cell is the lead item
     *  in the "train" of items moving forward by one cell.
     */
    private fun moveItemsForward(cells: List<Cell>) {
        if (cells.isEmpty()) {
            return
        }
        require(cells.last().isOccupied) { "The last cell of the list was not occupied by an item" }
        require(cells.last().nextCell != null) { "The last cell of the list does not have a following cell" }
        require(cells.last().nextCell!!.isAvailable) { "The cell after the last cell is not available" }
        reverseIterateItems(cells, this::moveItemForward)
    }

    private fun moveItemForward(item: Item) {
        item.moveForwardOneCell()
    }

    private fun reverseIterateItems(cells: List<Cell>, function: (Item) -> Unit) {
        var lastItem: Item? = null
        for (cell in cells.asReversed()) {
            if (cell.item != lastItem) {
                lastItem = cell.item
                if (cell.item != null) {
                    function(cell.item!!)
                }
            }
        }
    }

    override fun initialize() {
        velocity = initialVelocity
        for (cell in conveyorCells) {
            cell.item = null
            cell.allocation = null
        }
    }

    /**
     *  Checks if the number of cells needed [numCellsNeeded] can be
     *  allocated at the supplied [entryLocation] associated with the conveyor.
     *  True means that the amount of cells needed at the conveyor entry location can be allocated at this
     *  instant in time.
     */
    fun canAllocateCells(entryLocation: IdentityIfc, numCellsNeeded: Int): Boolean {
        require(entryLocations.contains(entryLocation)) { "The location ($entryLocation) is not a valid entry point on the conveyor" }
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed) for for conveyor (${this.name}"
        }
        val entryCell = entryCells[entryLocation]!!
        return canAllocateAt(entryCell, numCellsNeeded)
    }

    private fun canAllocateAt(entryCell: Cell, numCellsNeeded: Int): Boolean {
        return numAvailableCells(entryCell) >= numCellsNeeded
    }

    private fun numAvailableCells(entryCell: Cell): Int {
        val itr = conveyorCells.listIterator(entryCell.cellNumber - 1)
        var sum = 0
        while (itr.hasNext()) {
            if (itr.next().isAvailable) {
                sum++
            } else {
                return sum
            }
        }
        return sum
    }

    fun numAvailableCells(entryLocation: IdentityIfc): Int {
        require(entryLocations.contains(entryLocation)) { "The location ($entryLocation) is not a valid entry point on the conveyor" }
        return numAvailableCells(entryCells[entryLocation]!!)
    }

    /**
     *  Represents a request for space on a conveyor by an entity.
     *  The supplied request priority is related to the queue priority for the request.
     *
     *  @param entity the entity making the request
     *  @param numCellsNeeded the number of cells being requested
     */
    inner class CellRequest(
        val entity: ProcessModel.Entity,
        val numCellsNeeded: Int = 1,
        val entryLocation: IdentityIfc,
    ) : QObject() {

        val conveyor = this@Conveyor
        val entryCell: Cell

        init {
            require(numCellsNeeded >= 1) { "The number of cells requested must be >= 1" }
            require(numCellsNeeded <= maxEntityCellsAllowed) {
                "The entity requested more cells ($numCellsNeeded) than " +
                        "the allowed maximum ($maxEntityCellsAllowed) for for conveyor (${this.name})"
            }
            require(entryLocations.contains(entryLocation)) { "The location (${entryLocation.name}) of requested cells is not on conveyor (${conveyor.name})" }
            priority = entity.priority
            entryCell = entryCells[entryLocation]!!
        }

        val isFillable: Boolean
            get() = canAllocateAt(entryCell, numCellsNeeded)

        val isNotFillable: Boolean
            get() = !isFillable

    }

    internal fun createRequest(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int = 1,
        entryLocation: IdentityIfc,
    ): CellRequest {
        return CellRequest(entity, numCellsNeeded, entryLocation)
    }

    internal fun enqueueRequest(request: CellRequest) {
        accessQueues[request.entryLocation]!!.enqueue(request)
    }

    internal fun dequeueRequest(request: CellRequest) {
        accessQueues[request.entryLocation]!!.remove(request)
    }

    inner class CellAllocation(
        request: CellRequest
    ) : CellAllocationIfc {
        override val entity: ProcessModel.Entity = request.entity
        override val entryCell: Cell = request.entryCell
        override var numberOfCells = request.numCellsNeeded
            private set
        override val entryLocation: IdentityIfc = request.entryLocation

        override val creationTime: Double = time

        override val requestCreateTime: Double = request.createTime

        override var item: Item? = null
            internal set

        override val conveyor = this@Conveyor

        override var isReadyToConvey = false
            internal set

        /**
         *  True if the allocation is currently allocated some cells
         */
        override val isAllocated: Boolean
            get() = numberOfCells > 0

        /**
         *  True if no units are allocated
         */
        override val isDeallocated: Boolean
            get() = !isAllocated

        internal fun deallocate() { //TODO this is never called!!
            numberOfCells = 0
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.appendLine("Entity ${entity.id} holds $numberOfCells cells of conveyor (${conveyor.name}")
            return sb.toString()
        }
    }

    enum class ItemStatus { //TODO how to use?
        OFF, ENTERING, EXITING, ON
    }

    /**
     *  An item occupies cells on some segment of a conveyor. Items are created
     *  when a ride() occurs.
     */
    inner class Item(
        internal val cellAllocation: CellAllocation,
        desiredLocation: IdentityIfc
    ) : QObject(), ConveyorItemIfc {
        init {
            cellAllocation.item = this
        }

        //TODO review and remove unneeded properties
        override var status: ItemStatus = ItemStatus.OFF
            internal set

        override val entity: ProcessModel.Entity
            get() = cellAllocation.entity
        override val numberOfCells: Int
            get() = cellAllocation.numberOfCells
        override val origin: IdentityIfc
            get() = cellAllocation.entryLocation
        override var destination: IdentityIfc = desiredLocation
            internal set

        override var resumePriority: Int = KSLEvent.DEFAULT_PRIORITY
            internal set //TODO when to allow changes

        override var currentLocation: IdentityIfc? = null
            internal set

        private val myCellsOccupied: ArrayDeque<Cell> = ArrayDeque()

        override val occupiesCells: Boolean
            get() = myCellsOccupied.isNotEmpty()

        override var numCellsAllocated: Int = 0 //TODO where is this reduced
            internal set

        override val numCellsOccupied: Int
            get() = myCellsOccupied.size

        override val isConveyable: Boolean //TODO the item is not created unless it can be conveyed, why needed?
            get() = numCellsAllocated == numberOfCells

        override val conveyor = this@Conveyor

        override val hasReachedAnExitCell: Boolean
            get() {
                if (firstCell == null) {
                    return false
                } else {
                    return firstCell!!.type == CellType.EXIT
                }
            }

        override val hasReachedDestination: Boolean
            get() {
                return if (currentLocation == null) {
                    false
                } else {
                    destination == currentLocation
                }
            }

        val isNextCellOccupied: Boolean
            get() {
                val fc = firstCell
                return if (fc == null) {
                    false
                } else {
                    val nc = fc.nextCell
                    nc?.isOccupied ?: false
                }
            }

        /**
         *  The cell that is occupied by the item that is the furthest forward (closest to the end) on the segment
         *  that the item is currently occupying.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor.  If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then its first cell is 3.
         */
        override val firstCell: Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.last() else null

        /**
         *  The cell that is occupied by the item that is closest to the origin of the segment
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor. If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then its last cell is 2.
         */
        override val lastCell: Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.first() else null

        /**
         *  Causes an item already on the conveyor to move through the cell that it occupies.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         *  We assume that moving forward has been triggered by a time delay that
         *  represents the item moving through the cell. This represents the completion
         *  of that movement.  At the end of this movement, the item is fully covering the cell that it
         *  occupies.
         */
        internal fun moveForwardOneCell() {
            require(isConveyable) { "The item cannot move forward because it has no allocated cells" }
            require(firstCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the first cell cannot be null, safe to use
            require(firstCell!!.nextCell != null) {"The item cannot move forward because it has reached the end of the conveyor"}
            // the next cell exists and is not null, safe to use it
            if ((status == ItemStatus.OFF) && (firstCell!!.isEntryCell)) {
                // this item occupies an entry cell, but it is off the conveyor
                // don't move it forward, but mark it as entering
                status = ItemStatus.ENTERING // it will be included in future moves
                // basically we are skipping the movement of this item because
                // it already occupies the entry cell and doesn't need to move through it
            } else {
                // tell the item to occupy the next cell
                occupyCell(firstCell!!.nextCell!!)//TODO still need to handle getting off case
            }
            if ((myCellsOccupied.size == numberOfCells)) {
                // all cells acquired and last cell is an entry cell for the conveyor, then it completed loading
                if (lastCell!!.isEntryCell) {
                    // check if the item is in the process of entering
                    if (status == ItemStatus.ENTERING) {
                        // item is now fully on the segment, notify conveyor
                        status = ItemStatus.ON
                        // notify the conveyor that the item is fully on
                        conveyor.itemFullyOn(this)
                    }
                } else if (firstCell!!.isExitCell) {
                    // reached an exit cell
                    if (destination == firstCell!!.location) {
                        // reached the intended destination
                        conveyor.itemReachedDestination(this)
                    }
                }
            }
        }

        /**
         *  Causes the item to occupy the supplied cell.  No checking of the contiguous nature of cells
         *  is performed.  The cell is added to the end of the cells occupied and if the number of
         *  cells needs is reached, the oldest cell is removed from the cells occupied.
         */
        internal fun occupyCell(cell: Cell) {
            if (myCellsOccupied.size < numberOfCells) {
                myCellsOccupied.add(cell)
                cell.item = this
            } else {
                popOldest() // remove from front of the list
                myCellsOccupied.add(cell)  // add new cell to the end of the list
                cell.item = this
            }
        }

        /**
         *  Removes the cell that is oldest from the occupied cells. The cell that is
         *  closest to the origin is removed.
         */
        private fun popOldest(): Boolean {
            return if (myCellsOccupied.isNotEmpty()) {
                val first = myCellsOccupied.removeFirst()
                first.item = null
                true
            } else {
                false
            }
        }

    }

    enum class CellType {
        ENTRY, EXIT, INNER
    }

    /**
     *  A cell represents a length of space along the conveyor that
     *  can be allocated to and occupied by conveyable items.  A conveyor is divided
     *  into a set of cells to represent its length.  A cell acts like a general
     *  unit of distance along the conveyor.
     */
    inner class Cell(
        val type: CellType,
        val location: IdentityIfc?,
        val segment: Segment,
        private val cellList: MutableList<Cell>
    ) {
        init {
            cellList.add(this)
        }

        val isEntryCell: Boolean
            get() = type == CellType.ENTRY

        val isExitCell: Boolean
            get() = type == CellType.EXIT

        val isInnerCell: Boolean
            get() = type == CellType.INNER

        val conveyor: Conveyor = this@Conveyor

        val cellNumber: Int = cellList.size
        val index: Int
            get() = cellNumber - 1

        var item: Item? = null
            internal set

        var allocation: CellAllocation? = null
            internal set

        /**
         *  The cell in front of this cell (towards the end of the segment), unless
         *  this cell is the end cell of the final segment and the conveyor is
         *  circular, then the next cell is the first cell of the first segment.
         */
        val nextCell: Cell?
            get() {
                if (cellList.last() == this) {
                    // this cell is the last in the list
                    if (conveyor.isCircular) {
                        // return the first cell in the list
                        return cellList.first()
                    } else {
                        // not circular and reached the end
                        return null
                    }
                } else {
                    // this cell is not the last, the list is 0 index based
                    return cellList[index + 1]
                }
            }

        /**
         *  The cell immediately behind this cell (towards the front of the segment),
         *  unless the cell is first and the conveyor is circular, then the previous
         *  cell is the cell at the end of the last segment.
         */
        val previousCell: Cell?
            get() {
                if (cellList.first() == this) {
                    // this cell is the first in the list
                    if (conveyor.isCircular) {
                        // return the last cell in the list
                        return cellList.last()
                    } else {
                        // not circular, therefore nothing before the first
                        return null
                    }
                } else {
                    // this cell is not the last, the list is 0 index based
                    return cellList[index - 1]
                }
            }

        /**
         *  A cell is occupied if it is covered by an item
         */
        val isOccupied: Boolean
            get() = item != null

        val isNotOccupied: Boolean
            get() = !isOccupied

        val isBlocked: Boolean
            get() = allocation != null

        val isNotBlocked: Boolean
            get() = !isBlocked

        val isAvailable: Boolean
            get() = (!isOccupied && !isBlocked)

        val isUnavailable: Boolean
            get() = (isOccupied || isBlocked)

        override fun toString(): String {
            return "Cell(segment=${segment.name}, cell=$cellNumber, location=${location?.name}, type=$type, " +
                    "isOccupied=$isOccupied, isBlocked=$isBlocked, item=${item?.name})"
        }
    }

    companion object {
        fun builder(parent: ModelElement, name: String? = null): ConveyorTypeStepIfc {
            return Builder(parent, name)
        }
    }

    private class Builder(
        val parent: ModelElement,
        val name: String? = null
    ) : ConveyorTypeStepIfc, VelocityStepIfc,
        CellSizeStepIfc,
        FirstSegmentStepIfc, SegmentStepIfc {
        private var conveyorType = Type.ACCUMULATING
        private var velocity: Double = 1.0
        private var cellSize: Int = 1
        private var maxEntityCellsAllowed: Int = 1
        private lateinit var segmentsData: Segments
        override fun conveyorType(type: Type): VelocityStepIfc {
            conveyorType = type
            return this
        }

        override fun velocity(value: Double): CellSizeStepIfc {
            require(value > 0.0) { "The velocity of the conveyor must be > 0.0" }
            velocity = value
            return this
        }

        override fun cellSize(value: Int): FirstSegmentStepIfc {
            require(value >= 1) { "The cell size must >= 1" }
            cellSize = value
            return this
        }

        override fun maxCellsAllowed(value: Int): FirstSegmentStepIfc {
            require(value >= 1) { "The maximum number of cells allowed to occupy must be >= 1" }
            maxEntityCellsAllowed = value
            return this
        }

        override fun firstSegment(start: IdentityIfc, end: IdentityIfc, length: Int): SegmentStepIfc {
            require(length >= 1) { "The length of the segment must be >= 1 unit" }
            segmentsData = Segments(cellSize, start)
            segmentsData.toLocation(end, length)
            return this
        }

        override fun nextSegment(next: IdentityIfc, length: Int): SegmentStepIfc {
            require(length >= 1) { "The length of the segment must be >= 1 unit" }
            segmentsData.toLocation(next, length)
            return this
        }

        override fun build(): Conveyor {
            return Conveyor(parent, segmentsData, conveyorType, velocity, maxEntityCellsAllowed, name)
        }

    }

    interface ConveyorTypeStepIfc {
        fun conveyorType(type: Type): VelocityStepIfc
    }

    interface VelocityStepIfc {
        fun velocity(value: Double): CellSizeStepIfc
    }

    interface CellSizeStepIfc {
        fun cellSize(value: Int): FirstSegmentStepIfc
    }

    interface FirstSegmentStepIfc {

        fun maxCellsAllowed(value: Int): FirstSegmentStepIfc
        fun firstSegment(start: IdentityIfc, end: IdentityIfc, length: Int): SegmentStepIfc
    }

    interface SegmentStepIfc {
        fun nextSegment(next: IdentityIfc, length: Int): SegmentStepIfc

        fun build(): Conveyor
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Conveyor : $name")
        sb.appendLine("type = $conveyorType")
        sb.appendLine("is circular = $isCircular")
        sb.appendLine("velocity = $initialVelocity")
        sb.appendLine("cellSize = $cellSize")
        sb.appendLine("cell Travel Time = $cellTravelTime")
        sb.appendLine("Segments:")
        sb.append(segmentData)
        sb.appendLine("Cells:")
        for (cell in conveyorCells) {
            sb.appendLine(cell)
        }
        return sb.toString()
    }

    /**
     * It is an error to attempt to allocate cells if there are insufficient
     * cells available. Thus, the number of cells needed must be less than or equal to the number of cells
     * available at the origin point at the time of this call. This function
     * should only be called from the access() suspend function.
     *
     * @param request the access request that wants the cells
     * @return the cell allocation belonging to an entity accessing the conveyor
     */
    internal fun allocateCells(request: CellRequest): CellAllocationIfc {
        require(request.conveyor == this) { "The cell request is not from this conveyor" }
        require(request.isFillable) { "The cell request for (${request.numCellsNeeded}) cells cannot be filled at this time" }
        val ca = CellAllocation(request) // why not do all these when it is created
        ca.isReadyToConvey = true //when is not ready to convey?, after it exits!
        //TODO why does the cell need to remember the allocation?
        request.entryCell.allocation = ca // this causes the cell to be blocked
        blockedEntering(ca)
        return ca
    }

    private fun blockedEntering(allocation: CellAllocation) {
        TODO("Conveyor.blockedEntering() not implemented yet")
//        val blockage = Blockage(allocation, allocation.entryCell)
//        allocation.blockage = blockage
//        blockages.add(blockage)
//        if (blockages.size == 1) {
//            // newly added blockage should signal conveyor stoppage
//            signalConveyorStoppage(blockage)
//        }
    }

    private fun blockedExiting(allocation: CellAllocation, destination: IdentityIfc) {
        TODO("Conveyor.blockedEntering() not implemented yet")
//        val blockage = Blockage(allocation, exitCells[destination]!!)
//        allocation.blockage = blockage
//        blockages.add(blockage)
//        if (blockages.size == 1) {
//            // newly added blockage should signal conveyor stoppage
//            signalConveyorStoppage(blockage)
//        }
    }

//    private fun signalConveyorStoppage(blockage: Blockage) {
//        if (conveyorType == Type.NON_ACCUMULATING) {
//            // all motion on conveyor stops
//
//        } else {
//            // motion continues until none can move
//        }
//        TODO("Conveyor.signalConveyorStoppage() not implemented yet")
//    }

    /**
     *  The entity associated with the item should be suspended after this call.
     *  This function does not do the suspension.  The item
     *  will remain on the conveyor until the entity indicates that the cells are to be released by using
     *  the exit function. The behavior of the conveyor during the ride and when the item reaches its
     *  destination is governed by the type of conveyor. A blockage occurs at the destination location of the conveyor
     *  while the entity occupies the final cells before exiting or riding again.
     *  The blockage associated with the entry cell should be released once the conveyance starts.
     */
    internal fun conveyItem(cellAllocation: CellAllocation, destination: IdentityIfc) {
        // two cases 1) waiting to get on the conveyor or 2) already on the conveyor
        if (cellAllocation.isReadyToConvey) {
            startConveyance(cellAllocation, destination)
        } else {
            continueConveyance(cellAllocation, destination)
        }
    }

    private fun startConveyance(cellAllocation: CellAllocation, destination: IdentityIfc) {
        // the cell allocation is causing a blockage at the entry point of the segment
        // need to create the item, attach the item, start the movement
        val item = Item(cellAllocation, destination)
        // the item should occupy the entry cell in order to move into it
        val entryCell = cellAllocation.entryCell
        item.occupyCell(entryCell)
        item.status = ItemStatus.OFF
        cellAllocation.entryCell.allocation = null // this unblocks the cell
        if (conveyorType == Type.NON_ACCUMULATING) {
            if (hasNoBlockedCells()) {
                startNonAccumulatingConveyorMovement()
            }
        } else {
            startAccumulatingConveyorMovementAfterBlockage()
        }
    }

    private fun continueConveyance(cellAllocation: CellAllocation, nextDestination: IdentityIfc) {
        // must have an item to continue conveyance
        cellAllocation.item?.destination = nextDestination
        exitCells[nextDestination]?.allocation = null // unblocks the destination cell
        if (conveyorType == Type.NON_ACCUMULATING) {
            if (hasNoBlockedCells()) {
                startNonAccumulatingConveyorMovement()
            }
        } else {
            startAccumulatingConveyorMovementAfterBlockage()
        }
    }

    private fun startAccumulatingConveyorMovementAfterBlockage() {
        //need to check if there are items on the conveyor
        //what about items already scheduled to move in front of the blockage
        TODO("Conveyor.startAccumulatingConveyorMovementAfterBlockage(): Not yet implemented")
    }

    private fun startNonAccumulatingConveyorMovement() {
        //need to check if there are items on the conveyor
        if (isOccupied() && hasNoBlockedCells()) {
            // there are items on the conveyor, and the conveyor is not blocked
            // there must be a lead item if there are items on the conveyor, and the conveyor is not blocked
            val leadCell = firstMovableCell(conveyorCells)
                ?: throw IllegalStateException("Attempted to start the non-accumulating conveyor and there was no item to move")
            endCellTraversalEvent = endCellTraversalAction.schedule(cellTravelTime, message = leadCell)
        }
    }

    /**
     * This function should deallocate the cells associated with the cell allocation
     * and cause any blockage associated with the allocation to be removed.
     *
     * There should not be any time delay associated with this function, but it may cause
     * events to be scheduled and processes to be resumed as the allocation is released.
     */
    internal fun deallocateCells(cellAllocation: CellAllocation) {
        // allocation has no item, and was never conveyed
        cellAllocation.entryCell.allocation = null //unblocks the cell
        processWaitingRequests(cellAllocation.entryCell)
        if (conveyorType == Type.NON_ACCUMULATING) {
            if (hasNoBlockedCells()) {
                startNonAccumulatingConveyorMovement()
            }
        } else {
            startAccumulatingConveyorMovementAfterBlockage()
        }
    }

    private fun processWaitingRequests(entryCell: Cell) {
        TODO("Conveyor.processWaitingRequests() not implemented yet")
    }

    /**
     * This function should start the exiting process for the entity holding
     * the cell allocation.  The cells associated with the cell allocation should be deallocated
     * and any blockage associated with the allocation to be removed.
     *
     * There may be time delay associated with this function. It may cause
     * events to be scheduled and processes to be resumed as the allocation is released.
     */
    internal fun startExitingProcess(cellAllocation: CellAllocation) {
        // conveyed to destination and item is getting off
        val destination = cellAllocation.item?.destination!!
        val exitCell = exitCells[destination]!!
        exitCell.allocation = null // unblocks the destination cell
        processWaitingRequests(exitCell)
        if (conveyorType == Type.NON_ACCUMULATING) {
            if (hasNoBlockedCells()) {
                startNonAccumulatingConveyorMovement()
            }
        } else {
            startAccumulatingConveyorMovementAfterBlockage()
        }
    }

    private fun endCellTraversalEventActions(leadCell: Cell) {
        if (conveyorType == Type.NON_ACCUMULATING) {
            // move items forward that can be moved forward before the lead cell
            // need to include the lead cell in the list
            val movingCells = conveyorCells.subList(0, leadCell.cellNumber)
            moveItemsForward(movingCells)
        } else {

        }
        TODO("Conveyor.endCellTraversalEventActions() not implemented yet")
    }

    private fun itemFullyOn(item: Item) {
        // the blockage at the entry point can be removed
        TODO("Conveyor.itemFullyOn() not implemented yet")
    }

    private fun itemReachedDestination(item: Item) {
        // the trip has ended, need to block exit, resume the entity to proceed with exit
        // or allow it to start its next ride
        val exitCell = exitCells[item.destination]!!

        TODO("Conveyor.itemReachedDestination() not implemented yet")
    }

    /**
     *  Causes the event associated with cell traversals to be cancelled.
     *  This does not affect any movement associated with items getting on or off
     *  the conveyor. This only stops movement of items that are fully on the
     *  conveyor
     */
    private fun stopMovementOnConveyor() {
        //TODO not sure if it should stop all movement or just on the conveyor
        if (endCellTraversalEvent != null) {
            endCellTraversalEvent!!.cancelled = true
        }
    }

    private fun resumeMovementOnConveyor() {
        TODO("Conveyor.resumeMovementOnConveyor() not implemented yet")
    }

    private fun scheduleMovement() {
        TODO("Not yet implemented: Conveyor.scheduleMovement()")
    }

    private inner class EndOfCellTraversalAction : EventAction<Cell>() {
        override fun action(event: KSLEvent<Cell>) {
            endCellTraversalEventActions(event.message!!)
        }

    }

}

fun main() {
    val i1 = Identity()
    val i2 = Identity()
    val i3 = Identity()
    val c = Conveyor.builder(Model())
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20)
        .build()

    println(c)

}
