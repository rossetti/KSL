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
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.modeling.variable.TWResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.IdentityIfc
import ksl.utilities.Interval

/** A conveyor consists of a series of segments. A segment has a starting location (origin) and an ending location
 * (destination) and is associated with a conveyor. The start and end of each segment represent locations along
 * the conveyor where entities can enter and exit.  A conveyor does not utilize a spatial model. The locations
 * associated with its segments are instances of the interface IdentityIfc. Thus, movement on the conveyor is
 * disassociated with any spatial model associated with the entity using the conveyor.  Since a spatial model
 * using instances of the LocationIfc interface and LocationIfc extends the IdentityIfc, the locations associated
 * with segments can be the same as those associated with spatial models. If the destination of a ride request
 * is an instance of LocationIfc then the entity's curren location property will be updated after the movement.
 * However, if the segments are not defined by locations within a spatial model, no updating of the entity's location
 * will take place. The modeler is responsible for making the update to the entity location, if applicable.
 *
 * A conveyor has a cell size, which represents the length of
 * each cell on all segments of the conveyor. A conveyor also has a maximum permitted number of cells that can be
 * occupied by an item riding on the conveyor.  A conveyor has an [initialVelocity].  Each segment moves at the same
 * velocity.
 *
 * Each segment has a specified length that is divided into a number of equally sized contiguous cells.
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
 * rear cell associated with the item is cell 2.
 *
 * An entity trying to access the conveyor at an entry cell of the conveyor, waits until it can block the cell. A request
 * for entry on the conveyor will wait for the entry cell if the entry cell is unavailable (blocked or occupied) or if
 * another item is positioned to use the entry cell.  Once
 * the entity has control of the entry cell, this creates a blockage on the conveyor, which may restrict conveyor movement.
 * For a non-accumulating conveyor the blockage stops all movement on the conveyor. For accumulating conveyors, the blockage
 * restricts movement behind the blocked cell.  Gaining control of the entry cell does not position the entity
 * to ride on the conveyor. The entity simply controls the entry cell and causes a blockage. The entity is not on the conveyor
 * during the blockage.
 *
 * If the entity decides to ride on the conveyor, the entity will be allocated
 * cells based on its request and occupy those cells while moving on the conveyor. First, the entity's request is
 * removed from the request queue and positioned to enter the conveyor at the entry cell. To occupy a cell, the entity
 * must move the distance represented by the cell (essentially covering the cell).  Thus, an entering entity takes
 * the time needed to move through its required cells before being fully on the conveyor.  An entity occupies a cell during the
 * time it traverses the cell's length. Thus, assuming a single item, the time to move from the start of a segment
 * to the end of the segment is the time that it takes to travel through all the cells of the segment, including the
 * entry cell and the exit cell.
 *
 * When an entity riding on the conveyor reaches its destination (an exit cell), the entity causes a blockage on the conveyor.
 * For a non-accumulating conveyor, the entire conveyor stops.  For an accumulating conveyor, the blockage restricts movement
 * behind the blockage causing items behind the blockage to continue to move until they cannot move forward. When an entity
 * exits the conveyor, there will be a delay for the entity to move through the cells that it occupies before the entity
 * can be considered completely off of the conveyor. Thus, exiting the conveyor is not assumed to be instantaneous. This implementation
 * assumes that the entity must move through the occupied cells to get off the conveyor.  Any delay to unload the item
 * from the conveyor will be in addition to the exiting delay.  Thus, a modeling situation in which the entity is picked up
 * off the conveyor may need to account for the "extra" exiting delay. This implementation basically assumes that the item
 * is pushed through the occupying cells at the end of the conveyor in order to exit. Scenarios where the item is
 * picked up would not necessarily require the time to move through the cells.
 *
 * A conveyor is considered circular if the entry location of the first segment is the same as the exit location of the last segment.
 *
 * To construct a conveyor use the supplied builder or specify the segment data.
 *
 * @param parent the containing model element
 * @param velocity the initial velocity of the conveyor
 * @param segmentData the specification of the segments
 * @param maxEntityCellsAllowed the maximum number of cells that an entity can occupy while riding on the conveyor
 * @param name the name of the conveyor
 */
