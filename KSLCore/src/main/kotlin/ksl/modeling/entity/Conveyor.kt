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
        return mySegments.isEmpty()
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

    val status: Conveyor.ItemStatus

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
     *  The number of cells currently occupied on the conveyor.
     *  This may be different from numCellsAllocated because an
     *  allocation does not need to convey. It can be used to block
     *  the conveyor at one of its entrance locations
     */
    val numCellsOccupied: Int

    /**
     *  True if the item occupies cells. After exiting the conveyor
     *  the item does not occupy any cells
     */
    val occupiesCells: Boolean

    /**
     *  The conveyor the entity is using
     */
    val conveyor: Conveyor

    /**
     * The object riding on the conveyor has a front (facing towards) its destination
     * and an end (facing where it originated). This cell is the furthermost cell
     * occupied towards the front (in the direction of travel)
     */
    val frontCell: Conveyor.Cell?

    /**
     *  This cell is the furthermost cell occupied by the object towards the where
     *  it originated.
     */
    val rearCell: Conveyor.Cell?

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
            return if (rearCell == null) {
                null
            } else {
                rearCell!!.segment.exitLocation
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

    var defaultMovementPriority = KSLEvent.DEFAULT_PRIORITY + 1

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

    val conveyorCells: List<Cell>
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
        if (cells.size == 2) {
            require(cells.first().isEntryCell && cells.last().isExitCell) { "With 2 cells, there must be an entry cell and an exit cell." }
        }
        conveyorCells = cells.asList()
    }

    //    private val endCellTraversalAction = EndOfCellTraversalAction()
    private val endCellTraversalAction2 = EndOfCellTraversalActionV2()

    /**
     * The event associated with the movement of the items on the conveyor.
     * When this event is scheduled, this means that items are moving (traversing) through
     * the next cell, or exiting through their last cell to exit the conveyor. We
     * always allow the traversal to complete.
     *
     * The event will be set to null under the following conditions:
     * a) non-accumulating conveyor and a blockage exists
     * b) accumulating conveyor and there are no items that can move
     */
    // private var endCellTraversalEvent: KSLEvent<Cell>? = null
    private var endCellTraversalEvent: KSLEvent<List<Cell>>? = null

    internal val hasCellTraversalPending: Boolean
        get() = endCellTraversalEvent != null

