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
import ksl.modeling.variable.TWResponse
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
data class SegmentData(val entryLocation: IdentityIfc, val exitLocation: IdentityIfc, val length: Int) {
    init {
        require(entryLocation != exitLocation) { "The start and the end of the segment must be different!" }
        require(length >= 1) { "The length of the segment must be >= 1 unit" }
    }

    override fun toString(): String {
        return "(start = ${entryLocation.name} --> end = ${exitLocation.name} : length = $length)"
    }

}

/**
 * This class represents the data associated with segments of a conveyor and facilitates
 * the specification of segments for a conveyor.  See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
class SegmentsData(val cellSize: Int = 1, val firstLocation: IdentityIfc) {
    private val mySegments = mutableListOf<SegmentData>()
    private val myDownStreamLocations: MutableMap<IdentityIfc, MutableList<IdentityIfc>> = mutableMapOf()

    var lastLocation: IdentityIfc = firstLocation
        private set
    var minimumSegmentLength = Integer.MAX_VALUE
        private set
    val segments: List<SegmentData>
        get() = mySegments

    fun toLocation(next: IdentityIfc, length: Int) {
        require(length >= 1) { "The length ($length) of the segment must be >= 1 unit" }
        require(next != lastLocation) { "The next location (${next.name}) as the last location (${lastLocation.name})" }
        require(length % cellSize == 0) { "The length of the segment ($length) was not an integer multiple of the cell size ($cellSize)" }
        val sd = SegmentData(lastLocation, next, length)
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
    val isWaitingToConvey: Boolean
    val creationTime: Double
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
 *  A conveyor item represents something that occupies cells on a segment
 *  of a conveyor.  A conveyor item remembers its entry point (origin) on the
 *  conveyor and tracks its current location on the conveyor.  While moving
 *  on the conveyor the item has no current location. The current location is
 *  set when the item arrives at the end of a segment of the conveyor. The planned
 *  location represents the next exit location along the conveyor that the item is
 *  moving towards.
 */
interface ConveyorItemIfc {
    //TODO review and remove unneeded properties
    /**
     * The time that the item first occupied a cell on the conveyor
     */
    val createTime: Double

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
     * The number of cells allocated from the conveyor to the entity
     */
    val numCellsAllocated: Int

    /**
     *  The number of cells currently occupied on the conveyor.
     *  This may be different from numCellsAllocated because the
     *  allocation comes before actually occupying space on the conveyor
     */
    val numCellsOccupied: Int

    val occupiesCells: Boolean

    /**
     *  True if the number of cells allocated is equal to the
     *  number of cells needed.
     */
    val isConveyable: Boolean

    /**
     *  The conveyor the enitty is using
     */
    val conveyor: Conveyor

    /**
     *  The segment that the entity is currently using. May be
     *  null because the segment is not known until cells are allocated
     */
    val segment: Conveyor.Segment?

    /**
     * The object riding on the conveyor has a front (facing towards) its destination
     * and an end (facing where it originated). This cell is the furthermost cell
     * occupied towards the front (in the direction of travel)
     */
    val firstCell: Conveyor.Segment.Cell?

    /**
     *  This cell is the furthermost cell occupied by the object towards the where
     *  it originated.
     */
    val lastCell: Conveyor.Segment.Cell?

    /**
     *  True if the item has reached the last cell of the current segment
     */
    val hasReachedEndOfSegment: Boolean

    /**
     *  Can be used when the entity is resumed
     */
    val resumePriority: Int

    /**
     *  While riding this is the location where the entity is heading
     */
    val plannedLocation: IdentityIfc?
        get() {
            return if (segment == null) {
                null
            } else {
                segment!!.exitLocation
            }
        }

    /**
     *  True if the entity has reached its destination
     */
    val hasReachedDestination: Boolean
}

//TODO accumulating conveyors allow the item after the leading item to continue moving if the leading item is blocked
//TODO what happens if lead item is blocked