class Conveyor(
    parent: ModelElement,
    segmentData: ConveyorSegments,
    val conveyorType: Type = Type.ACCUMULATING,
    velocity: Double = 1.0,
    val maxEntityCellsAllowed: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(maxEntityCellsAllowed >= 1) { "The maximum number of cells that can be occupied by an entity must be >= 1" }
        require(segmentData.isNotEmpty()) { "The segment data must not be empty." }
        require(velocity > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
    }

    enum class Type {
        ACCUMULATING, NON_ACCUMULATING
    }

    var defaultMovementPriority = KSLEvent.DEFAULT_PRIORITY + 1

    /**
     * The initial velocity is the default movement velocity through cells on the
     * conveyor.  Changing the initial velocity will change the velocity that is
     * set at the beginning of each replication.
     */
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
//    internal val conveyorHoldQ = HoldQueue(this, "${this.name}:HoldQ")
    internal val exitingHoldQ =  HoldQueue(this, "${this.name}:ExitingHoldQ")
    internal val ridingHoldQ =  HoldQueue(this, "${this.name}:RidingHoldQ")
    internal val accessingHoldQ =  HoldQueue(this, "${this.name}:AccessingHoldQ")
    //TODO

    init {
//        conveyorHoldQ.waitTimeStatOption = false
//        conveyorHoldQ.defaultReportingOption = false
    }

    private val conveyorSegments: ConveyorSegments = segmentData

    val cellSize: Int = conveyorSegments.cellSize

    // the totality of cells representing space on the conveyor
    val conveyorCells: List<Cell>

    val numOccupiedCells: Int
        get() {
            var sum = 0
            for(cell in conveyorCells){
                if (cell.isOccupied){
                    sum++
                }
            }
            return sum
        }

    // the cells associated with each entry location
    private val entryCells = mutableMapOf<IdentityIfc, Cell>()

    // the cells associated with each exit location
    private val exitCells = mutableMapOf<IdentityIfc, Cell>()

    // holds the queues that hold requests that are waiting to use the conveyor associated with each entry location
    private val accessQueues = mutableMapOf<IdentityIfc, ConveyorQ>()

    // holds the requests that have requested to ride after blocking an entry cell
    private val positionedToEnter = mutableMapOf<Cell, ConveyorRequest>()

    /** indicates if entering items have priority at entry cell over items riding on the conveyor
     *  The default is no priority (false)
     */
    private val enteringPriority = mutableMapOf<IdentityIfc, Boolean>()

    fun hasItemPositionedToEnter(): Boolean = positionedToEnter.isNotEmpty()

    val segments: List<CSegment>

    /**
     *  Turns [on] or off the default reporting for the utilization and time average
     *  number of busy cells for each segment of the conveyor
     */
    fun segmentStatisticalReporting(on: Boolean = true){
        for(seg in segments){
            seg.myNumOccupiedCells.defaultReportingOption = on
            seg.cellUtilization.defaultReportingOption = on
//            seg.myTraversalTime.defaultReportingOption = on
        }
    }

    init {
        // constructs the cells based on the segment data
        val cells = mutableListOf<Cell>()
        val segs = mutableListOf<CSegment>()
        for (segment in conveyorSegments.segments) {
            val numCells = segment.length / cellSize
            var entryCell: Cell?
            var exitCell: Cell?
            require(numCells >= 2) { "There must be at least 2 cells on each segment" }
            for (i in 1..numCells) {
                if (i == 1) {
                    entryCell = Cell(CellType.ENTRY, segment.entryLocation, segment, cells)
                    entryCells[segment.entryLocation] = entryCell
                    enteringPriority[segment.entryLocation] = false
                    accessQueues[segment.entryLocation] =
                        ConveyorQ(this, "${this.name}:${segment.entryLocation.name}:AccessQ")
                } else if (i == numCells) {
                    exitCell = Cell(CellType.EXIT, segment.exitLocation, segment, cells)
                    exitCells[segment.exitLocation] = exitCell
                } else {
                    Cell(CellType.INNER, null, segment, cells)
                }
            }
        }
        require(cells.size >= 2) { "There must be 2 or more cells for the conveyor" }
        if (cells.size == 2) {
            require(cells.first().isEntryCell && cells.last().isExitCell) { "With 2 cells, there must be an entry cell and an exit cell." }
        }
        conveyorCells = cells.toList()
        for ((index, segment) in conveyorSegments.segments.withIndex()) {
            segs.add(CSegment(index + 1, entryCells[segment.entryLocation]!!, exitCells[segment.exitLocation]!!))
        }
        segments = segs.toList()
    }

    private val myNumOccupiedCells = TWResponse(this, "${this.name}:NumOccupiedCells", allowedDomain = Interval(0.0, conveyorCells.size.toDouble()))
    val numberOfOccupiedCells: TWResponseCIfc
        get() = myNumOccupiedCells

    private val myCellUtilization = Response(this, "${this.name}:CellUtilization")
    val cellUtilization: ResponseCIfc
        get() = myCellUtilization

    /**
     * The event associated with the movement of the items on the conveyor.
     * When this event is scheduled, this means that items are moving (traversing) through
     * the next cell, or exiting through their last cell to exit the conveyor. We
     * always allow the traversal to complete.
     *
     * The event will be set to null under the following conditions:
     * a) non-accumulating conveyor: a blockage exists or there are no items on the conveyor
     * b) accumulating conveyor: there are no items that can move due to blockages or no items on the conveyor
     */
    private var endCellTraversalEvent: KSLEvent<Nothing>? = null

    /**
     *  Temporarily holds the stopped cell traversal event if the conveyor is stopped
     */
    private var stoppedCellTraversalEvent: KSLEvent<Nothing>? = null

    /**
     *  indicates if the conveyor has been stopped via the stopConveyor() function
     */
    var isStopped: Boolean = false
        private set

    /**
     *  Returns the queue that holds requests that are waiting to access the
     *  conveyor at the supplied [location]
     */
    fun accessQueueAt(location: IdentityIfc): QueueCIfc<ConveyorRequest> {
        require(accessQueues.contains(location)) { "The origin ($location) is not a valid entry location on the conveyor" }
        return accessQueues[location]!!
    }

    /**
     *  By default (false), items riding on the conveyor have priority to enter entry cells over items
     *  waiting to enter.  By setting the priority to true, the items waiting to begin riding the
     *  conveyor have priority to access over items already riding on the conveyor.
     */
    fun enteringPriorityAt(entryLocation: IdentityIfc, priority: Boolean) {
        require(entryLocations.contains(entryLocation)) { "The location (${entryLocation.name}) is not an entry location for (${name})" }
        enteringPriority[entryLocation] = priority
    }

    /**
     *  The current velocity of the conveyor. A change will remain in effect
     *  until the end of a replication at which time the velocity will be reset
     *  to the initial velocity associated with the conveyor at time 0.0
     */
    var velocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The velocity of the conveyor must be > 0.0" }
            field = value
        }

    /**
     * The time that it takes an item on the conveyor to
     * travel through a cell in order to fully occupy the cell. This is
     * based on the cell size and the velocity of the conveyor.
     */
    val cellTravelTime: Double
        get() = cellSize / velocity

    /**
     *  Indicates if the conveyor's segments form a loop such that
     *  the location associated with the first segment is the same
     *  as the ending location of the last segment
     */
    val isCircular = conveyorSegments.isCircular

    /**
     *  The locations that can be used to enter (get on) the conveyor.
     */
    val entryLocations = conveyorSegments.entryLocations

    /**
     *  The locations that can be used as points of exit on the conveyor.
     */
    val exitLocations = conveyorSegments.exitLocations

    /**
     *  Determines if the [end] location is reachable from the [start] location.
     *  A location is reachable if the item can ride on the conveyor from
     *  the start location to the end location without exiting the conveyor.
     */
    fun isReachable(start: IdentityIfc, end: IdentityIfc): Boolean {
        return conveyorSegments.isReachable(start, end)
    }

    /**
     * A list of all conveyor requests that are currently associated with (occupying)
     * cells on the conveyor in the order from the furthest along (closest to the exit location of the last segment)
     * to the nearest request (closest to the entry location of the first segment).
     */
    fun conveyorRequests(): List<ConveyorRequestIfc> {
        return conveyorRequests(conveyorCells)
    }

    /**
     *  Returns a list of items that are associated with
     *  the supplied cell list, ordered from the furthest cell to the closest cell
     *  based on the direction of travel on the conveyor.
     */
    private fun conveyorRequests(cellList: List<Cell>): List<ConveyorRequestIfc> {
        val list = mutableSetOf<ConveyorRequestIfc>()
        for (cell in cellList.reversed()) {
            val item = cell.item
            if (item != null) {
                list.add(item)
            }
        }
        return list.toList()
    }

    /**
     * If any of the cells of the conveyor are occupied by an entity
     * then return true; otherwise, if there are no entities occupying
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
     *  Checks if the conveyor has any blocked cells. A cell can be blocked
     *  for entry or for exit.
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
     *  conveyor. If no cells are blocked, then null is returned.
     */
    private fun firstBlockedCellFromEnd(): Cell? {
        return conveyorCells.asReversed().firstOrNull { it.isBlocked }
    }

    /**
     *  Finds the first occupied cell from the start of the conveyor.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 5, 8 are occupied. Then, cell 5 is the first occupied cell from the start of the
     *  conveyor. If no cells are occupied then null is returned.
     */
    private fun firstOccupiedCellFromStart(): Cell? {
        return conveyorCells.firstOrNull { it.isOccupied }
    }

    /**
     * Finds the first blocked cell from the supplied cell going
     * forward on the conveyor. If there are no blocked cells, then
     * null is returned.
     */
    private fun firstBlockedCellForwardFromCell(startingCell: Cell): Cell? {
        return conveyorCells.subList(startingCell.index, conveyorCells.size).firstOrNull { it.isBlocked }
    }

    /**
     *  Checks if the supplied cell could move forward during the next cell traversal.
     *  Returns true if the cell immediately in front of the cell is not occupied or
     *  if the cell would be part of a train of cells that are pulled forward by
     *  a lead cell behind a blockage.  If there are no blockages, then if the
     *  conveyor has a movable cell then a train can be formed. The supplied
     *  cell must be an occupied cell.
     */
    private fun couldCellMoveForward(cell: Cell): Boolean {
        require(cell.isOccupied) { "The supplied cell is not occupied. No reason to check if it could move." }
        if (cell.nextCell != null) {
            // there is a next cell
            if (cell.nextCell!!.isNotOccupied) {
                return true
            }
            // the next cell is occupied, check for movable train
            val fbc = firstBlockedCellForwardFromCell(cell)
            if (fbc == null) {
                // no blockages going forward, check entire conveyor
                return findLeadingCell(conveyorCells) != null
            } else {
                // subset before first blocked cell
                val cells = conveyorCells.subList(cell.index, fbc.index)
                return findLeadingCell(cells) != null
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
    private fun cellsFromEndUntilFirstBlockage(): List<Cell> {
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
     *  of the pair contains all cells before (but not including) the first blocked
     *  cell from the end of the list. The second of the pair contains
     *  all cells after the blockage *including* the blockage.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 8 are blocked. Because cell 8 is the first blocked cell from the end of the
     *  list, the returned pair contains:
     *
     *  first = {1, 2, 3, 4, 5, 6, 7} (does not include the first blockage)
     *  second = {8, 9, 10, 11, 12} (includes the first blockage)
     *
     *  If the conveyor has no blocked cells, then the pair is:
     *
     *  first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
     *  second = {}
     *
     *  where the second is an empty list because there is no blockage to include.
     *
     *  If the last cell is the first blocked cell, then the pair is:
     *
     *  first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}
     *  second = {12}
     */
    private fun partitionAtFirstBlockageAfterInclusive(): Pair<List<Cell>, List<Cell>> {
        val fbc = firstBlockedCellFromEnd()
        if (fbc == null) {
            // no blocked cell, first is empty, second is all the cells
            return Pair(conveyorCells, emptyList())
        } else {
            if (conveyorCells.last() == fbc) {
                return Pair(conveyorCells.subList(0, fbc.index), conveyorCells.subList(fbc.index, conveyorCells.size))
            } else {
                val first = conveyorCells.subList(0, fbc.index)
                val second = conveyorCells.subList(fbc.index, conveyorCells.size)
                return Pair(first, second)
            }
        }
    }

    /**
     *  Partitions the conveyor cells into to sub lists. The first
     *  of the pair contains all cells before (and including) the first blocked
     *  cell from the end of the list. The second of the pair contains
     *  all cells after the blockage excluding the blockage.
     *  For example, suppose we have 12 cells (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12).
     *  Suppose cells 1, 5, 8 are blocked. Because cell 8 is the first blocked cell from the end of the
     *  list, the returned pair contains
     *  first = {1, 2, 3, 4, 5, 6, 7, 8} (includes the first blockage)
     *  second = {9, 10, 11, 12} (excludes the first blockage)
     *  If the conveyor has no blocked cells, then the pair is:
     *  first = {}
     *  second = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
     *  where the first is an empty list.
     *  If the last cell is the first blocked cell, then the pair is:
     *  first = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12}
     *  second = {}
     */
    private fun partitionAtFirstBlockageAfterExclusive(): Pair<List<Cell>, List<Cell>> {
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
     *  1 : {empty}, because there are no cells in front of cell 1
     *  5 : {2, 3, 4}
     *  11 : {6, 7, 8, 9, 10}
     *  12 : {empty}, because there are no cells between cell 11 and 12
     *  ```
     */
    private fun cellsBehindBlockedCells(cells: List<Cell>): Map<Cell, List<Cell>> {
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
     *  Processing the cells in reverse order, find the first cell that is occupied and for which its next cell exists and
     *  is available.  This returns the furthest cell (towards the end of the list) that can
     *  be traversed by an item. This cell holds the lead item of the list.
     *  If found, the lead cell must be occupied and cannot be blocked.
     */
    private fun findLeadingCell(cells: List<Cell>): Cell? {
        val reversedList = cells.asReversed()
        for (cell in reversedList) {
            if (cell.isOccupied) {
                if (cell.type != CellType.EXIT) {
                    // must be inner or entry cell, it *must* have a next cell
                    // the next cell must be either an inner cell or an exit cell
                    val nextCell = cell.nextCell
                    if (nextCell != null) {
                        if (nextCell.isAvailable) {
                            // use isAvailable, because the next cell could be an exit, which might be blocked
                            return cell
                        }
                    }
                } else {
                    // the cell must be an exit cell
                    // if the exit cell is blocked, we cannot move through it
                    // if the exit cell is not blocked and occupied, the item might be able to move
                    if (cell.isNotBlocked) {
                        // if the exit cell has an item that is exiting, then let it move
                        if (cell.item!!.status == ItemStatus.EXITING) {
                            return cell
                        }
                        // exit cell and not blocked, and not exiting
                        val nextCell = cell.nextCell
                        // it might be the LAST cell
                        if (nextCell != null) {
                            // not the LAST cell, because next cell exists, also could be end cell in circular conveyor
                            // use isAvailable, because the next cell will be an entry cell, which might be blocked
                            // next cell must be an entry cell
                            require(nextCell.isEntryCell) { "The cell must be an entry cell if this was reached." }
                            if (nextCell.isAvailable) {
                                // not blocked and not occupied, but entry cell could have something entering
                                if (positionedToEnter[nextCell] == null) {
                                    // nothing entering, cell is okay to enter
                                    return cell
                                }
                            }
                        } else {
                            // must be the last cell, and conveyor is not circular, because there is no next cell
                            //check(cell == conveyorCells.last()){"In findLeadingCell(): cell must be last cell of list"}
                            // the last cell is not blocked, and it is occupied
                            // to move forward, the occupying item must be exiting
                            if (cell.item!!.status == ItemStatus.EXITING) {
                                return cell
                            } else {
                                throw IllegalStateException("First movable cell was the cell at end of non-circular conveyor and item was not exiting")
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     *  True if there is at least one cell that is occupied that can move on the conveyor
     */
    fun hasMovableCell(): Boolean {
        return findLeadingCell(conveyorCells) != null
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
     *
     *  Called from within endCellTraversal() event
     */
    private fun moveItemsForwardOneCell(cells: List<Cell>) {
        //TODO review the logic of this function moveItemsForwardOneCell()
        if (cells.isEmpty()) {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR ($name): no movable cells" }
            return
        }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR ($name): movable cells: [${cells.first().cellNumber}..${cells.last().cellNumber}]" }
        require(cells.last().isOccupied) { "CONVEYOR ($name): The last cell (${cells.last().cellNumber}) of the list was not occupied by an item" }
        // the last cell of the list MUST now be occupied by an item
        // the last cell of the list may be the cell at the end of non-circular conveyor
        if (cells.last() == conveyorCells.last() && !isCircular) {
            // the last cell is the conveyor's last cell and the conveyor is not circular
            // the item at the last cell MUST be exiting
            val item = cells.last().item!!
            if (item.status != ItemStatus.EXITING) {
                throw IllegalStateException("The last cell in the move forward list was the last cell of the conveyor but the item was not exiting.")
            }
            // at this point the item in the last cell must be exiting
            // special case to allow movement to the exiting item at end of the conveyor
            moveItemsForwardThroughCells(cells)
        } else {
            // the last cell of the list is not the last cell of the conveyor, check to make sure that there is a next cell
            require(cells.last().nextCell != null) { "CONVEYOR ($name): The last cell of the list does not have a following cell" }
            val item = cells.last().item!!
            // item could be exiting or not, here. If it is not exiting, the next cell must be available. If it is exiting, then it moves through the final cell.
            if (item.status != ItemStatus.EXITING) {
                // item is not exiting, ensure that next cell is available
                require(cells.last().nextCell!!.isAvailable) { "CONVEYOR ($name): The cell (${cells.last().nextCell!!.cellNumber}) after the last cell (${cells.last().cellNumber}) is not available \n ${toString()}" }
            }
             moveItemsForwardThroughCells(cells)
        }
    }

    /**
     *  Causes the items that are occupying cells within the list to move forward
     *  by one cell. This is called by functions within the endOfCellTraversal() event.
     *  Each item is moved (copied) to the next cell starting with the last cell
     *  and working in reverse order. If the previous cell has an item,
     *  the item is moved to the next cell, and so forth.
     */
    private fun moveItemsForwardThroughCells(cells: List<Cell>) {
        var lastItem: ConveyorRequest? = null
        for (cell in cells.asReversed()) {
            if (cell.item != lastItem) {
                // new item, remember it
                lastItem = cell.item
                if (cell.item != null) {
                    cell.item!!.moveForwardOneCell()
                }
            }
        }
    }

    override fun initialize() {
        velocity = initialVelocity
        for (cell in conveyorCells) {
            cell.item = null
            cell.isBlocked = false
        }
        positionedToEnter.clear()
    }

    override fun replicationEnded() {
        myCellUtilization.value = myNumOccupiedCells.withinReplicationStatistic.weightedAverage / conveyorCells.size
        for (cSeg in segments){
            cSeg.replicationEnded()
        }
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

    /**
     *  If the conveyor has been stopped using the stopConveyor() function, this will start it moving.
     *  If the conveyor has not been stopped using the stopConveyor() function, this method does nothing.
     */
    fun startConveyor() {
        if (isStopped) {
            isStopped = false
            val event = stoppedCellTraversalEvent
            if (event != null) {
                if (event.time > time) {
                    // The cancelled event is still in the future undo the cancellation
                    event.cancel = false;
                    endCellTraversalEvent = event
                } else {
                    // the time of the cell traversal has passed, need to restart the conveyor
                    // for simplicity we assume the full traversal time
                    rescheduleConveyorMovement()
                }
            }
        }
    }

    /**
     *  If a cell traversal is scheduled, it will be cancelled and movement on the conveyor
     *  will not occur.
     */
    fun stopConveyor() {
        isStopped = true
        stoppedCellTraversalEvent = endCellTraversalEvent
        cancelConveyorMovement()
    }

    enum class ItemStatus {
        OFF, ENTERING, EXITING, ON
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

        internal lateinit var cSegment: CSegment

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

        var item: ConveyorRequest? = null
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
                    "isOccupied=$isOccupied, isBlocked=$isBlocked, item=${item?.entity?.name}, item status = ${item?.status})"
        }
    }

    companion object {
        /**
         * Creates a builder that uses chained calls to construct a conveyor
         */
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
        private lateinit var segmentsData: ConveyorSegments
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
            segmentsData = ConveyorSegments(cellSize, start)
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
        sb.appendLine("max number cells allowed to occupy = $maxEntityCellsAllowed")
        sb.appendLine("cell Travel Time = $cellTravelTime")
        sb.appendLine("Segments:")
        sb.append(conveyorSegments)
        sb.appendLine("Segment Cells:")
        for (seg in segments) {
            sb.appendLine(seg)
        }
        sb.appendLine("Cells:")
        for (cell in conveyorCells) {
            sb.appendLine(cell)
        }
        return sb.toString()
    }

    /**
     * Based on finding the lead cell, this method finds the cells
     * contain cells that can move.
     */
    private fun findMovableCells(cells: List<Cell>): List<Cell> {
        val fmc = findLeadingCell(cells)
        if (fmc == null) {
            return emptyList()
        } else {
            return conveyorCells.subList(cells.first().index, fmc.index + 1)
        }
    }

    /**
     *  This method is called after the item moves off the conveyor during the movement through cells.
     *  This method is called from ConveyorRequest.moveForwardOneCell().
     *  This method resumes the entity associated with the request that is exiting.
     */
    private fun itemFullyOffConveyor(request: ConveyorRequest, exitCell: Cell) {
        //TODO this is where entity is resumed after exiting
        require(request.isBlockingExit) { "The request must be in the blocking exit state when moving off the conveyor" }
        require(exitCell.isExitCell) { "The supplied cell was not an exit cell" }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > Request (${request.name}): status = ${request.status}: entity_id = ${request.entity.id} : fully off the conveyor: removing blockage" }
        removeBlockage(exitCell)
        // item completed the exiting process, tell the entity that it can proceed
        exitingHoldQ.removeAndResume(request.entity)
        // cause the transition to the complete state from the blocking exit state
        request.exitConveyor()
    }

    /**
     *  This method is called during the movement through cells, when an item
     *  reaches the exit cell that is its destination.
     *  This method is called from ConveyorRequest.moveForwardOneCell()
     */
    private fun itemReachedDestination(request: ConveyorRequest) {
        //TODO this is where the conveyor hold queue is resumed after riding, how does this get triggered
        // this is called in moveForwardOneCell()
        request.currentLocation = request.destination!!
        // the trip has ended, need to block exit, resume the entity to proceed with exit
        // or allow it to start its next ride
        val exitCell = exitCells[request.destination]!!
        exitCell.isBlocked = true
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Request (${request.name}): status = ${request.status}: entity_id = ${request.entity.id} : has blocked exit cell (${exitCell.cellNumber} : itemReachedDestination())" }
        request.blockExitLocation() //I wonder if conveyor check and canceling should be in blockExitLocation()
        if (conveyorType == Type.NON_ACCUMULATING) {
            cancelConveyorMovement()
        }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : resuming after reaching destination: (${request.destination!!.name}) : itemReachedDestination()" }
        ridingHoldQ.removeAndResume(request.entity)
        // conveyorHoldQ.removeAndResume(request.entity, request.accessResumePriority, false)
    }

    /**
     *  The main event routine for conveyor movement. The function processes the cells in the following order:
     *  1) moves any cells forward on the conveyor that can be moved
     *  2) move any items positioned to enter the conveyor at an entry cell onto the conveyor
     *  3) evaluates items waiting in access queues to see if they can move into position to enter
     *  4) determines whether further movement is needed and schedules the next end of cell traversal if needed.
     */
    private fun endOfCellTraversal(event: KSLEvent<Nothing>) {
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : ***** started end of cell traversal action ***** : CONVEYOR (${this@Conveyor.name})" }
        // determine cells to move forward and move them forward
        moveCellsOnConveyor()
        // move items that have asked to ride and occupy an entry cell
        processItemsPositionedToEnter()
        // entry cells may have become unblocked after processing items positioned to enter or moving items
        // process those items waiting to access the conveyor to allow them to block the entry
        processRequestsWaitingToAccessConveyor()
        // reschedule the conveyor movement if needed
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}) : checking for conveyor movement at end of move cycle" }
        rescheduleConveyorMovement()
        // after all the movement capture the cells that are occupied
        captureCellUsage()
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : ***** end of cell traversal action ***** : CONVEYOR (${this@Conveyor.name})" }
    }

    private fun captureCellUsage(){
        // called from endOfCellTraversal() event
        myNumOccupiedCells.value = numOccupiedCells.toDouble()
        for (seg in segments){
            seg.myNumOccupiedCells.value = seg.numOccupiedCells.toDouble()
        }
    }

    /**
     *  Checks if a movement through cells is scheduled to occur
     */
    private fun isCellTraversalInProgress(): Boolean {
        return endCellTraversalEvent != null && endCellTraversalEvent!!.isScheduled && !endCellTraversalEvent!!.cancel
    }

    /**
     *  Items waiting to access the conveyor are checked to see if they can move into position
     *  to block an entry cell of the conveyor.
     */
    private fun processRequestsWaitingToAccessConveyor() {
        //TODO this resumes the entity in hold to access conveyor
        for ((location, cell) in entryCells) {
            if ((cell.isNotBlocked) && !positionedToEnter.containsKey(cell)) {
                val prevCell = cell.previousCell
                if (enteringPriority[location] == false) {
                    if (prevCell != null) {
                        if (prevCell.isOccupied && (prevCell.item!!.status != ItemStatus.EXITING)) {
                            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): processing waiting requests at location ${location.name}: cell (${cell.cellNumber}) was not blocked, but item (${prevCell.item!!.entity.name}) in previous cell (${prevCell.cellNumber}) is continuing" }
                            continue
                        }
                    }
                }
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): processing waiting requests at location ${location.name}: cell (${cell.cellNumber}) was not blocked and there was no ride request positioned for entry" }
                val queue = accessQueues[location]!!
                if (queue.isNotEmpty) {
                    val request = queue.peekFirst()!!
                    dequeueRequest(request)
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : request = ${request.id} removed from queue = : ${queue.name}"}
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Request (${request.name}): status = ${request.status}: resuming entity_id = ${request.entity.id} at location ${location.name}" }
                    accessingHoldQ.removeAndResume(request.entity)
                } else {
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): processing waiting requests at location ${location.name}: no requests waiting" }
                }
            } else {
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): processing waiting requests: at location ${location.name}: cell (${cell.cellNumber}) was blocked or there was already a ride request waiting" }
            }
        }
    }

    /**
     *  If there is a traversal event pending, then it is cancelled. This will
     *  stop a scheduled movement of the conveyor.
     */
    private fun cancelConveyorMovement() {
        // if there is a traversal event pending then the conveyor is moving
        if (isCellTraversalInProgress()) {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > cancel cell traversal event scheduled for time = ${endCellTraversalEvent!!.time} : CONVEYOR (${this@Conveyor.name})"}
            // all motion on conveyor stops
            endCellTraversalEvent!!.cancel = true
            endCellTraversalEvent = null
        }
    }

    /**
     *  Depending on the type of conveyor, move the cells on the conveyor that can be moved
     */
    private fun moveCellsOnConveyor() {
        // called from endOfCellTraversal() event
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}) : moving cells on the conveyor...." }
        if (conveyorType == Type.NON_ACCUMULATING) {
            nonAccumulatingConveyorMovement()
        } else {
            //accumulatingConveyorMovement()
            accumulatingConveyorMovementViaSegments()
        }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}) : .... completed moving cells on the conveyor" }
    }

    /**
     *  Called from within the endOfCellTraversal() event
     */
    private fun nonAccumulatingConveyorMovement() {
        require(conveyorType == Type.NON_ACCUMULATING) { "The conveyor is not type non-accumulating" }
        if (!isOccupied() || hasBlockedCells()) {
            // nothing can move on a non-accumulating conveyor if it is blocked
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}):  Non-accumulating: not occupied or is blocked: nothing to move" }
            return
        }
        // conveyor is occupied and has no blocked cells
        val leadCell = findLeadingCell(conveyorCells)
        if (leadCell != null) {
            val trainEndCell = firstOccupiedCellFromStart()!!
            val movingCells = conveyorCells.subList(trainEndCell.index, leadCell.cellNumber)
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}):  Non-accumulating: has no blocked cells: moving cells within [${trainEndCell.cellNumber}..${leadCell.cellNumber}] forward" }
            moveItemsForwardOneCell(movingCells)
        } else {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}):  Non-accumulating: occupied with no blocked cells: no lead cell found!" }
        }
    }

    /**
     *  Items may be pre-positioned to enter the conveyor at entry cells. Cause the items
     *  to enter the conveyor by occupying the entry cell. The item will be in the entering state
     *  while it does not occupy all required cells.  Once it occupies all required cells, then
     *  the item is considered to on the conveyor.
     */
    private fun processItemsPositionedToEnter() {
        // make a copy of the waiting requests because they will be removed when fully on the conveyor
        val rideRequests = positionedToEnter.values.toList()
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Processing #(${rideRequests.size}) items positioned to ride on the conveyor..." }
        for (request in rideRequests) {
            // the request is off the conveyor and the entry cell is blocked for it to enter
            if (request.entryCell.isNotOccupied) {
                // make sure that the entry cell is not occupied
                request.enterConveyor()
            }
        }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): .... completed processing items positioned to ride on the conveyor." }
    }

    /**
     * Finds the first blocked cell from the supplied cell going
     * forward in the list. If there are no blocked cells, then
     * null is returned.
     */
    private fun firstBlockedCellForwardFromEntryCell(entryCell: Cell): Cell? {
        return conveyorCells.subList(entryCell.index + 1, conveyorCells.size).firstOrNull { it.isBlocked }
    }

    private fun rescheduleConveyorMovement() {
        if (conveyorType == Type.NON_ACCUMULATING) {
            rescheduleNonAccumulatingConveyorMovement()
        } else {
            rescheduleAccumulatingConveyorMovement()
        }
    }

    private fun rescheduleNonAccumulatingConveyorMovement() {
        val occupied = isOccupied()
        val blocked = hasBlockedCells()
        if (!occupied || blocked) {
            endCellTraversalEvent = null
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: occupied = $occupied, blocked = $blocked: no movement scheduled" }
            return
        }
        //is occupied and has no blocked cells, move the cells
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: occupied and no blocked cells: movement scheduled" }
        scheduleConveyorMovement()
    }

    private fun rescheduleAccumulatingConveyorMovement() {
        if (!isOccupied()) {
            // the conveyor is not occupied, but it could have items positioned to enter
            if (hasItemPositionedToEnter()) {
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: has at least one item positioned to enter: movement scheduled" }
                scheduleConveyorMovement()
                return
            }
            endCellTraversalEvent = null
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: not occupied and no entering items: no movement scheduled" }
            return
        }
        // conveyor must be occupied, find the leading cell
        val leadingCell = findLeadingCell(conveyorCells)
        if (leadingCell == null) {
            // no leading cell, thus no cells to move, may have items positioned to enter
            if (hasItemPositionedToEnter()) {
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: no movable items but has at least one item positioned to enter: movement scheduled" }
                scheduleConveyorMovement()
                return
            }
            endCellTraversalEvent = null
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: no movable items and no entering items: no movement scheduled" }
            return
        }
        // must have at least one movable cell so make it move
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: at least one movable cell: movement possible" }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: first movable cell = ${leadingCell.cellNumber}: item (${leadingCell.item?.entity?.name})" }
        scheduleConveyorMovement()
    }

    /**
     *  This function removes the blockage at the supplied cell and
     *  may cause the conveyor to start moving.  This method is called from:
     *
     *  1) Conveyor.BlockingEntry.exit() when an entity "exits" w/o riding from an entry cell
     *  2) Conveyor.itemFullyOffConveyor() during the movement cycle when an item moves through the exit cell (and off of the conveyor)
     *  3) Conveyor.startExitingProcess() when an entity indicates that it wants to exit (after riding) this
     *  allows the exiting cell to be unblocked during the movement, which allows following items to move into the exit cell
     *  during the move cycle
     *  4) Conveyor.BlockingExit.ride() when an entity continues to ride after reaching its destination
     */
    private fun removeBlockage(blockedCell: Cell) {
        blockedCell.isBlocked = false // this unblocks the cell
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): removed blockage at cell (${blockedCell.cellNumber}) : type (${blockedCell.type}) : location (${blockedCell.location?.name})" }
        // no need to worry about event scheduling because removing a blockage occurs
        // during the movement cycle, thus rescheduling at end of cycle will determine whether
        // the blockage affects the movement
    }

    private fun scheduleConveyorMovement() {
//        require(!isCellTraversalInProgress()){"r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR ($name): Tried to schedule a cell traversal when one was already pending! \n ${toString()}"}
        if (isCellTraversalInProgress()) {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): scheduleConveyorMovement(): cell traversal already pending, new traversal not scheduled" }
            return
        }
        endCellTraversalEvent = schedule(this::endOfCellTraversal, cellTravelTime, priority = defaultMovementPriority, name = "End of Cell Traversal")
        endCellTraversalEvent!!.name = "End of Cell Traversal"
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): scheduled event ( event_id = ${endCellTraversalEvent?.id}): the end of cell traversal for t = ${(time + cellTravelTime)}" }
    }

    internal fun enqueueRequest(request: ConveyorRequest) {
        accessQueues[request.entryLocation]!!.enqueue(request)
    }

    internal fun dequeueRequest(request: ConveyorRequest) {
        accessQueues[request.entryLocation]!!.remove(request)
    }

    /**
     *  The request has blocked an entry cell at an entry location.
     *  The entity has asked for the request to begin riding.
     *  The request will either be handled by an already scheduled cell traversal
     *  or it must initiate movement.
     */
    private fun beginRiding(request: ConveyorRequest) {
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : status = ${request.status}: begin riding..." }
        // the request has asked to start riding for the very first time
        if (!isCellTraversalInProgress()) {
            // no movement scheduled (pending), item arrived and is positioned to ride, its entry cell is not blocked
            if (conveyorType == Type.NON_ACCUMULATING) {
                if (!isOccupied()) {
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: conveyor not occupied: schedule conveyor movement" }
                    scheduleConveyorMovement()
                    return
                }
                // must be occupied to be here
                if (!hasMovableCell()) {
                    // no movable cells
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: conveyor has no movable cells" }
                    if (hasNoBlockedCells()) {
                        // no movable cells and conveyor is not blocked somewhere
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: conveyor is not blocked anywhere: schedule conveyor movement" }
                        scheduleConveyorMovement()
                        return
                    } else {
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: conveyor has blocked cells: no conveyor movement scheduled" }
                    }
                } else {
                    // has movable cells
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: has movable cells" }
                    if (hasNoBlockedCells()) {
                        // movable cells and no blocked cells
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: has no blocked cells" }
                        scheduleConveyorMovement()
                    } else {
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: has blocked cells" }
                    }
                 }
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Non-accumulating: begin riding: no conveyor movement scheduled" }
            } else {
                // cell traversal not in progress, accumulating conveyor, item is positioned to ride, needs movement scheduled
                if (!isOccupied()) {
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: begin riding: conveyor not occupied: schedule conveyor movement" }
                    scheduleConveyorMovement()
                    return
                }
                // must be occupied to be here
                if (!hasMovableCell()) {
                    // no movable cells
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: begin riding: conveyor has no movable cells: schedule conveyor movement" }
                    scheduleConveyorMovement()
                    return
                }
                // must be occupied and have movable cells to get here, with no cell traversal scheduled
                // do not schedule because movable cells will be noticed at end of cell traversal
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating: begin riding: conveyor occupied and has movable cells: no new movement scheduled" }
                return
            }
            return
        }
        // movement is pending, what to do? nothing
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): begin riding: cell traversal in progress case: don't schedule conveyor movement" }
    }

    /**
     *  This method is called from ProcessModel within the exitConveyor() method.
     *  The request must be blocking the exit in order to start the exiting process.
     *  Exiting requires that the item move completely through the exit cell. An item
     *  is off of the conveyor when it occupies no cells. That is, when its tail
     *  no longer occupies a cell. Exiting requires that the cells move forward to
     *  "push" the item off of the conveyor. The exit cell is unblocked during the
     *  exiting process.
     */
    internal fun startExitingProcess(request: ConveyorRequest) {
        //TODO need to investigate startExitingProcess()
        require(request.isBlockingExit) { "The request must be blocking the exit to start the exiting process" }
        // the request is blocking the exit, it must be in the BlockingExit state
        request.status = ItemStatus.EXITING
        val destination = request.destination!!
        val exitCell = exitCells[destination]!!
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : status = ${request.status}: starting the exit process: unblocked cell (${exitCell.cellNumber})" }
        // This only unblocks the cell, the request remains in the BlockingExit state
        // removing the blockage may allow movement forward on the conveyor
        removeBlockage(exitCell)
        if (conveyorType == Type.NON_ACCUMULATING) {
            rescheduleConveyorMovement()
        } else {
            if (!isCellTraversalInProgress()) {
                rescheduleConveyorMovement()
            }
        }
    }

    /**
     *  A conveyor request represents the holding of cells on a conveyor and acts as
     *  a "ticket" to use the conveyor.  Once an entity has a conveyor request, the entity
     *  has control over the cells at the start of the segment associated with the entry
     *  location along the conveyor.  After receiving a request to
     *  access the conveyor the entity can either ride on the conveyor or exit. The conveyor
     *  request blocks at the point of access until riding or exiting. The request is placed
     *  in the blocking entry state.  When the entity
     *  asks to ride the conveyor then the request will be placed in the riding state. If the entity
     *  never rides the conveyor, then the request stays in the blocking entry state.  The property isWaitingForEntry
     *  indicates that the conveyor request is waiting to be allowed to block the entry cell of the conveyor
     *  at its current location. Once the conveyor request is used to ride the conveyor, the isWaitingToConvey property will
     *  report false. The isBlockingEntry property will report true until the request begins
     *  riding.  Once the request reaches its destination, the isBlockingExit property will be true and the
     *  request is in the blocking exit state.  When the request exits the conveyor the isCompleted property is true
     *  and the request is in the completed state.  Once in the completed state, the request can no longer be used
     *  for any interaction with the conveyor.
     */
    inner class ConveyorRequest(
        override val entity: ProcessModel.Entity,
        override val numCellsNeeded: Int,
        override val entryLocation: IdentityIfc,
        override val accessResumePriority: Int
    ) : QObject(), ConveyorRequestIfc {
        init {
            require(numCellsNeeded >= 1) { "The number of cells requested must be >= 1" }
            require(numCellsNeeded <= maxEntityCellsAllowed) {
                "The entity requested more cells ($numCellsNeeded) than " +
                        "the allowed maximum ($maxEntityCellsAllowed) for for conveyor (${this@Conveyor.name})"
            }
            require(entryLocations.contains(entryLocation))
                { "The location (${entryLocation.name}) of requested cells is not on conveyor (${this@Conveyor.name})" }
            priority = entity.priority
        }

        internal var timeStartedTraversal: Double = 0.0

        override val conveyor = this@Conveyor

        override var status: ItemStatus = ItemStatus.OFF
            internal set

        val entryCell: Cell = entryCells[entryLocation]!!

        override var currentLocation: IdentityIfc = entryLocation
            internal set

        // destination should be set when asking to ride
        override var destination: IdentityIfc? = null
            internal set

        internal var state: RequestState = requestWaitingForEntryState

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

        internal fun blockEntryLocation() {
            state.blockEntryCell(this)
            entryCell.isBlocked = true
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : caused blockage for entry cell (${entryCell.cellNumber}) at location (${entryLocation.name})" }
        }

        internal fun startRiding() {
            // behavior depends on state (blocking entry or blocking exit)
            state.ride(this)
        }

        internal fun blockExitLocation() {
            state.blockExitCell(this)
        }

        internal fun exitConveyor() {
            state.exit(this)
        }

        private val myCellsOccupied: ArrayDeque<Cell> = ArrayDeque()

        override val occupiesCells: Boolean
            get() = myCellsOccupied.isNotEmpty()

        override val numCellsOccupied: Int
            get() = myCellsOccupied.size

        override val hasReachedAnExitCell: Boolean
            get() {
                if (frontCell == null) {
                    return false
                } else {
                    return frontCell!!.type == CellType.EXIT
                }
            }

        override val hasReachedDestination: Boolean
            get() = destination == currentLocation

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

        internal fun moveForwardOneCellV2(){
            require(frontCell != null) { "The item cannot move forward because it does not occupy any cells" }
            if (frontCell!!.isExitCell) {
                // item may be exiting or passing through cell
                if (status == ItemStatus.EXITING) {
                    // need to move the item forward, through its front cell, which is the exit cell
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: is exiting the conveyor from cell (${frontCell?.cellNumber}) at location (${frontCell?.location?.name})" }
                    // item is exiting, move it forward and check if it is off the conveyor
                    val exitCell = frontCell!! // captures the exit cell, because popping moves the item
                    popRearCell() // moves item forward, item no longer occupies its rear cell, it now covers its exit cell (or is off conveyor)
                    if (!occupiesCells) {
                        // no longer on the conveyor
                        status = ItemStatus.OFF
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: has moved off the conveyor" }
                        conveyor.itemFullyOffConveyor(this, exitCell)
                    }
                } else {
                    // item is not exiting, it must be passing through the exit cell onto the next segment, need to move it forward
                    // front cell is an exit cell, but item is not exiting, there needs to be a next cell
                    check(frontCell!!.nextCell != null) { "The item cannot move forward because it has reached the end of the conveyor" }
                    // there is a next cell, because the item's front cell is an exit cell, the next cell must be an entry cell
                    require(status == ItemStatus.ON) {"The item is not exiting at an exit cell and there is a next cell. The item must have the ON status"}
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: moved from cell (${frontCell?.cellNumber}) to cell (${frontCell?.nextCell?.cellNumber})" }
                    occupyCell(frontCell!!.nextCell!!) //this moves the item one cell forward
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: occupied cells: (${frontCell!!.cellNumber}..${rearCell!!.cellNumber})" }
                    // The items' front cell must be an entry cell, an entry cell cannot be a destination
                    // nothing further to check
                }
            } else {
                // the front cell is not an exit cell, the item must not be exiting
                require(status != ItemStatus.EXITING) {"CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : The item (${this.name}) cannot exit when its front cell ${frontCell?.cellNumber} is not an exit cell"}
                // if the front cell is not an exit cell, then it must either be an entry cell or an inner cell
                // in which case there MUST be a next cell
                check(frontCell!!.nextCell != null) { "The item cannot move forward because it has reached the end of the conveyor" }
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: moved from cell (${frontCell?.cellNumber}) to cell (${frontCell?.nextCell?.cellNumber})" }
                occupyCell(frontCell!!.nextCell!!) //this moves the item one cell forward
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: occupied cells: (${frontCell!!.cellNumber}..${rearCell!!.cellNumber})" }
                // item has moved forward, if entering, it may be fully on the conveyor
                if (status == ItemStatus.ENTERING) {
                    if (numCellsNeeded == numCellsOccupied) {
                        status = ItemStatus.ON
                        positionedToEnter.remove(entryCell)
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: is fully on the conveyor" }
                        // item is fully on the conveyor
                    }
                }
                // item has moved forward, it may have reached its destination
                if (frontCell!!.isExitCell) {
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: destination = (${destination?.name}): has reached exit cell (${frontCell!!.cellNumber}) at location (${frontCell!!.location?.name})" }
                    // reached an exit cell
                    if (destination == frontCell!!.location) {
                        // reached the intended destination
                        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: has reached cell (${frontCell?.cellNumber}) and its destination: (${destination?.name})" }
                        conveyor.itemReachedDestination(this)
                    }
                }
            }
        }

        /**
         *  Causes an item already on the conveyor to move through the cell that it occupies.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         *  We assume that moving forward has been triggered by a time delay that
         *  represents the item moving through the cell. This represents the completion
         *  of that movement.  At the end of this movement, the item is fully covering the cell that it
         *  occupies.  This function is called from within function moveItemsForwardThroughCells()
         *  which is called by functions within the endOfCellTraversal() event.
         */
        internal fun moveForwardOneCell() {
            //TODO maybe the conditions need to be reviewed, could item off the conveyor being missed
            require(frontCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the front cell cannot be null, safe to use
            // two cases, item is on conveyor or item is exiting the conveyor
            // in order to exit, the front cell must be an exit cell
            if ((status == ItemStatus.EXITING) && (frontCell!!.isExitCell)) {
                // need to move the item forward, through its front cell, which is the exit cell
                // item no longer occupies its rear cell
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: is exiting the conveyor from cell (${frontCell?.cellNumber}) at location (${frontCell?.location?.name})" }
                val exitCell = frontCell!! // captures the exit cell, because popping moves the item
                popRearCell() // moves item forward, item no longer occupies its rear cell, now cover exit cell (or is off conveyor)
                if (!occupiesCells) {
                    // no longer on the conveyor
                    status = ItemStatus.OFF
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: has moved off the conveyor" }
                    conveyor.itemFullyOffConveyor(this, exitCell)
                }
                return
            }
            // must not be exiting, which means the front cell must not be an exit cell
            // if the front cell is not an exit cell, then it must either be an entry cell or an inner cell
            // in which case there MUST be a next cell
            check(frontCell!!.nextCell != null) { "The item cannot move forward because it has reached the end of the conveyor" }
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: moved from cell (${frontCell?.cellNumber}) to cell (${frontCell?.nextCell?.cellNumber})" }
            occupyCell(frontCell!!.nextCell!!)
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: occupied cells: (${frontCell!!.cellNumber}..${rearCell!!.cellNumber})" }
            if (status == ItemStatus.ENTERING) {
                if (numCellsNeeded == numCellsOccupied) {
                    status = ItemStatus.ON
                    positionedToEnter.remove(entryCell)
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: is fully on the conveyor" }
                    // item is fully on the conveyor
                }
            }
            // item has moved forward, it may have reached its destination
            if (frontCell!!.isExitCell) {
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: destination = (${destination?.name}): has reached exit cell (${frontCell!!.cellNumber}) at location (${frontCell!!.location?.name})" }
                // reached an exit cell
                if (destination == frontCell!!.location) {
                    // reached the intended destination
                    ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: has reached cell (${frontCell?.cellNumber}) and its destination: (${destination?.name})" }
                    conveyor.itemReachedDestination(this)
                }
                // this should be the end of a segment
            }
        }

        /**
         *  Causes the item to occupy the supplied cell.  No checking of the contiguous nature of cells
         *  is performed.  The cell is added to the end of the cells occupied and if the number of
         *  cells needs is reached, the oldest cell is removed from the cells occupied.
         */
        private fun occupyCell(cell: Cell) {
            if (myCellsOccupied.size < numCellsNeeded) {
                myCellsOccupied.add(cell)
                cell.item = this
            } else {
                popRearCell() // remove from front of the list
                myCellsOccupied.add(cell)  // add new cell to the end of the list
                cell.item = this
            }
        }

        /**
         *  Causes the item to occupy the entry cell
         */
        internal fun enterConveyor() {
            check(status == ItemStatus.OFF) { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: Request status must be OFF to enter the conveyor for the first time" }
            check(entryCell.isNotOccupied) { "CONVEYOR: entity_id = ${entity.id} : Tried to enter the conveyor at cell (${entryCell.cellNumber}) and the cell was occupied by entity (${entryCell.item?.entity?.name}) \n ${this@Conveyor.toString()}" }
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: entering the conveyor at cell (${entryCell.cellNumber})" }
            occupyCell(entryCell)
            if (numCellsNeeded == numCellsOccupied) {
                // item is fully on the conveyor
                status = ItemStatus.ON
                // no longer getting on
                positionedToEnter.remove(entryCell)
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: is fully on the conveyor" }
            } else {
                status = ItemStatus.ENTERING
            }
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${entity.id} : status = $status: entered conveyor at location (${entryLocation.name}) occupied entry cell (${entryCell.cellNumber})" }
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

        internal fun mustWait(): Boolean {
            // study this: This appears to be causing requests not to wait when arriving and the entry cell is occupied
            // return entryCell.isBlocked || positionedToEnter.containsKey(entryCell)
            return entryCell.isUnavailable || positionedToEnter.containsKey(entryCell)
        }

    }

    /**
     * These states are defined within Conveyor so that they can be
     * shared across the many requests that are created. These state define
     * permitted behavior for requests rather that actual internal state.
     */
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

        open fun exit(request: ConveyorRequest) {
            errorMessage("complete()")
        }

        private fun errorMessage(routineName: String) {
            val sb = StringBuilder()
            sb.appendLine("Using $routineName : Tried to transition a cell request for $name to an illegal state from state $stateName")
            ProcessModel.logger.error { sb.toString() }
            throw ksl.utilities.exceptions.IllegalStateException(sb.toString())
        }
    }

    /**
     *  Represents a conveyor request that has been created and is waiting in
     *  an access queue to enter the conveyor. To transition out of the waiting
     *  state the request must block an entry cell and transition into the
     *  BlockingEntry state.
     */
    internal inner class WaitingForEntry : RequestState("Waiting") {
        override fun blockEntryCell(request: ConveyorRequest) {
            request.state = requestBlockingEntryState
        }
    }

    /**
     * From the BlockingEntry state a conveyor request may transition into
     * the RidingState by calling ride() or release the blockage by calling
     * exit(). ride() places the request in the RidingState and exit() places
     * the request in the Completed state.
     */
    internal inner class BlockingEntry : RequestState("BlockingEntry") {
        /**
         * The state becomes the Riding and the request is positioned to enter
         * the entry cell during the next movement cycle. The entry cell becomes
         * unblocked. The request may begin riding. This may initiate movement if it
         * is not already initiated.
         */
        override fun ride(request: ConveyorRequest) {
            // place the request as riding
            request.state = requestRidingState
            // remember that the request needs to move into the entry cell
            positionedToEnter[request.entryCell] = request
            request.entryCell.isBlocked = false //TODO why here?
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : status = ${request.status}: positioned for entry cell (${request.entryCell.cellNumber}): removed cell blockage" }
            beginRiding(request)
        }

        /**
         * Causes the request to transition into the Completed state.
         * The blockage at the entry cell is removed.
         */
        override fun exit(request: ConveyorRequest) {
            request.state = requestCompletedState
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): entity_id = ${request.entity.id} : status = ${request.status}:exiting w/o riding" }
            // need to remove the blockage at the entry cell
            removeBlockage(request.entryCell)
        }
    }

    /**
     *  Represents that the request is either positioned to enter during the next movement
     *  cycle or is already riding on the conveyor. In either case, the request is now
     *  using the conveyor. The use continues until it exits.  To exit, the request
     *  must transition to the BlockingExit state.  This occurs automatically when
     *  the request reaches its destination.
     */
    internal inner class Riding : RequestState("Riding") {
        override fun blockExitCell(request: ConveyorRequest) {
            request.state = requestBlockingExitState
        }
    }

    /**
     *  Represents when the request has reached the destination associated with its
     *  riding on the conveyor.  While in the BlockingExit state the request can only
     *  exit() or ride() to another destination
     */
    internal inner class BlockingExit : RequestState("BlockingExit") {
        /**
         * Transitions back to the Riding state and unblocks the exit. The request
         * does not exit the conveyor.
         */
        override fun ride(request: ConveyorRequest) {
            request.state = requestRidingState
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR ($name): entity_id = ${request.entity.id} : status = ${request.status}: continue riding" }
            // need to remove the blockage at the exit cell
            val exitCell = exitCells[request.currentLocation]!!
            removeBlockage(exitCell)
            // just stay on the conveyor
            // request to continue riding may not be within movement cycle and thus movement may need to be initiated
            // need to ensure movement if this request is the only item than can move
            if (!isCellTraversalInProgress()) {
                rescheduleConveyorMovement()
            }
        }

        /**
         * The request is transitioned to the Completed state.  It can
         * no longer use the conveyor.
         */
        override fun exit(request: ConveyorRequest) {
            request.state = requestCompletedState
        }
    }

    /**
     * When a request is in the Completed state it can no longer be used to interact
     * with the conveyor.
     */
    internal inner class Completed : RequestState("Completed")

    /**
     *  Called from ProcessModel via the requestConveyor() function.
     *  Creates the conveyor request that remembers the entity to allow the
     *  resumption of the suspension. The returned request is used as a "ticket"
     *  to ride the conveyor (like a resource allocation). The request is scheduled
     *  to arrive at the conveyor at the current simulated time using the request
     *  priority to order competing requests at different access points.
     */
    internal fun receiveEntity(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int,
        entryLocation: IdentityIfc,
        accessPriority: Int,
        accessResumePriority: Int
    ): ConveyorRequest {
        // create the request
        val request = ConveyorRequest(entity, numCellsNeeded, entryLocation, accessResumePriority)
        // schedule the access event with the request at the current time using the access priority
        // this scheduled event handles the priority of the competing request calls for the conveyor
        schedule(::receiveRequest, 0.0, message = request, priority = accessPriority)
        // return the created request
        return request
    }

    private fun receiveRequest(event: KSLEvent<ConveyorRequest>) {
        //TODO this resumes the entity waiting to access the conveyor
        val request = event.message!!
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = ${request.entity.id} : arrive for cells" }
        // a new request for cells has arrived at an entry point of the conveyor
        // always enter the queue to get statistics on waiting to enter the conveyor
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : enqueue request_id = ${request.id} at ${request.entryLocation.name}" }
        enqueueRequest(request)
        // check if the request does not have to wait
        if (!request.mustWait()) {
            // remove the request from the queue and start it processing
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : dequeue request_id = ${request.id} at ${request.entryLocation.name}" }
            dequeueRequest(request)
            accessingHoldQ.removeAndResume(request.entity)
        } else {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... executing event : event_id = ${event.id} : entity_id = ${request.entity.id} : must wait for cells..." }
        }
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = ${request.entity.id} : arrival for cells " }
    }

    //TODO scheduleConveyAction(): This seems to be a problem
    internal fun scheduleConveyAction(
        conveyorRequest: ConveyorRequest,
        destination: IdentityIfc,
        ridePriority: Int
    ) {
        conveyorRequest.destination = destination
        // need to check if a cell traversal is in progress if so, don't make the request until after it ends
        if (isCellTraversalInProgress()) {
            // handle cell traversal in progress differently by type of conveyor
            if (conveyorType == Type.NON_ACCUMULATING) {
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > : entity_id = ${conveyorRequest.entity.id} : cell traversal in progress : arrival of ride request"}
                // item has asked to ride non-accumulating conveyor and will block the cell
                // cancel the current traversal
                cancelConveyorMovement()
                // schedule the need for conveyance at the current time, this will cause the blockage
                schedule(::startRideAction, 0.0, message = conveyorRequest, priority = ridePriority)
            } else {
                // cell traversal is in progress, must wait to end of current movement before engaging conveyor for ride
                // determine time of end of cell traversal
                val timeUntilEndOfTraversal = endCellTraversalEvent!!.timeRemaining
                schedule(::startRideAction, timeUntilEndOfTraversal, message = conveyorRequest, priority = ridePriority)
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > : entity_id = ${conveyorRequest.entity.id} : cell traversal in progress : scheduled start of ride for time = ${time+ timeUntilEndOfTraversal}"}
            }
         } else {
            // cell traversal is not in progress
            // schedule the need for conveyance at the current time
            schedule(::startRideAction, 0.0, message = conveyorRequest, priority = ridePriority)
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > : entity_id = ${conveyorRequest.entity.id} : cell traversal NOT in progress : scheduled start of ride for time = ${time}"}
        }
    }

    private fun startRideAction(event: KSLEvent<ConveyorRequest>) {
        val request = event.message!!
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = ${request.entity.id} : start ride action" }
        request.startRiding()
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = ${request.entity.id} : start ride action" }
    }

    internal fun scheduleExitAction(
        conveyorRequest: ConveyorRequest,
        exitPriority: Int
    ) {
        if (conveyorRequest.isBlockingEntry) {
            // the request cannot be riding or completed, if just blocking the entry, it must just complete
            val entity = conveyorRequest.entity
            conveyorRequest.exitConveyor()
            logger.trace { "r = ${model.currentReplicationNumber} : $time > PROCESS: exitConveyor(): Entity (${entity.name}) released blockage at entry location ${conveyorRequest.currentLocation.name} for (${this.name})" }
        } else {
            // schedule the need for exit at the current time
            schedule(::startExitAction, 0.0, message = conveyorRequest, priority = exitPriority)
        }
    }

    private fun startExitAction(event: KSLEvent<ConveyorRequest>) {
        val request = event.message!!
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** EXECUTING ... : event_id = ${event.id} : entity_id = ${request.entity.id} : start exit action" }
        startExitingProcess(request) //TODO
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > EVENT : *** COMPLETED! : event_id = ${event.id} : entity_id = ${request.entity.id} : start exit action" }
    }

    inner class CSegment(
        val number: Int,
        val entryCell: Cell,
        val exitCell: Cell
    ) {
        init {
            require(number > 0) { "The segment number must be > 0" }
            require(entryCell.isEntryCell) { "The starting cell of the segment was not an entry cell" }
            require(exitCell.isExitCell) { "The ending cell of the segment was not an exit cell" }
        }

        val cells = conveyorCells.subList(entryCell.index, exitCell.cellNumber)

        init {
            for (cell in cells){
                cell.cSegment = this
            }
        }

        val numOccupiedCells: Int
            get() {
                var sum = 0
                for(cell in cells){
                    if (cell.isOccupied){
                        sum++
                    }
                }
                return sum
            }

        internal val myNumOccupiedCells = TWResponse(this@Conveyor, "${this@Conveyor.name}:${this.name}:NumOccupiedCells", allowedDomain = Interval(0.0, cells.size.toDouble()))
        val numberOfOccupiedCells: TWResponseCIfc
            get() = myNumOccupiedCells

        private val myCellUtilization = Response(this@Conveyor, "${this@Conveyor.name}:${this.name}:CellUtilization")

        val cellUtilization: ResponseCIfc
            get() = myCellUtilization

//        internal val myTraversalTime = Response(this@Conveyor,"${this@Conveyor.name}:${this.name}:TraversalTime")
//        val traversalTime: ResponseCIfc
//            get() = myTraversalTime

        internal fun replicationEnded(){
            myCellUtilization.value = myNumOccupiedCells.withinReplicationStatistic.weightedAverage / cells.size
        }

        val isLastSegment: Boolean
            get() = conveyorCells.last() == exitCell

        val isFirstSegment: Boolean
            get() = conveyorCells.first() == entryCell

        val entryLocation: IdentityIfc
            get() = entryCell.location!!

        val exitLocation: IdentityIfc
            get() = exitCell.location!!

        val firstOccupiedCell: Cell?
            get() = cells.firstOrNull { it.isOccupied }

        val isOccupied: Boolean
            get() = firstOccupiedCell != null

        val isNotOccupied: Boolean
            get() = !isOccupied

        val leadingCell: Cell?
            get() = findLeadingCell(cells)

        val name: String
            get() = "${entryLocation.name}->${exitLocation.name}"

        fun movableCells(): List<Cell> {
            val foc = firstOccupiedCell ?: return emptyList()
            val leader = leadingCell ?: return emptyList()
            // the foc could be the last cell of the list
            // the leader cell must be occupied, and it cannot be blocked
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR.CSegment($name): foc cell # = ${foc.cellNumber}, leader cell # = ${leader.cellNumber}" }
            //ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > CONVEYOR.CSegment: foc cell index = ${foc.index}, leader cell index = ${leader.index}"}
            return conveyorCells.subList(foc.index, leader.cellNumber)
        }

        internal fun moveCellsForward() {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR.CSegment($name): moving cells forward" }
            val mc = movableCells()
            moveItemsForwardOneCell(mc)
        }

        override fun toString(): String {
            return "Locations[$name] = Cells[${entryCell.cellNumber}..${exitCell.cellNumber}]"
        }

    }

    private fun accumulatingConveyorMovementViaSegments() {
        require(conveyorType == Type.ACCUMULATING) { "The conveyor is not type accumulating" }
        if (!isOccupied()) {
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}):  Accumulating conveyor: not occupied: nothing to move" }
            return
        }
        if (hasNoBlockedCells()) {
            // with no blocked cells, there may be items entering that prevent movement
            // there must be a lead cell to move if the conveyor has items and there are no blocked cells
            val leadCell = findLeadingCell(conveyorCells)
            if (leadCell != null) {
                // get the cells to move
                // from the beginning up to and including the lead cell
                val trainEndCell = firstOccupiedCellFromStart()!!
                val movingCells = conveyorCells.subList(trainEndCell.index, leadCell.cellNumber)
                ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating conveyor: has no blocked cells: moving cells within [${trainEndCell.cellNumber}..${leadCell.cellNumber}] forward" }
                moveItemsForwardOneCell(movingCells)
            }
        } else {
            // there are blockages, try to move items that can be moved
            if (isCircular) {
                val last = conveyorCells.last()
                if (last.isOccupied && last.isNotBlocked) {
                    if (last.item!!.status != ItemStatus.EXITING) {
                        // continuing around, movement is special
                        handleAccumulatingCircularConveyorCase()
                        return
                    }
                }
            }
            // could be circular or not, if circular it does not have an item continuing around
            // move items by segment
            ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating conveyor: has blocked cells: moving cells by segments forward" }
            for (segment in segments.reversed()) {
                segment.moveCellsForward() //TODO need some output
            }
        }
    }

    private fun handleAccumulatingCircularConveyorCase() {
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > ... event executing : CONVEYOR (${this@Conveyor.name}): Accumulating conveyor: has blocked cells: item in last cell is re-circulating" }
        val ms = segments.firstOrNull { (it.leadingCell != null) }
        if (ms != null) {
            // a segment exists that has a movable item
            if (ms != segments.last()) {
                // the last segment is not the first movable segment
                val (f, s) = segments.partition { it.number <= ms.number }
                val rf = f.asReversed()
                val rs = s.asReversed()
                val n = rf + rs
                // move all segments forward starting with the one that could move
                for (segment in n) {
                    segment.moveCellsForward()
                }
            } else {
                // the last segment is the first movable segment
                // the exit cell must be the movable item if we get here
                // we can move the cells forward in the last segment if and only if
                // the entry cell of the first segment is available and nothing is positioned to enter
                val entryCell = segments.first().entryCell
                if (entryCell.isAvailable && positionedToEnter[entryCell] == null) {
                    // go through all segments because movement in last segment could allow
                    // movement in following segment
                    for (segment in segments.reversed()) {
                        segment.moveCellsForward()
                    }
                }
            }
            //TODO("Checking if we got here")
        }
    }
}