//    fun accessQueueAt(location: IdentityIfc): QueueCIfc<CellRequest> {
//        require(accessQueues.contains(location)) { "The origin ($location) is not a valid entry point on the conveyor" }
//        return accessQueues[location]!!
//    }

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

    /**
     * The entry locations that are blocked and the corresponding blocked cell
     */
    fun blockedEntryCells(): Map<IdentityIfc, Cell> {
        return entryCells.filterValues { it.isBlocked }
    }

    /**
     *  The exit locations that are blocked and the corresponding blocked cell
     */
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

    /**
     *  Checks if the conveyor does not have any blocked cells
     */
    fun hasNoBlockedCells(): Boolean = !hasBlockedCells()

    /**
     *  Finds the first blocked cell from the end of the conveyor.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 8 are blocked. Then, cell 8 is the first blocked cell from the end of the
     *  conveyor.
     */
    fun firstBlockedCellFromEnd(): Cell? {
        return conveyorCells.asReversed().firstOrNull { it.isBlocked }
    }

    /**
     * Finds the first blocked cell from the supplied cell going
     * forward in the list. If there are no blocked cells, then
     * null is returned.
     */
    fun firstBlockedCellFromBeginning(startingCell: Cell): Cell? {
        return conveyorCells.subList(startingCell.index, conveyorCells.size).firstOrNull { it.isBlocked }
    }

    /**
     *  Checks if the supplied cell could move forward during the next cell traversal.
     *  Returns true if the cell immediately in front of the cell is not occupied or
     *  if the cell would be part of a train of cells that are pulled forward by
     *  a lead cell behind a blockage.  If there are no blockage, then if the
     *  conveyor has a movable cell then a train can be formed. The supplied
     *  cell must be an occupied cell.
     */
    fun couldCellMoveForward(cell: Cell): Boolean {
        require(cell.isOccupied) { "The supplied cell is not occupied. No reason to check if it could move." }
        if (cell.nextCell != null) {
            // there is a next cell
            if (cell.nextCell!!.isNotOccupied) {
                return true
            }
            // the next cell is occupied, check for movable train
            val fbc = firstBlockedCellFromBeginning(cell)
            if (fbc == null) {
                // no blockages going forward, check entire conveyor
                return firstMovableCell(conveyorCells) != null
            } else {
                // subset before first blocked cell
                val cells = conveyorCells.subList(cell.index, fbc.index)
                return firstMovableCell(cells) != null
            }
        }
        return false
    }

    /**
     *  Finds the cells from the end of the conveyor until the first blockage.
     *  The returned list does not include the first cell that is blocked.
     *  If the conveyor has no blocked cells or the last cell of the conveyor
     *  is the first blocked cell, then this function returns an empty list.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 8 are blocked. Then the returned list contains
     *  cells {9, 10, 11, 12} because cell 8 is the first blocked cell from the end of the
     *  list.  These cells may or may not contain items.
     */
    fun cellsFromEndUntilFirstBlockage(): List<Cell> {
        if (conveyorCells.last().isBlocked || hasNoBlockedCells()) {
            return emptyList()
        }
        // last is not blocked, and there is a blockage
        val firstBlocked = firstBlockedCellFromEnd()!!
        // don't include the blocked cell, but go to the end of the list
        return conveyorCells.subList(firstBlocked.index + 1, conveyorCells.size)
    }

    /**
     *  Partitions the conveyor cells into to sub lists. The first
     *  of the pair is all cells before (and including) the first blocked
     *  cell from the end of the list. The second of the pair is
     *  the all cells after the blockage but not including the blockage.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 8 are blocked. Because cell 8 is the first blocked cell from the end of the
     *  list, the returned pair contains
     *  first = {1, 2, 3, 4, 5, 6, 7, 8}
     *  second = {9, 10, 11, 12}
     *  If the conveyor has no blocked cells, then the pair is:
     *  first = {}
     *  second = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
     *  where the first is an empty list.
     *  If the last cell is the first blocked cell, then the pair is:
     *  first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
     *  second = {}
     */
    fun partitionAtFirstBlockage(): Pair<List<Cell>, List<Cell>> {
        val fbc = firstBlockedCellFromEnd()
        if (fbc == null) {
            // no blocked cell, first is empty, second is all the cells
            return Pair(emptyList(), conveyorCells)
        } else {
            if (conveyorCells.last() == fbc) {
                return Pair(conveyorCells, emptyList())
            } else {
                val first = conveyorCells.subList(0, fbc.index + 1)
                val second = conveyorCells.subList(fbc.index + 1, conveyorCells.size)
                return Pair(first, second)
            }
        }
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
        var startIndex = 0
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
    fun firstMovableCell(cells: List<Cell>): Cell? {
        // find the first occupied cell from the end of the list
        val foundCell =
            cells.asReversed().firstOrNull { it.isOccupied && (it.nextCell != null) && it.nextCell!!.isAvailable }
        if (foundCell != null) {
            return foundCell
        } else {
            // the first occupied cell may be the last cell of the list
            val firstOccupied = cells.asReversed().firstOrNull { it.isOccupied }
            if (firstOccupied == null) {
                return null // no occupied cells, thus no cells to move
            } else {
                // there is an occupied first cell
                if (firstOccupied == conveyorCells.last() && !isCircular) {
                    // the first occupied cell is the cell at the end of the conveyor. Thus, there is no next cell
                    // check if the item needs to move through the cell
                    if (firstOccupied.item!!.status == ItemStatus.EXITING) {
                        // the item must be exiting
                        return firstOccupied
                    } else {
                        throw IllegalStateException("First movable cell was cell at end of conveyor and item was not exiting")
                    }
                }
            }
        }
        return null
    }

    fun hasMovableCell(): Boolean {
        return firstMovableCell(conveyorCells) != null
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
    private fun moveItemsForwardOneCell(cells: List<Cell>) {
        if (cells.isEmpty()) {
            return
        }
        require(cells.last().isOccupied) { "The last cell of the list was not occupied by an item" }
        // the last cell of the list may be the cell at the end of non-circular conveyor
        if (cells.last() == conveyorCells.last() && !isCircular) {
            val item = cells.last().item!!
            if (item.status != ItemStatus.EXITING) {
                throw IllegalStateException("The last cell in the move forward list was the last cell of the conveyor but the item was not exiting.")
            }
            // special case to allow movement to the exiting item at end of the conveyor
            reverseIterateItems(cells, this::moveItemForward)
        } else {
            require(cells.last().nextCell != null) { "The last cell of the list does not have a following cell" }
            require(cells.last().nextCell!!.isAvailable) { "The cell after the last cell is not available" }
            reverseIterateItems(cells, this::moveItemForward)
        }
    }

//    private fun processWaitingRequestsWhenEntryCellBecomesUnoccupied(entryCell: Cell) {
//        require(entryCell.isEntryCell) { "The supplied cell was not an entry cell" }
//        require(entryCell.isNotOccupied) { "Cannot process waiting requests when the entry cell is occupied" }
//        val location = entryCell.location!!
//        // there is a possibility that a waiting request can get on
//        val queue = accessQueues[location]!!
//        if (queue.isNotEmpty) {
//            ProcessModel.logger.info { "$time > processing waiting requests at location ${location.name}" }
//            val request = queue.peekFirst()!!
//            if (request.isFillable) {
//                ProcessModel.logger.info { "$time > resuming entity (${request.entity.name}) with fillable request at location ${location.name}" }
//                request.entity.resumeProcess(0.0, priority = request.accessResumePriority)
//            }
//        } else {
//            ProcessModel.logger.info { "$time > access queue at location ${location.name} was empty" }
//        }
//    }

    /**
     *  This function is called after items have completed a move forward on the conveyor.
     *  This may have freed up space at an entry location.  This function checks
     *  all entry locations and if there are sufficient cells available to fill the first
     *  request that is waiting, then the entity is resumed. This causes the
     *  waiting request to be removed from the queue and the entity to be
     *  allocated cells at that location for potential use on the conveyor.
     */
//    private fun processWaitingRequests() {
//        //TODO this checking may depend on type of conveyor
//        for (entryLocation in entryLocations.reversed()) {
//            val entryCell = entryCells[entryLocation]!!
//            if (entryCell.isNotOccupied) {
//                processWaitingRequestsWhenEntryCellBecomesUnoccupied(entryCell)
//            }
//        }
////
////        for (entryLocation in entryLocations) {
////            ProcessModel.logger.info { "$time > processing waiting requests at location ${entryLocation.name}" }
////            if (entryCells[entryLocation]!!.isAvailable) { //TODO ******* This is a problem ******
////                ProcessModel.logger.info { "$time > entry cell at location ${entryLocation.name} was available" }
////                // there is a possibility that a waiting request can get on
////                val queue = accessQueues[entryLocation]!!
////                if (queue.isNotEmpty) {
////                    ProcessModel.logger.info { "$time > processing waiting requests at location ${entryLocation.name}" }
////                    val request = queue.peekFirst()!!
////                    if (request.isFillable) {
////                        ProcessModel.logger.info { "$time > resuming entity (${request.entity.name}) with request at location ${entryLocation.name}" }
////                        request.entity.resumeProcess(0.0, priority = request.accessResumePriority)
////                    }
////                }
////            } else {
////                ProcessModel.logger.info { "$time > entry cell at location ${entryLocation.name} was not available" }
////            }
////        }
//    }

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
//        status = Status.IDLE
        velocity = initialVelocity
        for (cell in conveyorCells) {
            cell.item = null
            cell.isBlocked = false
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
        val itr = conveyorCells.listIterator(entryCell.index)
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

    /**
     *  Returns true if the entry location on the conveyor is blocked. Blocked
     *  means that an entity has control of the cell. A blockage causes non-accumulating
     *  conveyors to stop. For accumulating conveyors, items can continue moving until
     *  they reach the blockage and cannot move forward through the blockage. The
     *  entry cell remains blocked until released. The entity does not have to be
     *  on the conveyor to block the entry. The entity can release the blockage
     *  by exiting the conveyor (before getting on) or after riding on the conveyor. A
     *  blockage at an entry location is automatically released the instant that an item
     *  riding on a conveyor is completely on the conveyor. That is, when the item's
     *  rear most cell first occupies the entry cell.
     */
    fun isEntryLocationBlocked(entryLocation: IdentityIfc): Boolean {
        require(entryLocations.contains(entryLocation)) { "The location ($entryLocation) is not a valid entry point on the conveyor" }
        val entryCell = entryCells[entryLocation]!!
        return entryCell.isBlocked
    }

    /**
     *  Returns true if the exit location on the conveyor is blocked. Blocked
     *  means that an entity has control of the cell. A blockage causes non-accumulating
     *  conveyors to stop. For accumulating conveyors, items can continue moving until
     *  they reach the blockage and cannot move forward through the blockage. An exit location
     *  is blocked when the item occupying the exit cell has reached its destination while
     *  still on the conveyor. The exit cell becomes unblocked the instant that the item
     *  is fully off of the conveyor
     */
    fun isExitLocationBlocked(exitLocation: IdentityIfc): Boolean {
        require(exitLocations.contains(exitLocation)) { "The location ($exitLocation) is not a valid exit point on the conveyor" }
        val exitCell = exitCells[exitLocation]!!
        return exitCell.isBlocked
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
        var accessResumePriority: Int = KSLEvent.DEFAULT_PRIORITY
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

    /**
     *  Depending on the type of conveyor, this function returns true
     *  if the entry cell at the supplied location is available to be controlled allocated and
     *  false if entry is not possible and a request at this time would need to wait.
     */
    fun isEntryPossible(entryLocation: IdentityIfc): Boolean {
        require(entryLocations.contains(entryLocation)) { "The supplied location was not valid entry location." }
        val entryCell = entryCells[entryLocation]!!
        return if (conveyorType == Type.NON_ACCUMULATING) {
            isEntryCellAvailableNonAccumulatingConveyor(entryCell)
        } else {
            isEntryCellAvailableAccumulatingConveyor(entryCell)
        }
    }

    private fun isEntryCellAvailableNonAccumulatingConveyor(entryCell: Cell): Boolean {
        return if (hasBlockedCells()) {
            !entryCell.isBlocked
        } else {
            if (isOccupied()) {
                // no blocked cells and conveyor is occupied. It should be moving
                val pc = entryCell.previousCell
                if (pc != null) {
                    !pc.isOccupied
                } else {
                    true
                }
            } else {
                true
            }
        }
    }

    private fun isEntryCellAvailableAccumulatingConveyor(entryCell: Cell): Boolean {
        return if (entryCell.isBlocked) {
            false
        } else {
            // entry cell is not blocked
            if (!entryCell.isOccupied) {
                // entry cell is not occupied
                val pc = entryCell.previousCell
                // if the previous cell exists and is occupied, the entry cell will not be available
                !(pc != null && pc.isOccupied)
            } else {
                // entry cell is occupied, the item may move out of it, into the next cell
                couldCellMoveForward(entryCell)
            }
        }
    }

//    internal fun requestCells(
//        entity: ProcessModel.Entity,
//        numCellsNeeded: Int = 1,
//        entryLocation: IdentityIfc,
//        accessResumePriority: Int
//    ): CellRequest {
//        val request = CellRequest(entity, numCellsNeeded, entryLocation, accessResumePriority)
//        accessQueues[request.entryLocation]!!.enqueue(request)
//        return request
//    }
//
//    internal fun dequeueRequest(request: CellRequest) {
//        accessQueues[request.entryLocation]!!.remove(request)
//    }

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

        override var isReadyToConvey = true
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

        internal fun deallocate() {
            numberOfCells = 0
        }

        override fun toString(): String {
            val sb = StringBuilder()
            sb.appendLine("Entity ${entity.id} holds $numberOfCells cells of conveyor (${conveyor.name}")
            return sb.toString()
        }
    }

    enum class ItemStatus {
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

        override var resumePriority: Int = KSLEvent.DEFAULT_PRIORITY - 2

        override var currentLocation: IdentityIfc? = null
            internal set

        private val myCellsOccupied: ArrayDeque<Cell> = ArrayDeque()

        override val occupiesCells: Boolean
            get() = myCellsOccupied.isNotEmpty()

        override val numCellsOccupied: Int
            get() = myCellsOccupied.size

        override val conveyor = this@Conveyor

        override val hasReachedAnExitCell: Boolean
            get() {
                if (frontCell == null) {
                    return false
                } else {
                    return frontCell!!.type == CellType.EXIT
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
                val fc = frontCell
                return if (fc == null) {
                    false
                } else {
                    val nc = fc.nextCell
                    nc?.isOccupied ?: false
                }
            }

        /**
         *  The cell that is occupied by the item that is the furthest forward (closest to the end) on the conveyor
         *  that the item is currently occupying.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor.  If the conveyor has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then its front cell is 3.
         */
        override val frontCell: Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.last() else null

        /**
         *  The cell that is occupied by the item that is closest to the origin of the conveyor
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor. If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then its rear cell is 2.
         */
        override val rearCell: Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.first() else null

        //TODO move forward logic

        /**
         *  Causes an item already on the conveyor to move through the cell that it occupies.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         *  We assume that moving forward has been triggered by a time delay that
         *  represents the item moving through the cell. This represents the completion
         *  of that movement.  At the end of this movement, the item is fully covering the cell that it
         *  occupies.
         */
        internal fun moveForwardOneCell() {
            require(frontCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the first cell cannot be null, safe to use
            if ((status == ItemStatus.EXITING) && (frontCell!!.isExitCell)) {
                // need to move the item forward, through its front cell, which is the exit cell
                // item no longer occupies its rear cell
                ProcessModel.logger.info { "$time > Item($name) representing Entity(${entity.name}) is exiting the conveyor from cell ${frontCell?.cellNumber} at location ${frontCell?.location?.name}" }
                popRearCell()
                if (!occupiesCells) {
                    // no longer on the conveyor
                    status = ItemStatus.OFF
                    ProcessModel.logger.info { "$time > Item($name) representing Entity(${entity.name}) has moved off the conveyor" }
                    conveyor.itemFullyOffConveyor(this, frontCell!!)
                }
            } else if ((status == ItemStatus.OFF) && (frontCell!!.isEntryCell)) {
                // this item occupies an entry cell, but it is off the conveyor
                // don't move it forward, but mark it as entering
                ProcessModel.logger.info { "$time > Item(${name}) representing Entity(${entity.name}) reached the end of cell ${frontCell?.cellNumber} at location ${frontCell?.location?.name}" }
                status = ItemStatus.ENTERING // it will be included in future moves
                // could be fully on here
                //TODO this is not working, it is not being called for 1st or 2nd entity
                if ((myCellsOccupied.size == numberOfCells)) {
                    // all cells acquired and last cell is an entry cell for the conveyor, then it completed loading
                    if (rearCell!!.isEntryCell) {
                        // check if the item is in the process of entering
                        if (status == ItemStatus.ENTERING) {
                            status = ItemStatus.ON
                            ProcessModel.logger.info { "$time > Item(${name}) representing Entity(${entity.name}) is fully on of the conveyor" }
                            // notify the conveyor that the item is fully on
                            conveyor.itemFullyOnConveyor(rearCell!!)
                        }
                    }
                }
                // basically we are skipping the movement of this item because
                // it already occupies the entry cell and doesn't need to move through it
            } else {
                // Status is ENTERING or ON
                require(frontCell!!.nextCell != null) { "The item cannot move forward because it has reached the end of the conveyor" }
                // tell the item to occupy the next cell
                ProcessModel.logger.info { "$time > Item(${name}) representing Entity(${entity.name}) moved from cell ${frontCell?.cellNumber} to cell ${frontCell?.nextCell?.cellNumber}" }
                occupyCell(frontCell!!.nextCell!!)
                if ((myCellsOccupied.size == numberOfCells)) {
                    // all cells acquired and last cell is an entry cell for the conveyor, then it completed loading
                    if (rearCell!!.isEntryCell) {
                        //TODO this is not working, it is not being called for 1st or 2nd entity

                        // check if the item is in the process of entering
                        if (status == ItemStatus.ENTERING) {
                            // this logic should only execute if the item was on but did not have all its cells yet
                            // item is now fully on the conveyor, notify conveyor
                            status = ItemStatus.ON
                            ProcessModel.logger.info { "$time > Item(${name}) representing Entity(${entity.name}) is fully on of the conveyor" }
                            // notify the conveyor that the item is fully on
                            conveyor.itemFullyOnConveyor(rearCell!!)
                        }
                    } else if (frontCell!!.isExitCell) {
                        // reached an exit cell
                        if (destination == frontCell!!.location) {
                            // reached the intended destination
                            ProcessModel.logger.info { "$time > Item(${name}) representing Entity(${entity.name}) has reached cell ${frontCell?.cellNumber} and its destination: ${destination.name}" }
                            conveyor.itemReachedDestination(this)
                        }
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
                popRearCell() // remove from front of the list
                myCellsOccupied.add(cell)  // add new cell to the end of the list
                cell.item = this
            }
        }

        /**
         *  Removes the cell that is oldest from the occupied cells. The cell that is
         *  closest to the origin is removed.
         */
        private fun popRearCell(): Boolean {
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

        var isBlocked: Boolean = false
            internal set

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
        //TODO this error is occurring because there are no cells available because item is still in entry cell when the entity was resumed
        // this does not anticipate the movement out of the entry cell
//        require(request.isFillable) { "The cell request for (${request.numCellsNeeded}) cells cannot be filled at this time" }
        val ca = CellAllocation(request)
        ProcessModel.logger.info { "$time > ${request.entity.name} causing blockage for cell ${ca.entryCell.cellNumber} at location (${request.entryLocation.name})" }
        causeBlockage(ca.entryCell)
        return ca
    }

    private fun causeBlockage(blockingCell: Cell) {
        //TODO this function needs a re-do/look
        blockingCell.isBlocked = true
        ProcessModel.logger.info { "$time > ...... caused blockage at cell ${blockingCell.cellNumber}" }
        if (conveyorType == Type.NON_ACCUMULATING) {
            // all motion on conveyor stops
            if (endCellTraversalEvent != null && endCellTraversalEvent!!.scheduled) {
                ProcessModel.logger.info { "$time > blockage cancelled cell traversal event" }
                endCellTraversalEvent!!.cancelled = true
                endCellTraversalEvent = null
//                status = Status.BLOCKED
            }
        } else {
            // motion continues until none can move
            if (!isOccupied()) {
                ProcessModel.logger.info { "$time > accumulating conveyor: is not occupied: after causing blockage" }
            }
            //TODO("Conveyor.causeBlockage() accumulating conveyor case not implemented yet")
        }
    }

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
        require(exitLocations.contains(destination)) { "The destination (${destination.name} is not associated with conveyor (${this.name})" }
        // two cases 1) waiting to get on the conveyor or 2) already on the conveyor
        // if the allocation does not have an item, then it could never have ridden the conveyor before
        if (cellAllocation.item == null) {
            // this will create and attach an item to the allocation
            ProcessModel.logger.info { "$time > Entity (${cellAllocation.entity.name}) starting conveyance to destination ${destination.name}" }
            startConveyance(cellAllocation, destination)
        } else {
            ProcessModel.logger.info { "$time > Entity (${cellAllocation.entity.name}) continuing conveyance to destination ${destination.name}" }
            continueConveyance(cellAllocation, destination)
        }
    }

    /**
     *  This function is called when the entity asks to ride() on the conveyor. The cell allocation
     *  is used as a ticket to ride. An item is created that represents the entity occupying cells
     *  on the conveyor. The entry location associated with the cell allocation becomes unblocked.
     *  The item occupies the entry cell and the conveyor is told to begin movement.
     */
    private fun startConveyance(cellAllocation: CellAllocation, destination: IdentityIfc) {
        // the cell allocation is causing a blockage at the entry point of the segment
        // need to create the item, attach the item, start the movement
        val item = Item(cellAllocation, destination)
        // the item should occupy the entry cell in order to move into it
        val entryCell = cellAllocation.entryCell
        item.occupyCell(entryCell)
        ProcessModel.logger.info { "$time > Item (${item.name}) for entity (${item.entity.name}) has occupied cell (${entryCell.cellNumber}) during entry" }
        item.status = ItemStatus.OFF // redundant, item is created as OFF
        removeBlockage(entryCell)
    }

    /**
     *  This function is called when the conveyor is asked to convey an item by an entity,
     *  but the entity is not getting on (it is already on the conveyor).
     *
     *  There is a blockage at the exit location of the destination. The blockage should
     *  be removed and if possible the items on the conveyor can start moving.
     */
    private fun continueConveyance(cellAllocation: CellAllocation, nextDestination: IdentityIfc) {
        // must have an item to continue conveyance
        cellAllocation.item?.destination = nextDestination
        val exitCell = exitCells[nextDestination]!!
        removeBlockage(exitCell)
    }

    /**
     *  This function removes the blockage at the supplied cell and
     *  causes the conveyor to start moving.
     */
    private fun removeBlockage(blockedCell: Cell) {
        //TODO when should this be called
        blockedCell.isBlocked = false // this unblocks the cell
        ProcessModel.logger.info { "$time > removeBlockage() at $blockedCell" }
        if (conveyorType == Type.NON_ACCUMULATING) {
            scheduleNonAccumulatingConveyorMovement()
//            if (hasNoBlockedCells()) {
//                //startNonAccumulatingConveyorMovement()
//                scheduleNonAccumulatingConveyorMovement()
//            }
        } else {
            scheduleAccumulatingConveyorMovement()
            //startAccumulatingConveyorMovementAfterBlockage(blockedCell)
        }
    }

    private fun scheduleConveyorMovement() {
        if (conveyorType == Type.NON_ACCUMULATING) {
            scheduleNonAccumulatingConveyorMovement()
        } else {
            scheduleAccumulatingConveyorMovement()
        }
    }

//    private fun startAccumulatingConveyorMovementAfterBlockage(blockingCell: Cell) {
//        //need to check if there are items on the conveyor
//        //what about items already scheduled to move in front of the blockage
//        if (isOccupied()) {
//            if (!hasCellTraversalPending) {
//                ProcessModel.logger.info { "$time > accumulating conveyor: after blockage: is occupied with no pending cell traversal: check for movable cells" }
//                // if the conveyor is fully blocked then we can try to start it up
//                // there are items on the conveyor and the conveyor was fully stopped due to blockages
//                // the blockage was removed, items may be able to move forward
//                val movableCell = firstMovableCell(conveyorCells)
//                if (movableCell != null) {
//                    // there is at least one cell that can now move
//                    // schedule the movement of a cell traversal
////                    status = Status.ACCUMULATING
//                    ProcessModel.logger.info { "$time > scheduling start of accumulating conveyor with movable cell ${movableCell.cellNumber}" }
//                    endCellTraversalEvent = endCellTraversalAction.schedule(
//                        cellTravelTime,
//                        message = movableCell,
//                        priority = defaultMovementPriority
//                    )
//                } else {
//                    ProcessModel.logger.info { "$time > accumulating conveyor: after blockage: is occupied: no movable cells to schedule" }
//                    endCellTraversalEvent = null
//                }
//            } else {
//                ProcessModel.logger.info { "$time > accumulating conveyor: after blockage: is occupied: has pending cell traversal: don't schedule new movement" }
//            }
//        } else {
//            ProcessModel.logger.info { "$time > accumulating conveyor: after blockage: not occupied: don't schedule new movement" }
//            endCellTraversalEvent = null
//        }
//        //TODO("Conveyor.startAccumulatingConveyorMovementAfterBlockage(): Not yet implemented")
//    }

//    private fun startNonAccumulatingConveyorMovement() {
//        ProcessModel.logger.info { "$time startNonAccumulatingConveyorMovement()" }
//        //need to check if there are items on the conveyor
//        if (isOccupied() && hasNoBlockedCells()) {
//            // there are items on the conveyor, and the conveyor is not blocked
//            // there must be a lead item if there are items on the conveyor, and the conveyor is not blocked
//            val leadCell = firstMovableCell(conveyorCells)
//                ?: throw IllegalStateException("Attempted to start the non-accumulating conveyor and there was no item to move")
//            ProcessModel.logger.info { "scheduling movement for cell $leadCell for traversal time $cellTravelTime" }
//            endCellTraversalEvent =
//                endCellTraversalAction.schedule(cellTravelTime, message = leadCell, priority = defaultMovementPriority)
//        }
//    }

    /**
     * This function is called when the entity wants to exit without having
     * been on the conveyor. It has an allocation, but has not asked to ride.
     *
     * This function should deallocate the cells associated with the cell allocation
     * and cause any blockage associated with the allocation to be removed.
     *
     * There should not be any time delay associated with this function, but it may cause
     * events to be scheduled and processes to be resumed as the allocation is released.
     */
    internal fun deallocateCells(cellAllocation: CellAllocation) {
        // allocation has no item, and was never conveyed
        cellAllocation.deallocate()
        val blockedCell = cellAllocation.entryCell
        removeBlockage(blockedCell)
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
        val item = cellAllocation.item!!
        item.status = ItemStatus.EXITING
        val destination = cellAllocation.item?.destination!!
        val exitCell = exitCells[destination]!!
        removeBlockage(exitCell)
    }

    /**
     * This function represents the end of cell traversal for all items that
     * were told to move forward one cell on the conveyor.
     */
//    private fun endCellTraversalActionForNonAccumulatingConveyor(leadCell: Cell) {
//        ProcessModel.logger.info { "$time > endCellTraversalActionForNonAccumulatingConveyor()" }
//        // move items forward that can be moved forward before the lead cell
//        // need to include the lead cell in the list
//        ProcessModel.logger.info { "finding the moving cells" }
//        val movingCells = conveyorCells.subList(0, leadCell.cellNumber)
//        ProcessModel.logger.info { "moving the cells forward" }
//        moveItemsForwardOneCell(movingCells)
//        // after moving the items forward on the cells, there may be space on the conveyor for waiting requests
//        processWaitingRequests()
//        ProcessModel.logger.info { "Completed moving the cells forward" }
//        if (isOccupied() && hasNoBlockedCells()) {
//            // there are items on the conveyor, and the conveyor is not blocked
//            // there must be a lead item if there are items on the conveyor, and the conveyor is not blocked
//            val nextLeadCell = firstMovableCell(conveyorCells)
//                ?: throw IllegalStateException("Attempted to start the non-accumulating conveyor and there was no item to move")
//            ProcessModel.logger.info { "scheduling movement for cell ${nextLeadCell.cellNumber} for traversal time $cellTravelTime" }
//            endCellTraversalEvent = endCellTraversalAction.schedule(
//                cellTravelTime,
//                message = nextLeadCell,
//                priority = defaultMovementPriority
//            )
//        }
//    }

    private fun nonAccumulatingConveyorFindCellsToMove(): List<Cell> {
        require(conveyorType == Type.NON_ACCUMULATING) { "The conveyor is not type non-accumulating" }
        if (!isOccupied() || hasBlockedCells()) {
            // nothing can move on a non-accumulating conveyor if it is blocked
            return emptyList()
        }
        // conveyor is occupied and has no blocked cells
        val mc = mutableListOf<Cell>()
        val leadCell = firstMovableCell(conveyorCells)
        if (leadCell != null) {
            val movingCells = conveyorCells.subList(0, leadCell.cellNumber)
            mc.addAll(movingCells)
        }
        return mc
    }

    private fun findMovableCells(cells: List<Cell>): List<Cell> {
        val fmc = firstMovableCell(cells)
        if (fmc == null) {
            return emptyList()
        } else {
            return conveyorCells.subList(cells.first().index, fmc.index + 1)
        }
    }

    //TODO blockedAccumulatingConveyorMovement()
    /**
     *  This function assumes that there is at least one blocked cell on a conveyor
     *  of type accumulating.  If any items on the conveyor moves forward, then
     *  the function returns true. If no items were able to move forward, then
     *  the function returns false.
     */
    private fun blockedAccumulatingConveyorMovement(): Boolean {
        require(conveyorType == Type.ACCUMULATING) { "The conveyor is not type accumulating" }
        require(hasBlockedCells()) { "There were no blocked cells on the accumulating conveyor" }
        var movedCells = false
        // conveyor has blocked cells, so there must be some cells before the first blockage
        val (cellsBeforeFirstBlockage, cellsAfterFirstBlockage) = partitionAtFirstBlockage()
        // if the cells after the first blockage is empty, then the first blockage is the last cell
        if (cellsAfterFirstBlockage.isNotEmpty()) {
            // these cells may be able to move forward
            val movableCells = findMovableCells(cellsAfterFirstBlockage)
            if (movableCells.isNotEmpty()) {
                moveItemsForwardOneCell(movableCells)
                movedCells = true
            }
        }
        // get the cells behind every blockage
        val behindBlockagesCells = cellsBehindBlockedCells(cellsBeforeFirstBlockage)
        for ((bc, cells) in behindBlockagesCells) {
            if (cells.isNotEmpty()) {
                // check if the cells can move forward
                val movableCells = findMovableCells(cells)
                if (movableCells.isNotEmpty()) {
                    moveItemsForwardOneCell(movableCells)
                    movedCells = true
                }
            }
        }
        return movedCells
    }

    private fun accumulatingConveyorFindCellsToMove(): List<Cell> {
        require(conveyorType == Type.ACCUMULATING) { "The conveyor is not type accumulating" }
        if (!isOccupied()) {
            return emptyList()
        }
        val mc = mutableListOf<Cell>()
        if (hasNoBlockedCells()) {
            // there must be a lead cell to move if the conveyor has items and there are no blocked cells
            val leadCell = firstMovableCell(conveyorCells)!!
            // get the cells to move
            // from the beginning up to and including the lead cell
            val movingCells = conveyorCells.subList(0, leadCell.cellNumber)
            mc.addAll(movingCells)
        } else {
            // there are blockages, try to move items that can be moved
            // conveyor has blocked cells, so there must be some cells before the first blockage
            val (cellsBeforeFirstBlockage, cellsAfterFirstBlockage) = partitionAtFirstBlockage()
            // if the cells after the first blockage is empty, then the first blockage is the last cell
            if (cellsAfterFirstBlockage.isNotEmpty()) {
                // these cells may be able to move forward
                val movableCells = findMovableCells(cellsAfterFirstBlockage)
                mc.addAll(movableCells)
            }
            // get the cells behind every blockage
            val behindBlockagesCells = cellsBehindBlockedCells(cellsBeforeFirstBlockage)
            for ((bc, cells) in behindBlockagesCells) {
                if (cells.isNotEmpty()) {
                    // check if the cells can move forward
                    val movableCells = findMovableCells(cells)
                    mc.addAll(movableCells)
                }
            }
        }
        return mc
    }

    /**
     *  An accumulating conveyor will move items forward by one cell that can be moved
     *  forward on the conveyor.  This includes items that are behind blockages and
     *  any items that are not blocked. No time advancement occurs with this movement.
     *  This method is intended to be invoked at the end of a cell traversal event
     *  for accumulating conveyors.
     */
    private fun accumulatingConveyorMoveItemsForwardOneCell() {
        // no movement is necessary if the conveyor is not occupied
        if (isOccupied()) {
            ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied" }
            if (hasNoBlockedCells()) {
                // there must be a lead cell to move if the conveyor has items and there are no blocked cells
                val leadCell = firstMovableCell(conveyorCells)!!
                // get the cells to move
                ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied: no blockages: looking for cells to move" }
                // from the beginning up to and including the lead cell
                val movingCells = conveyorCells.subList(0, leadCell.cellNumber)
                ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied: no blockages: moving items forward from cell (${leadCell.cellNumber})" }
                moveItemsForwardOneCell(movingCells)
                //processWaitingRequests()
            } else {
                // there are blockages, try to move items that can be moved
                ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied: with blockages: attempting to move items forward" }
                val moved = blockedAccumulatingConveyorMovement()
                if (moved) {
                    ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied: with blockages: there were items moved" }
                    //processWaitingRequests()
                } else {
                    ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: is occupied: with blockages: there no items moved" }
                }
            }
        } else {
            ProcessModel.logger.info { "$time > accumulating conveyor: moving items forward: not occupied" }
        }
    }

//    private fun endCellTraversalActionForAccumulatingConveyor() {
//        ProcessModel.logger.info { "$time > accumulating conveyor: causing movable items to move forward by one cell" }
//        accumulatingConveyorMoveItemsForwardOneCell()
//        processWaitingRequests()
//        //TODO I think that this is the only place needed for checking to processWaitingRequests()
//        ProcessModel.logger.info { "$time > accumulating conveyor: completed moving items forward by one cell" }
//        if (isOccupied()) {
//            // if the conveyor is occupied, then we can try to continue the movement
//            ProcessModel.logger.info { "$time > accumulating conveyor: is occupied: checking for movable cells" }
//            val movableCell = firstMovableCell(conveyorCells)
//            if (movableCell != null) {
//                // there is at least one cell that can now move
//                // schedule the movement of a cell traversal
////                status = Status.ACCUMULATING
//                ProcessModel.logger.info { "$time > accumulating conveyor: is occupied: found movable cells: scheduling more movement" }
//                endCellTraversalEvent = endCellTraversalAction.schedule(
//                    cellTravelTime,
//                    message = movableCell,
//                    priority = defaultMovementPriority
//                )
//            } else {
//                ProcessModel.logger.info { "$time > accumulating conveyor: is occupied: no movable cells found: not scheduling more movement" }
//                endCellTraversalEvent = null
//            }
//        } else {
//            ProcessModel.logger.info { "$time > accumulating conveyor: not occupied: not scheduling more movement" }
//            endCellTraversalEvent = null
//        }
//        //TODO("Conveyor.endCellTraversalEventActions() for accumulating conveyor case not implemented yet")
//    }

    private fun itemFullyOffConveyor(item: Item, exitCell: Cell) {
        require(exitCell.isExitCell) { "The supplied cell was not an exit cell" }
        //TODO what happens when an item exits, should the exit cell become unblocked?
        exitCell.isBlocked = false //TODO what about remove blockage function
        // item completed the exiting process, tell the entity that it can proceed
        conveyorHoldQ.removeAndResume(item.entity)
    }

    private fun itemReachedDestination(item: Item) {
        // the trip has ended, need to block exit, resume the entity to proceed with exit
        // or allow it to start its next ride
        val exitCell = exitCells[item.destination]!!
        causeBlockage(exitCell)
        ProcessModel.logger.info { "$time > resuming ${item.entity} after reaching destination" }
        conveyorHoldQ.removeAndResume(item.entity, item.resumePriority, false)
    }

//    private inner class EndOfCellTraversalAction : EventAction<Cell>() {
//        override fun action(event: KSLEvent<Cell>) {
//            ProcessModel.logger.info { "$time > ***** started end of cell traversal action *****" }
//            if (conveyorType == Type.NON_ACCUMULATING) {
//                endCellTraversalActionForNonAccumulatingConveyor(event.message!!)
//            } else {
//                endCellTraversalActionForAccumulatingConveyor()
//            }
//            ProcessModel.logger.info { "$time > ***** completed end of cell traversal action *****" }
//        }
//
//    }

    private fun scheduleAccumulatingConveyorMovement() {
        if (!isOccupied()) {
            //TODO make even NULL
            endCellTraversalEvent = null
            return
        }
        val movableCells = accumulatingConveyorFindCellsToMove()
        if (movableCells.isNotEmpty()) {
            //TODO check for entry cells that will be uncovered after the move
            processEntryCellsBeforeMoving(movableCells)
            //TODO capture the event
            endCellTraversalEvent = endCellTraversalAction2.schedule(
                cellTravelTime,
                message = movableCells,
                priority = defaultMovementPriority
            )
        }
    }

    private fun processEntryCellsBeforeMoving(movingCells: List<Cell>) {
        for (cell in movingCells) {
            if (cell.isEntryCell) {
                // check if an item will not move into it
                val pc = cell.previousCell
                if (pc != null) {
                    // has previous cell
                    if (movingCells.contains(pc)) {
                        // previous cell is a cell that will move
                        if (pc.isNotOccupied) {
                            // nothing will move into entry cell from behind it on conveyor, during this move
                            // entry cell will not be occupied during the move
                            handleWaitingRequestsForAccumulatingConveyor(cell)
                        }
                    }
                } else {
                    // no previous cell, nothing can come from behind it
                    handleWaitingRequestsForAccumulatingConveyor(cell)
                }
            }
        }
    }

    private fun handleWaitingRequestsForAccumulatingConveyor(entryCell: Cell) {
        val location = entryCell.location!!
        // the waiting request can be allocated because entry cell will not be occupied
        val queue = accessQueues[location]!!
        if (queue.isNotEmpty) {
            ProcessModel.logger.info { "$time > processing waiting requests at location ${location.name}" }
            val request = queue.peekFirst()!!
            ProcessModel.logger.info { "$time > resuming entity (${request.entity.name}) with request at location ${location.name}" }
            request.entity.resumeProcess(0.0, priority = request.accessResumePriority)
        } else {
            ProcessModel.logger.info { "$time > access queue at location ${location.name} was empty" }
        }
    }

    private fun scheduleNonAccumulatingConveyorMovement() {
        if (!isOccupied() || hasBlockedCells()) {
            //TODO make event NULL
            endCellTraversalEvent = null
            return
        }
        val movableCells = nonAccumulatingConveyorFindCellsToMove()
        if (movableCells.isNotEmpty()) {
            //TODO check for uncovered entry cells here??
            processEntryCellsBeforeMoving(movableCells)
            //TODO capture the event
            endCellTraversalEvent = endCellTraversalAction2.schedule(
                cellTravelTime,
                message = movableCells,
                priority = defaultMovementPriority
            )
        }
    }

    private fun itemFullyOnConveyor(entryCell: Cell) {
        require(entryCell.isEntryCell) { "The supplied cell was not an entry cell" }
        // an entering item has fully occupied its needed cells at the entry cell location
        entryCell.isBlocked = false
        // check if entry cell will be available
        // check if an item will not move into it
        val pc = entryCell.previousCell
        if (pc != null) {
            // has previous cell
            if (pc.isNotOccupied) {
                // nothing will move into entry cell from behind it on conveyor, during next move
                // entry cell will not be occupied during the next move
                handleWaitingRequestsForAccumulatingConveyor(entryCell)
            }
        } else {
            // no previous cell, nothing can come from behind it
            handleWaitingRequestsForAccumulatingConveyor(entryCell)
        }

    }

    private inner class EndOfCellTraversalActionV2 : EventAction<List<Cell>>() {
        override fun action(event: KSLEvent<List<Cell>>) {
            ProcessModel.logger.info { "$time > ***** started end of cell traversal action *****" }
            val cellsToMove = event.message!!
            //TODO not sure if a simple list will work
            moveItemsForwardOneCell(cellsToMove)
            // items moved, could have uncovered entry or exit cells
//            processWaitingRequests()//TODO this should be handled when the cell is unblocked not here
            ProcessModel.logger.info { "$time > ***** completed end of cell traversal action *****" }
            // reschedule next traversal based on type of conveyor
            scheduleConveyorMovement()
        }

    }

    inner class ConveyorRequest(
        val entity: ProcessModel.Entity,
        val numCellsNeeded: Int,
        override val entryLocation: IdentityIfc,
        val accessResumePriority: Int
    ) : QObject(), ConveyorRequestIfc {
        init {
            require(numCellsNeeded >= 1) { "The number of cells requested must be >= 1" }
            require(numCellsNeeded <= maxEntityCellsAllowed) {
                "The entity requested more cells ($numCellsNeeded) than " +
                        "the allowed maximum ($maxEntityCellsAllowed) for for conveyor (${this.name})"
            }
            require(entryLocations.contains(entryLocation)) { "The location (${entryLocation.name}) of requested cells is not on conveyor (${conveyor.name})" }
            priority = entity.priority
        }

        override val conveyor = this@Conveyor
        val entryCell: Cell = entryCells[entryLocation]!!
        override var currentLocation: IdentityIfc = entryLocation //TODO need to update current location when it arrives at destination, when request is resumed
            internal set
        override var destination: IdentityIfc? = null
            internal set

        internal var state: RequestState = requestWaitingForEntryState

        internal fun mustSuspend(): Boolean {
            return !isEntryPossible(currentLocation)
        }

        override val isWaitingForEntry: Boolean
            get() = state == requestWaitingForEntryState

        override val isBlockingEntry: Boolean
            get() = state == requestBlockingEntryState

        override val isRiding: Boolean
            get() = state == requestRidingState
        override val isBlockingExit: Boolean
            get() = state == requestBlockingExitState

        override val isCompleted: Boolean
            get() = state == requestCompletedState

        internal fun blockEntry() {
            state.blockEntryCell(this)
        }

        internal fun ride() {
            state.ride(this)
        }

        internal fun blockExit(request: ConveyorRequest) {
            state.blockExitCell(request)
        }

        internal fun complete(request: ConveyorRequest) {
            state.complete(request)
        }

    }

    private val requestWaitingForEntryState = WaitingForEntry()
    private val requestBlockingEntryState = BlockingEntry()
    private val requestRidingState = Riding()
    private val requestBlockingExitState = BlockingExit()
    private val requestCompletedState = Completed()

    internal abstract inner class RequestState(val stateName: String) {

        open fun blockEntryCell(request: ConveyorRequest) {
            errorMessage("blockEntryCell()")
        }

        open fun ride(request: ConveyorRequest) {
            errorMessage("ride()")
        }

        open fun blockExitCell(request: ConveyorRequest) {
            errorMessage("blockExitCell()")
        }

        open fun complete(request: ConveyorRequest) {
            errorMessage("complete()")
        }

        private fun errorMessage(routineName: String) {
            val sb = StringBuilder()
            sb.appendLine("Using $routineName : Tried to transition a cell request for ${name} to an illegal state from state $stateName")
            ProcessModel.logger.error { sb.toString() }
            throw ksl.utilities.exceptions.IllegalStateException(sb.toString())
        }
    }

    internal inner class WaitingForEntry : RequestState("Waiting") {
        override fun blockEntryCell(request: ConveyorRequest) {
            request.state = requestBlockingEntryState
            blockEntry(request)
        }
    }

    internal inner class BlockingEntry : RequestState("BlockingEntry") {
        override fun ride(request: ConveyorRequest) {
            request.state = requestRidingState
            startConveyance(request)
        }

        override fun complete(request: ConveyorRequest) {
            request.state = requestCompletedState
        }
    }

    internal inner class Riding : RequestState("Riding") {
        override fun blockExitCell(request: ConveyorRequest) {
            request.state = requestBlockingExitState
            blockExit(request)
        }
    }

    internal inner class BlockingExit : RequestState("BlockingExit") {
        override fun ride(request: ConveyorRequest) {
            request.state = requestRidingState
            continueConveyance(request)
        }

        override fun complete(request: ConveyorRequest) {
            request.state = requestCompletedState
        }
    }

    internal inner class Completed : RequestState("Completed")

    internal fun requestConveyor(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int,
        entryLocation: IdentityIfc,
        accessResumePriority: Int
    ): ConveyorRequest {
        val request = ConveyorRequest(entity, numCellsNeeded, entryLocation, accessResumePriority)
        accessQueues[entryLocation]!!.enqueue(request)
        return request
    }

    private fun blockEntry(request: ConveyorRequest) {

        TODO("Need to implement Conveyor.blockEntry()")
    }

    private fun startConveyance(request: ConveyorRequest) {
        TODO("Need to implement Conveyor.convey()")
    }

    private fun blockExit(request: ConveyorRequest) {
        //TODO request.currentLocation = request.entryLocation??? needs to be set when request is resumed
        TODO("Need to implement Conveyor.blockExit()")
    }

    private fun continueConveyance(request: ConveyorRequest) {
        TODO("Need to implement Conveyor.convey()")
    }
}

interface ConveyorRequestIfc {
    val conveyor: Conveyor
    val isWaitingForEntry: Boolean
    val isBlockingEntry: Boolean
    val isRiding: Boolean
    val isBlockingExit: Boolean
    val isCompleted: Boolean

    /**
     *  The location where the entity first accessed the conveyor
     */
    val entryLocation: IdentityIfc

    /**
     * The current location of the entity. This is assigned
     * when the entity arrives at the end of a segment
     */
    val currentLocation: IdentityIfc

    /**
     * The final location where the entity wants to visit on the conveyor
     */
    val destination: IdentityIfc?
}

fun main() {

    runConveyorTest(Conveyor.Type.ACCUMULATING)
//    runConveyorTest(Conveyor.Type.NON_ACCUMULATING)
//    blockedCellsTest()
}

fun buildTest() {
    val i1 = Identity(aName = "A")
    val i2 = Identity(aName = "B")
    val i3 = Identity(aName = "C")
    val c = Conveyor.builder(Model())
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20)
        .build()

    println(c)
}

fun blockedCellsTest() {
    val i1 = Identity(aName = "A")
    val i2 = Identity(aName = "B")
    val i3 = Identity(aName = "C")
    val c = Conveyor.builder(Model())
        .conveyorType(Conveyor.Type.ACCUMULATING)
        .velocity(1.0)
        .cellSize(1)
        .firstSegment(i1, i2, 12)
        .build()

//        .nextSegment(i3, 20)
//        .build()

    c.conveyorCells[0].isBlocked = true
    c.conveyorCells[4].isBlocked = true
    c.conveyorCells[7].isBlocked = true
//    c.conveyorCells[11].isBlocked = true
    println(c)
    println()
    val fbc = c.firstBlockedCellFromEnd()
    println("First blocked cell: ${fbc?.cellNumber}")
    println()
    val (f, s) = c.partitionAtFirstBlockage()
    println("Cells before first blocked cell")
    println(f.joinToString(prefix = "{", postfix = "}", transform = { it -> "${it.cellNumber}" }))
    println("Cells after first blocked cell")
    println(s.joinToString(prefix = "{", postfix = "}", transform = { it -> "${it.cellNumber}" }))
    println()
    val m = c.cellsBehindBlockedCells(f)
    println("Cells behind blocked cells:")
    for ((k, v) in m) {
        print("${k.cellNumber}: ")
        println(v.joinToString(prefix = "{", postfix = "}", transform = { it -> "${it.cellNumber}" }))
    }

}

class TestConveyor(parent: ModelElement, conveyorType: Conveyor.Type) : ProcessModel(parent) {

    val conveyor: Conveyor
    val i1 = Identity(aName = "A")
    val i2 = Identity(aName = "B")
    val i3 = Identity(aName = "C")

    init {
        conveyor = Conveyor.builder(this)
            .conveyorType(conveyorType)
            .velocity(1.0)
            .cellSize(1)
            .firstSegment(i1, i2, 10)
            .nextSegment(i3, 20)
            //           .nextSegment(i1, 15)
            .build()
        println(conveyor)
        println()
    }

    override fun initialize() {
        val p1 = Part("Part1")
        activate(p1.conveyingProcess)
        val p2 = Part("Part2")
        activate(p2.conveyingProcess, timeUntilActivation = 0.1)
        val p3 = Part("Part3")
        activate(p3.conveyingProcess, timeUntilActivation = 0.1)
    }

    private inner class Part(name: String? = null) : Entity(name) {
        val conveyingProcess: KSLProcess = process("test") {
            println("${entity.name}: time = $time before access at ${i1.name}")
            val a = requestConveyor(conveyor, i1)
            println("${entity.name}: time = $time after access")
            //           delay(10.0)
            timeStamp = time
            if (entity.name == "Part1") {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            } else {
                println("${entity.name}: time = $time before ride to ${i2.name}")
                rideConveyor(a, i2)
                println("${entity.name}: time = $time after ride to ${i2.name}")
            }
            println("${entity.name}: The riding time was ${time - timeStamp}")
//            delay(10.0)
//            println("${entity.name}: time = $time after second delay of 10.0 ")
            println("${entity.name}: time = $time before exit ")
            exitConveyor(a)
            println("${entity.name}: time = $time after exit ")
        }

    }

}

fun runConveyorTest(conveyorType: Conveyor.Type) {
    val m = Model()
    val test = TestConveyor(m, conveyorType)
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 1
    m.simulate()
    m.print()
}