/** A conveyor consists of a series of segments. A segment has a starting location (origin) and an ending location
 * (destination) and is associated with a conveyor. The start and end of each segment represent locations along
 * the conveyor where entities can get on and off.  A conveyor has a cell size, which represents the length of
 * each cell on any segment of the conveyor. A conveyor also has a maximum permitted number of cells that can be
 * occupied by an item riding on the conveyor.  A conveyor has a [initialVelocity].  Each segment moves at the same
 * velocity.  Each segment has a specified length that is divided into a number of equally sized contiguous cells.
 * The length of any segment must be an integer multiple of the conveyor's cell size so that each segment will have
 * an integer number of cells to represent its length. If a segment consists of 12 cells and the length of the conveyor
 * is 12 feet then each cell represents 1 foot. If a segment consists of 4 cells and the length of the segment is 12 feet,
 * then each cell of the segment represents 3 feet.  Thus, a cell represents a generalized unit of distance along the segment.
 * Segments may have a different number of cells because they may have different lengths.
 *
 * The cells on a segment are numbered from 1 to n, with 1 at the origin, and n at the destination where n is the number
 * of cells on the segment.
 *
 * Items that ride on the segment must be allocated cells and then occupy the cells while moving on the segment.  Items
 * can occupy more than one cell while riding on the conveyor.  For example, if the segment has 5 cells (1, 2, 3, 4, 5) and
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
 * A conveyor is considered circular if the start of the first segment is the same as the end of the last segment.
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
    segmentData: SegmentsData,
    val conveyorType: Type = Type.ACCUMULATING,
    velocity: Double = 1.0,
    val maxEntityCellsAllowed: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {

    enum class Type {
        ACCUMULATING, NON_ACCUMULATING
    }

    init {
        require(velocity > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
    }

    private val mySegmentMap = mutableMapOf<IdentityIfc, Segment>()
    private val mySegmentList = mutableListOf<Segment>()
    private val mySegmentData: SegmentsData

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

    var initialVelocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
            field = value
        }

    val cellSize: Int

    init {
        require(maxEntityCellsAllowed >= 1) { "The maximum number of cells that can be occupied by an entity must be >= 1" }
        require(segmentData.isNotEmpty()) { "The segment data must not be empty." }
        mySegmentData = segmentData
        cellSize = mySegmentData.cellSize
        for ((i, seg) in mySegmentData.segments.withIndex()) {
            val segment = Segment(seg, "${this.name}:Seg:$i")
            mySegmentMap[seg.entryLocation] = segment
            mySegmentList.add(segment)
        }
        conveyorHoldQ.waitTimeStatOption = false
        conveyorHoldQ.defaultReportingOption = false
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
    val isCircular = mySegmentData.isCircular

    /**
     *  The locations that can be used to enter (get on) the conveyor.
     */
    val entryLocations = mySegmentData.entryLocations

    /**
     *  The locations that can be used as points of exit on the conveyor.
     */
    val exitLocations = mySegmentData.exitLocations

    /**
     *  Determines if the [end] location is reachable from the [start] location.
     *  A location is reachable if the item can ride on the conveyor from
     *  the start location to the end location without exiting the conveyor.
     */
    fun isReachable(start: IdentityIfc, end: IdentityIfc): Boolean {
        return mySegmentData.isReachable(start, end)
    }

    /**
     *  Determines the lead item on the segment that starts at the supplied location.
     *  The lead item is the item that is the furthest forward that is not blocked.
     *  An item is not blocked if the cell in front of it exists and is not occupied.
     */
    fun findLeadItem(entryLocation: IdentityIfc): ConveyorItemIfc? {
        require(entryLocations.contains(entryLocation)) { "The location is not on the conveyor" }
        return mySegmentMap[entryLocation]?.findLeadItem()
    }

    override fun initialize() {
        velocity = initialVelocity
    }

    /**
     *  Checks if the number of cells needed [numCellsNeeded] can be
     *  allocated at the supplied [location] associated with the conveyor.
     *  True means that the amount of cells needed at the conveyor entry location be allocated at this
     *  instant in time.
     */
    fun canAllocateCells(location: IdentityIfc, numCellsNeeded: Int = 1): Boolean {
        require(entryLocations.contains(location)) { "The origin ($location) is not a valid entry point on the conveyor" }
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name}"
        }
        val segment = mySegmentMap[location]!!
        return segment.canAllocate(numCellsNeeded)
    }

    /**
     * It is an error to attempt to allocate cells if there are insufficient
     * cells available. Thus, the number of cells needed must be less than or equal to the number of cells
     * available at the origin point at the time of this call. This function
     * should only be called from the access() suspend function.
     *
     * @param request the access request that wants the cells
     */
    internal fun allocateCells(request: CellRequest): CellAllocation {
        require(request.conveyor == this) { "The cell allocation request is not from this conveyor" }
        require(request.isFillable) { "The cell request for (${request.numCellsNeeded}) cells cannot be filled at this time" }
        // get the segment for entry onto the conveyor
        val segment = mySegmentMap[request.entryLocation]!!
        // ask the segment to allocate cells to the entity and make the allocation
        // need to assign entity to have allocation, need to cause blocking
        return segment.allocateCells(request)
    }

    /**
     * The allocation is either on the conveyor or it is waiting to get on the conveyor.
     * The behavior is  delegated to the appropriate segment.
     * Conveying an item may cause events to be scheduled to move the lead item forward.
     * The entity associated with the item should be suspended, placed in the
     * conveyor's HoldQ, after this call from the ride() suspend function of the process. The item
     * will remain on the conveyor until the entity indicates that the cells are to be released by using
     * the exit function. The behavior of the conveyor during the ride and when the item reaches its
     * destination is governed by the type of conveyor. A blockage occurs at the destination location of the segment
     * while the entity occupies the final cells before exiting or riding again.
     */
    internal fun conveyItem(cellAllocation: CellAllocation, destination: IdentityIfc) {
        // cell allocation is already checked to be allocated
        // destination has already been checked to be valid
        // get the segment associated with the allocation
        val segment = mySegmentMap[cellAllocation.entryLocation]!!
        // delegate the work to the segment
        // the entity associated with the item should be suspended, after this call
        segment.conveyItem(cellAllocation, destination)
    }

    /**
     * This function should deallocate the cells associated with the cell allocation
     * and cause any blockage associated with the allocation to be removed. This
     * function should be called only from the exit() function of the entity process.
     * There should not be any time delay associated with this function, but it may cause
     * events to be scheduled and processes to be resumed as the allocation is released.
     * The work is delegated to the segment associated with the cell allocation.
     */
    internal fun deallocateCells(cellAllocation: CellAllocationIfc) {
        // cell allocation is already checked to be valid
        // get the segment associated with the allocation
        val segment = mySegmentMap[cellAllocation.entryLocation]!!
        segment.deallocateCells(cellAllocation)
    }

    /**
     * This function should start the exiting process for the entity holding
     * the cell allocation.  The cells associated with the cell allocation should be deallocated
     * and any blockage associated with the allocation to be removed. This
     * function should be called only from the exit() function of the entity process.
     * There may be time delay associated with this function. It may cause
     * events to be scheduled and processes to be resumed as the allocation is released.
     * The work is delegated to the segment associated with the cell allocation.
     */
    internal fun startExitingProcess(cellAllocation: CellAllocationIfc) {
        // cell allocation is already checked to be valid
        // get the segment associated with the allocation
        val segment = mySegmentMap[cellAllocation.entryLocation]!!
        segment.startExitingProcess(cellAllocation)
    }


    fun startConveyor() {
        for (segment in mySegmentList) {
            segment.startMovement()
        }
    }

    fun stopConveyor() {
        for (segment in mySegmentList) {
            segment.stopMovement()
        }
    }

    /**
     *  Represents a request for space on a conveyor by an entity.
     *  The supplied request priority is related to the queue priority for the request.
     *
     *  @param entity the entity making the request
     *  @param numCellsNeeded the number of cells being requested
     *  @param requestPriority the priority associated with waiting in the queue for the cells
     */
    inner class CellRequest(
        val entity: ProcessModel.Entity,
        val numCellsNeeded: Int = 1,
        val entryLocation: IdentityIfc,
    ) : QObject() {
        var segment: Conveyor.Segment
            internal set

        init {
            require(numCellsNeeded >= 1) { "The number of cells requested must be >= 1" }
            require(numCellsNeeded <= maxEntityCellsAllowed) {
                "The entity requested more cells ($numCellsNeeded) than " +
                        "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name})"
            }
            require(entryLocations.contains(entryLocation)) { "The location (${entryLocation.name}) of requested cells is not on conveyor (${conveyor.name})" }
            priority = entity.priority
            segment = mySegmentMap[entryLocation]!!
        }

        val isFillable: Boolean
            get() = segment.canAllocate(numCellsNeeded)

        val isNotFillable: Boolean
            get() = !isFillable

        val conveyor = this@Conveyor
    }

    internal fun createRequest(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int = 1,
        entryLocation: IdentityIfc,
    ): CellRequest {
        return CellRequest(entity, numCellsNeeded, entryLocation)
    }

    internal fun enqueueRequest(request: CellRequest) {
        request.segment.enqueueRequest(request)
    }

    internal fun dequeueRequest(request: CellRequest) {
        request.segment.dequeueRequest(request)
    }

    inner class CellAllocation(
        override val entity: ProcessModel.Entity,
        theAmount: Int = 1,
        override val entryLocation: IdentityIfc
    ) : CellAllocationIfc {
        init {
            require(theAmount >= 1) { "The number of cells allocated must be >= 1" }
        }

        override val creationTime: Double = time

        override var item: Item? = null
            internal set

        override val conveyor = this@Conveyor
        override var numberOfCells = theAmount
            private set

        override var isWaitingToConvey = false
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

        override var segment: Conveyor.Segment? = null
            internal set

        override var resumePriority: Int = KSLEvent.DEFAULT_PRIORITY
            internal set //TODO when to allow changes

        override var currentLocation: IdentityIfc? = null
            internal set

        private val myCellsOccupied: ArrayDeque<Segment.Cell> = ArrayDeque()

        override val occupiesCells: Boolean
            get() = myCellsOccupied.isNotEmpty()

        override var numCellsAllocated: Int = 0 //TODO where is this reduced, tie into statistics??
            internal set

        override val numCellsOccupied: Int
            get() = myCellsOccupied.size

        override val isConveyable: Boolean
            get() = numCellsAllocated == numberOfCells

        override val conveyor = this@Conveyor

        override val hasReachedEndOfSegment: Boolean
            get() {
                return if (segment == null) {
                    false
                } else if (firstCell != null) {
                    firstCell == segment!!.exitCell
                } else {
                    false
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
        override val firstCell: Segment.Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.last() else null

        /**
         *  The cell that is occupied by the item that is closest to the origin of the segment
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor. If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then its last cell is 2.
         */
        override val lastCell: Segment.Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.first() else null

        /**
         *  Causes an item already on the segment to move forward by one cell.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         *  We assume that moving forward has been triggered by a time delay that
         *  represent the item moving through the cell. This represents the completion
         *  of that movement.
         */
        internal fun moveForwardOneCell() {
            require(isConveyable) { "The item cannot move forward because it has no allocated cells" }
            require(firstCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the front cell cannot be null, safe to use
            require(firstCell!!.isNotLast) { "The item cannot move forward because it has reached the end of the segment" }
            // the front cell is not the last cell of the segment
            // this means that there must be a next cell
            // each occupied cell becomes the next occupied cell
            occupyCell(firstCell!!.nextCell!!)
            if (segment != null) {
                // all cells acquired and last cell is the first cell of the segment, then it completed loading
                if ((lastCell!! == segment!!.entryCell) && (myCellsOccupied.size == numberOfCells)) {
                    // item is now fully on the segment, notify segment
                    segment!!.itemFullyLoaded(this)
                    status = ItemStatus.ON
                }
                //TODO how to tell if entering (loading)?, exiting (unloading)?, off?
            }
        }

        /**
         *  Causes the item to occupy the supplied cell.  No checking of the contiguous nature of cells
         *  is performed.  The cell is added to the end of the cells occupied and if the number of
         *  cells needs is reached, the oldest cell is removed from the cells occupied.
         */
        internal fun occupyCell(cell: Segment.Cell) {
            if (myCellsOccupied.size < numberOfCells) {
                myCellsOccupied.add(cell)
                cell.occupyingItem = this
            } else {
                popOldest() // remove from front of the list
                myCellsOccupied.add(cell)  // add new cell to the end of the list
                cell.occupyingItem = this
            }
        }

        /**
         *  Removes the cell that is oldest from the occupied cells. The cell that is
         *  closest to the origin is removed.
         */
        private fun popOldest(): Boolean {
            return if (myCellsOccupied.isNotEmpty()) {
                val first = myCellsOccupied.removeFirst()
                first.occupyingItem = null
                true
            } else {
                false
            }
        }

    }

    enum class SegmentStatus {
        MOVING, BLOCKED_ENTERING, BLOCKED_EXITING, IDLE, UNBLOCKED_ENTERING
    }

    /**
     * A segment consists of a number of equally sized contiguous cells with each cell representing some unit of distance along
     * the segment.  If a segment consists of 12 cells and the length of the conveyor is 12 feet then each cell
     * represents 1 foot. If a segment consists of 4 cells and the length of the conveyor is 12 feet, then each
     * cell represents 3 feet.  Thus, a cell represents a generalized unit of distance along the segment. Items that
     * ride on the segment must be allocated cells and then occupy the cells while moving on the segment.  A segment
     * has a starting location (origin) and an ending location (destination) and is associated with a conveyor. A series
     * of segments represents the conveyor. The cells on a segment are numbered from 1 to n, with 1 at the origin, and
     * n at the destination where n is the number of cells on the segment.
     */
    inner class Segment(segmentData: SegmentData, name: String?) : ModelElement(this@Conveyor, name) {

        var status: SegmentStatus = SegmentStatus.IDLE
            internal set(value) {
                field = value
                this@Conveyor.segmentStatusChange(this)
            }

        /**
         *  This queue holds items that are waiting at the start of the segment for the appropriate number of cells on the
         *  conveyor in order to ride (move) to their destination. An item should be in the
         *  access queue until a sufficient number of cells are available for the item to begin moving onto the conveyor
         */
        private val myAccessQ = ConveyorQ(this, "${this.name}:AccessQ")
        val accessQ: QueueCIfc<Conveyor.CellRequest>
            get() = myAccessQ

        private val endCellTraversalAction = EndOfCellTraversalAction()

        /**
         * The event associated with the movement of the lead item on the segment
         */
        private var cellTraversalEvent: KSLEvent<Item>? = null

        private val exitSegmentAction = ExitSegmentAction()

        /**
         *  The event associated with the movement of the item exiting at the end of the segment
         */
        private var exitSegmentEvent: KSLEvent<Item>? = null

        /**
         *  Holds the items that occupy cells on the segment. The items are held in the list
         *  based on order of entry, with the first item being the oldest and furthest forward towards the end
         *  of the segment and the last item being in the newest and closest to the entry of the conveyor.
         */
        private val myItems = mutableListOf<Item>()

        var entryCellAllocation: CellAllocation? = null
            internal set(value) {
                field = value
                status = if (value == null) {
                    SegmentStatus.UNBLOCKED_ENTERING
                } else {
                    SegmentStatus.BLOCKED_ENTERING
                }
            }

        val firstItem: Item?
            get() = myItems.firstOrNull()

        val lastItem: Item?
            get() = myItems.lastOrNull()

        val entryLocation: IdentityIfc = segmentData.entryLocation

        val exitLocation: IdentityIfc = segmentData.exitLocation

        /**
         *  The total number of cells on this segment of the conveyor
         */
        val numCells: Int = segmentData.length / cellSize
        private val myCells: List<Cell>

        init {
            val list = mutableListOf<Cell>()
            for (i in 1..numCells) {
                Cell(list)
            }
            myCells = list.asList()
        }

        val entryCell: Cell
            get() = myCells.first()

        val exitCell: Cell
            get() = myCells.last()

        /**
         * The number of available (unoccupied) consecutive cells starting from
         * the beginning of the segment.
         */
        val numAvailableCells: Int  //TODO redo
            get() {
                if (entryCellAllocation != null) {
                    return 0 // an item waiting to convey means the start of the segment is not available
                }
                var sum = 0
                for (cell in myCells) {
                    if (!cell.isOccupied) {
                        sum++
                    } else {
                        return sum
                    }
                }
                return sum
            }

        private val myNumCellsOccupied = TWResponse(this, "${this.name}:NumCellsOccupied")//TODO

        //TODO how to transfer from one segment to the next

        override fun initialize() {
            status = SegmentStatus.IDLE
            for (cell in myCells) {
                cell.occupyingItem = null
            }
            myItems.clear()
        }

        /**
         *  Determines the lead item on the segment.  The lead item
         *  is the item that is the furthest forward that is not blocked.
         *  An item is not blocked if the cell in front of it exists and
         *  is not occupied.  The lead item may be an item that is waiting to convey, but
         *  that is not yet occupying cells on the conveyor.
         */
        fun findLeadItem(): Item? {
            for (item in myItems.reversed()) {
                if (item.occupiesCells){
                    val nextCell = item.firstCell?.nextCell
                    if ((nextCell != null) && !nextCell.isOccupied) {
                        return item
                    }
                } else {
                    // if the item does not occupy any cells, it must be the last item of the list
                    // and must be associated with the cell allocation that is waiting to get on the conveyor
                    require(item == myItems.last()) {"The found lead item does not occupy any cells but is not the last item."}
                    require(item.segment?.entryCellAllocation?.item == item){"The found lead item was not associated with " +
                            "the cell allocation waiting to get on the conveyor"}
                    if (entryCell.isOccupied){
                        // if the first cell is occupied, then this waiting item cannot be the lead item
                        return null
                    }
                    return item
                }
            }
            return null
        }

        internal fun enqueueRequest(request: CellRequest) {
            myAccessQ.enqueue(request)
        }

        internal fun dequeueRequest(request: CellRequest) {
            myAccessQ.remove(request)
        }

        /**
         *  Checks if the number of available cells at the beginning of the segment
         *  are sufficient to allow allocation
         */
        fun canAllocate(numCellsNeeded: Int): Boolean {
            return numAvailableCells >= numCellsNeeded
        }

        /**
         *  Causes the events associated with the movement of time to be cancelled.
         */
        internal fun stopMovement() {
            if (cellTraversalEvent != null) {
                cellTraversalEvent!!.cancelled = true
            }
            if (exitSegmentEvent != null) {
                exitSegmentEvent!!.cancelled = true
            }
        }

        /**
         * Causes movement associated with the segment to continue
         */
        internal fun startMovement() {
            // if the scheduled event times occur after the current time
            // then reschedule the events using the time remaining
            //TODO this assumes that the lead item did not change.  Is that true???
            if (cellTraversalEvent != null) {
                val e = cellTraversalEvent!!
                if (e.time >= time) {
                    cellTraversalEvent = endCellTraversalAction.schedule(e.timeRemaining, message = e.message)
                }
            }
            if (exitSegmentEvent != null) {
                val e = exitSegmentEvent!!
                if (e.time >= time) {
                    exitSegmentEvent = exitSegmentAction.schedule(e.timeRemaining, message = e.message)
                }
            }
        }

        /**
         * Cause the creation of a cell allocation for the start (entry point) of the segment.
         * The allocation blocks further entry to the segment while waiting to convey.
         * If the conveyor is non-accumulating then all movement along the segment stops.
         * There is no time advancement associated with this function.
         */
        internal fun allocateCells(request: CellRequest): CellAllocation {
            require(entryCellAllocation == null) { "There is already a cell allocation waiting to convey" }
            val ca = CellAllocation(request.entity, request.numCellsNeeded, request.entryLocation)
            ca.isWaitingToConvey = true
            request.entity.cellAllocation = ca
            entryCellAllocation = ca // causes segment status to change to BLOCKED_ENTRY
//            //TODO probably delegate to main conveyor, because entire conveyor stops for non-accumulating
//            if (conveyorType == Type.NON_ACCUMULATING) {
//                stopMovement() // causes all movement forward of the entry location to stop
//            }
            return ca
        }

        /**
         *  This is called from the enclosing conveyor. The enclosing conveyor
         *  is responsible for ensuring that inputs are valid
         *  The entity associated with the item should be suspended after this call.
         *  This function does not do the suspension.  The item
         *  will remain on the conveyor until the entity indicates that the cells are to be released by using
         *  the exit function. The behavior of the conveyor during the ride and when the item reaches its
         *  destination is governed by the type of conveyor. A blockage occurs at the destination location of the segment
         *  while the entity occupies the final cells before exiting or riding again.
         */
        internal fun conveyItem(cellAllocation: CellAllocation, destination: IdentityIfc) {
            // two cases 1) waiting to get on the conveyor or 2) already on the conveyor
            if (cellAllocation.isWaitingToConvey) {
                startConveyance(cellAllocation, destination)
            } else {
                continueConveyance(cellAllocation, destination)
            }
        }

        /**
         *  This method creates an item that can use the cells of the segment and adds
         *  the item to the list of items that are controlled by the movement along the conveyor
         */
        private fun createConveyorItem(cellAllocation: CellAllocation, destination: IdentityIfc): Item {
            // need to create the item, attach the item, put it in the item list, start the movement
            val item = Item(cellAllocation, destination)
            cellAllocation.item = item // attach the item to the allocation
            item.segment = this // the item is using this segment
            myItems.add(item) // the segment is managing the movement of the item
            return item
        }

        private fun startConveyance(cellAllocation: CellAllocation, destination: IdentityIfc) {
            // the cell allocation is causing a blockage at the entry point of the segment
            // need to create the item, attach the item, put it in the item list, start the movement
            val item = Item(cellAllocation, destination)
            cellAllocation.item = item // attach the item to the allocation
            item.segment = this // the item is using this segment
            myItems.add(item) // the segment is managing the movement of the item
            item.occupyCell(entryCell) // the item now occupies the first cell of the segment

            // segment does not become unblocked until entire item is on the conveyor

//            entryCellAllocation = null // unblock the entry
//            cellAllocation.isWaitingToConvey = false
            // item is ready to convey on this segment, moving may depend on other segments
            // let conveyor decide what to do
            this@Conveyor.scheduleMovement()

            TODO("Conveyor.Segment.startConveyance()")
        }

        private fun continueConveyance(cellAllocation: CellAllocation, destination: IdentityIfc) {
            // already on the conveyor and reached the end of a segment associated with destination
            // user want to continue riding to another destination, w/o getting off
            //
            //TODO need to transition to next segment
            TODO("Conveyor.Segment.continueConveyance()")
        }

        /**
         *  This is called from the enclosing conveyor. The enclosing conveyor
         *  is responsible for ensuring that the item can be conveyed.
         *  The entity associated with the item should be suspended after this call.
         *  This function does not do the suspension.
         */
        internal fun conveyItem(item: Item) {
            // if the conveyor is empty, then we need to start the movement and have the item
            // occupy the first cell
            if (myItems.isEmpty()) {
                item.occupyCell(entryCell)
                // this item becomes the lead item
                cellTraversalEvent = endCellTraversalAction.schedule(cellTravelTime, item)
            }
            // add it to the list of items managed by the segment
            // notice that the item may not be occupying any cells
            // when the lead item moves forward all items associated with the segment will move forward
            // make the item associated with the segment
            myItems.add(item)
            // the entity associated with the item should be suspended, after this call to convey
        }

        /**
         * The lead item is the item that pulls all items behind it forward
         * when it moves forward.  When the lead item moves forward through its next cell, all
         * items behind it also move through their next cells.
         *
         * This function is associated with the end of the traversal through the cell that was
         * unoccupied in front of the lead item.  When this function is called, the lead item has
         * just traversed (in time) the physical space
         * associated with the next cell. The next cell is the cell that is
         * in front of the item's current front cell.  We need to move the items
         * forward through their cells so that they occupy their next cells.
         *
         * The leading item may have reached the end of the segment. That is
         * the front cell occupied by the item may be the last cell of the segment.
         * If not at the end of the segment, then schedule the next traversal.
         * If at the end of the segment, the item ends its trip.
         */
        private fun endCellTraversalEventActions(leadItem: Item) {
            // this needs to start with the lead item and only include itself and
            // those items behind the lead item.
            // move all the items on the segment forward by one cell
            moveItemsForward(leadItem)
            if (leadItem.hasReachedEndOfSegment) {
                // coordinate with the entity to allow it to decide what to do
                // the entity is resumed (at the current time), but its conveyable is
                // still on the segment
                conveyorHoldQ.removeAndResume(leadItem.entity, leadItem.resumePriority)
            } else {
                if (leadItem.isNextCellOccupied) {
                    // lead item can not move forward if the next cell is occupied
                    //TODO what to do if lead item becomes blocked?
                } else {
                    // lead item can move forward
                    // if the next cell is not occupied, schedule the next traversal
                    endCellTraversalAction.schedule(cellTravelTime, leadItem)
                }
            }
        }

        private fun moveItemsForward(leadItem: Item) {
            val i = myItems.indexOf(leadItem)
            val itr = myItems.listIterator(i)
            while (itr.hasNext()) {
                itr.next().moveForwardOneCell()
            }
        }

        internal fun scheduleConveyorExit(exitingItem: Item) {
            exitSegmentAction.schedule(cellTravelTime, exitingItem)
        }

        private fun exitSegmentEventActions(exitingItem: Item) {
            // if an item exits the segment, then all items can move forward
            moveItemsForward(exitingItem)
            if (exitingItem.occupiesCells) {
                // exiting item still has cells on the conveyor
                // need to delay to travel through the next cell
                exitSegmentAction.schedule(cellTravelTime, exitingItem)
            } else {

            }
            // TODO how to handle case of using more than one cell when exiting?

            TODO("handle exiting the segment")
            // move all the items on the segment forward by one cell
            // the first item in the conveyable list should be the leading item

            //TODO exiting the conveyor actually takes time to move through the occupied cells
        }

        fun deallocateCells(item: Item) {
            require(!item.occupiesCells) { "Cannot deallocate cells associated with the item because it still occupies cells" }
            //TODO give cells back
            item.numCellsAllocated = 0

            // check queue
            if (myAccessQ.isNotEmpty) {
                val nextItem = myAccessQ.peekNext()!!
                if (canAllocate(nextItem.numCellsNeeded)) {
                    myAccessQ.removeNext()
//TODO                    conveyorHoldQ.removeAndResume(nextItem.entity, nextItem.resumePriority)
                }
            }
            TODO("handle de-allocation of cells")
        }

        /**
         * This function should deallocate the cells associated with the cell allocation
         * and cause any blockage associated with the allocation to be removed. This
         * function is called from the outer conveyor class to delegate the work to
         * the segment associated with the cells.
         * There should not be any time delay associated with this function, but it may cause
         * events to be scheduled and processes to be resumed as the allocation is released.
         */
        fun deallocateCells(cellAllocation: CellAllocationIfc) {
            TODO("Conveyor.Segment.deallocateCells()")
        }

        /**
         * This function should start the exiting process for the entity holding
         * the cell allocation.  The cells associated with the cell allocation should be deallocated
         * and any blockage associated with the allocation to be removed. This
         * function is called from the outer conveyor class to delegate the work
         * to the segment associated with the cells.
         *
         * There may be time delay associated with this function. It may cause
         * events to be scheduled and processes to be resumed as the allocation is released.
         */
        fun startExitingProcess(cellAllocation: CellAllocationIfc) {
            TODO("Conveyor.Segment.startExitingProcess()")
        }

        internal fun itemFullyLoaded(item: Conveyor.Item) {
            TODO("Conveyor.Segment.itemEntered()")
        }


        private inner class EndOfCellTraversalAction : EventAction<Item>() {
            override fun action(event: KSLEvent<Item>) {
                endCellTraversalEventActions(event.message!!)
            }

        }

        private inner class ExitSegmentAction : EventAction<Item>() {
            override fun action(event: KSLEvent<Item>) {
                exitSegmentEventActions(event.message!!)
            }

        }

        /**
         *  A cell represents a length of space along the conveyor segment that
         *  can be allocated to and occupied by conveyable items.  A segment is divided
         *  into a set of cells to represent its length.  A cell acts like a general
         *  unit of distance along the segment.
         */
        inner class Cell(private val cellList: MutableList<Cell>) {
            init {
                cellList.add(this)
            }

            val cellNumber: Int = cellList.size
            val segment: Segment = this@Segment

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
             *  A cell is occupied if it is covered by an item, and it is allocated.
             */
            val isOccupied: Boolean
                get() = (occupyingItem != null)

            var occupyingItem: Item? = null
                internal set

        }

    }

    private fun scheduleMovement() {
        TODO("Not yet implemented")
    }

    private fun segmentStatusChange(segment: Segment) {
        val s = segment.status
        TODO("Conveyor.segmentStatusChange()")
        // need to have the entire conveyor react to individual segment status changes

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
        private lateinit var segmentsData: SegmentsData
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
            segmentsData = SegmentsData(cellSize, start)
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
        sb.append(mySegmentData)
        return sb.toString()
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