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
        conveyorCells = cells.asList()
    }

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

    internal fun items() : List<Item> {
        val list = mutableSetOf<Item>()
        for(cell in conveyorCells.reversed()){
            val item = cell.item
            if (item != null){
                list.add(item)
            }
        }
        return list.asList()
    }

    override fun initialize() {
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
    fun canAllocateCells(entryLocation: IdentityIfc, numCellsNeeded: Int = 1): Boolean {
        require(entryLocations.contains(entryLocation)) { "The location ($entryLocation) is not a valid entry point on the conveyor" }
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name}"
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
        while (itr.hasNext()){
            if (itr.next().isAvailable){
                sum++
            } else {
                return sum
            }
        }
        return sum
    }

    fun numAvailableCells(entryLocation: IdentityIfc) : Int {
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
                        "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name})"
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

        internal fun deallocate() {
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
        cellAllocation: CellAllocation,
        desiredLocation: IdentityIfc
    ) : QObject(), ConveyorItemIfc {
        //TODO review and remove unneeded properties
        override var status: ItemStatus = ItemStatus.OFF
            internal set

        override val entity: ProcessModel.Entity = cellAllocation.entity
        override val numberOfCells: Int = cellAllocation.numberOfCells
        override val origin: IdentityIfc = cellAllocation.entryLocation
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
         *  Causes an item already on the conveyor to move forward by one cell.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         *  We assume that moving forward has been triggered by a time delay that
         *  represents the item moving through the cell. This represents the completion
         *  of that movement.
         */
        internal fun moveForwardOneCell() {
            require(isConveyable) { "The item cannot move forward because it has no allocated cells" }
            require(firstCell != null) { "The item cannot move forward because it does not occupy any cells" }//TODO problem here
            // the front cell cannot be null, safe to use
            require(firstCell!!.isNotLast) { "The item cannot move forward because it has reached the end of the segment" }
            // the front cell is not the last cell of the segment
            // this means that there must be a next cell
            // each occupied cell becomes the next occupied cell
            occupyCell(firstCell!!.nextCell!!)
            // all cells acquired and last cell is the first cell of the segment, then it completed loading
            if ((myCellsOccupied.size == numberOfCells)) {
                if (lastCell!!.type == CellType.ENTRY){
                    // item is now fully on the segment, notify segment
                    status = ItemStatus.ON
                    //TODO notify the conveyor??
                }
            }
            //TODO how to tell if entering (loading)?, exiting (unloading)?, off?
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


    /**
     *  This method is called by an internal segment when the segment has to
     *  block due to an item entering at an entry location.
     */
    private fun segmentBlockedOnEntry() {
        // an individual segment of the conveyor is blocked at its entry point
        // handle blocking based on type of conveyor
        TODO("Not yet implemented: Conveyor.segmentBlockedOnEntry()")
    }

    private fun scheduleMovement() {
        TODO("Not yet implemented: Conveyor.scheduleMovement()")
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

        var item: Item? = null
            internal set

        val cellNumber: Int = cellList.size

        val isFirst: Boolean
            get() = cellList.first() == this

        val isLast: Boolean
            get() = cellList.last() == this

        val isNotLast: Boolean
            get() = !isLast

        /**
         *  The cell in front of this cell (towards the end of the segment)
         */
        val nextCell: Cell?
            get() {
                return if (isLast) null
                else
                    cellList[cellNumber] // because cells are numbered starting at 1, but list is 0 index based
            }

        /**
         *  The cell immediately behind this cell (towards the front of the segment)
         */
        val previousCell: Cell?
            get() {
                return if (isFirst) null
                else
                    cellList[cellNumber - 1] // because cells are numbered starting at 1, but list is 0 index based
            }

        /**
         *  A cell is occupied if it is covered by an item
         */
        val isOccupied: Boolean
            get() = item != null

        var isBlocked: Boolean = false
            internal set

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

    internal fun conveyItem(cellAllocation: Conveyor.CellAllocation, destination: IdentityIfc) {
        TODO("Conveyor.conveyItem() not implemented yet")
    }

    internal fun deallocateCells(cellAllocation: CellAllocationIfc) {
        TODO("Conveyor.deallocateCells() not implemented yet")
    }

    internal fun startExitingProcess(cellAllocation: CellAllocationIfc) {
        TODO("Conveyor.startExitingProcess() not implemented yet")
    }

    internal fun allocateCells(request: Conveyor.CellRequest): CellAllocationIfc {
        TODO("Conveyor.allocateCells() not implemented yet")
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


//if (conveyorType == Type.NON_ACCUMULATING){
//    // all movement on the conveyor must have been stopped
//    // need to start or restart movement!!
//
//    val leadItem = findLeadItem()
//    if (leadItem != null) {
//        cellTraversalEvent = endCellTraversalAction.schedule(cellTravelTime, leadItem)
//    }
//} else {
//    // items on the segment continued to move forward during blockage
//
//}
////
//// can the segment be moving?  can there be events pending
//val leadItem = findLeadItem()
//if (leadItem != null) {
//    cellTraversalEvent = endCellTraversalAction.schedule(cellTravelTime, leadItem)
//}

/**
 *  Causes the item to exit the conveyor at its destination.  Exiting the conveyor
 *  causes the item to travel through the final cells that it occupies. Thus,
 *  this call will take simulated time.  The process (entity) calling this should be
 *  suspended until the exiting time is completed.
 */
//internal fun exitConveyor(item: Conveyor.Item) {
//    require(item.conveyor == this) { "Item is not from this conveyor" }
//    require(item.segment != null) { "The item was not using a segment" }
//    // conveyable means that the item has its required allocated cells
//    require(item.isConveyable) { "Tried to exit a conveyor for an item that is not conveyable" }
//    // since it is conveyable, it has at least 1 allocated cell
//    // handle the case of when item exits without occupying any cells, there will not be any time delay
//    if (!item.occupiesCells) {
//        // item does not occupy any cells, exiting without being on segment
//        // just deallocate the cells
//        item.segment!!.deallocateCells(item)
//    } else {
//
//    }
//
//    // make sure that the item occupies cells
//    require(item.occupiesCells) { "The exiting item does not occupy any cells on the conveyor" }
//    // has allocated cells and is occupying cells on the conveyor
//    // need to make sure that the item's current location matches the exit location
//    require(item.destination != null) { "The item had no destination set" }
//    require(exitLocations.contains(item.destination)) { "The destination is not on this conveyor" }
//    // delegate to the segment
//    // this will schedule an event to start the exiting process
//    item.segment!!.scheduleConveyorExit(item)
//}