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
 * This data class represents the origin [start] and destination [end] of a [length]
 * of a segment for a conveyor. See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
data class SegmentData(val start: IdentityIfc, val end: IdentityIfc, val length: Int) {
    init {
        require(start != end) { "The start and the end of the segment must be different!" }
        require(length >= 1) { "The length of the segment must be >= 1 unit" }
    }

    override fun toString(): String {
        return "(start = ${start.name} --> end = ${end.name} : length = $length)"
    }

}

/**
 * This class represents the data associated with segments of a conveyor and facilitates
 * the specification of segments for a conveyor.  See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
class SegmentsData(val cellSize: Int = 1, val firstLocation: IdentityIfc) {
    private val mySegments = mutableListOf<SegmentData>()
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
        mySegments.add(SegmentData(lastLocation, next, length))
        if (length <= minimumSegmentLength) {
            minimumSegmentLength = length
        }
        lastLocation = next
    }

    val entryLocations: List<IdentityIfc>
        get() {
            val list = mutableListOf<IdentityIfc>()
            for (seg in mySegments) {
                list.add(seg.start)
            }
            return list
        }

    val exitLocations: List<IdentityIfc>
        get() {
            val list = mutableListOf<IdentityIfc>()
            for (seg in mySegments) {
                list.add(seg.end)
            }
            return list
        }

    val isCircular: Boolean
        get() = firstLocation == lastLocation

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
        return sb.toString()
    }
}

interface ConveyableIfc {
    /**
     * The entity that needs to use the conveyor
     */
    val entity: ProcessModel.Entity

    /**
     *  The number of cells needed when riding on the conveyor
     */
    val numCellsNeeded: Int

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
    val frontCell: Conveyor.Segment.Cell?

    /**
     *  This cell is the furthermost cell occupied by the object towards the where
     *  it originated.
     */
    val endCell: Conveyor.Segment.Cell?

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
            return if (segment == null){
                null
            } else {
                segment!!.end
            }
        }

    /**
     *  True if the entity has reached its destination
     */
    val hasReachedDestination: Boolean

    val occupiesCells: Boolean
}

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
            mySegmentMap[seg.start] = segment
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
     * Creates an object that can request cells to be allocated on the conveyor.
     *
     * @param entity the entity to which the cells need to be allocated on the conveyor
     * @param numCellsNeeded the number of cells needed at the entry point of the conveyor by the entity
     * @param origin the entry location at which the entity wants the cells on the conveyor
     * @return a conveyable representing the entity's potential request for cells on the conveyor.
     * At this point, the object does not have cells allocated, but is now able to request cells.
     */
    internal fun createConveyable(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int = 1,
        origin: IdentityIfc
    ): Conveyable {
        return Conveyable(entity, numCellsNeeded, origin)
    }

    /**
     * It is an error to attempt to allocate cells if there are insufficient
     * cells available. Thus, the number of cells needed must be less than or equal to the number of cells
     * available at the origin point at the time of this call. This function
     * should only be called from the access() suspend function.
     *
     * @param item the conveyable item that wants the cells
     */
    internal fun allocateCells(item: Conveyable) {
        require(item.conveyor == this) { "Item is not from this conveyor" }
        require(!item.isConveyable) { "The item already has its requested number of cells" }
        require(canAllocateCells(item.origin, item.numCellsNeeded))
        { "Cannot allocate ${item.numCellsNeeded} at origin ${item.origin.name} to the entity at this time instant." }
        // get the segment for entry onto the conveyor
        val segment = mySegmentMap[item.origin]!!
        // ask the segment to allocate cells to the entity and make the conveyable
        segment.allocateCells(item)
        if (conveyorType == Type.NON_ACCUMULATING) {
            segment.stopMovement()
        }
    }

    /**
     * Conveying an item may cause events to be scheduled to move the lead item forward.
     * If it cannot be immediately conveyed the item is held until the move event executes.
     * The entity associated with the item should be suspended, placed in the
     * conveyor's HoldQ, after this call from the ride() suspend function of the process.
     * This function should only be called from the ride() suspend function.
     */
    internal fun conveyItem(item: Conveyable, destination: IdentityIfc) {
        // the item should be conveyable, it needs to have a destination
        require(item.isConveyable) { "Tried to convey an item that is not conveyable" }
        require(item.conveyor == this) { "Item is not from this conveyor" }
        require(exitLocations.contains(destination)) { "The destination is not on this conveyor" }
        require(item.segment != null) { "The item was not using a segment" }
        item.destination = destination
        item.segment!!.conveyItem(item)
        // the entity associated with the item should be suspended, after this call
    }

    /**
     *  Causes the item to exit the conveyor at its destination.  Exiting the conveyor
     *  causes the item to travel through the final cells that it occupies. Thus,
     *  this call will take simulated time.  The process (entity) calling this should be
     *  suspended until the exiting time is completed.
     */
    internal fun exitConveyor(item: Conveyable) {
        require(item.conveyor == this) { "Item is not from this conveyor" }
        require(item.segment != null) { "The item was not using a segment" }
        // conveyable means that the item has its required allocated cells
        require(item.isConveyable) { "Tried to exit a conveyor for an item that is not conveyable" }
        // since it is conveyable, it has at least 1 allocated cell
        //TODO handle the case of when item exits without occupying any cells, there will not be any time delay
        if (!item.occupiesCells){
            // item does not occupy any cells, exiting without being on segment
            // just deallocate the cells
            item.segment!!.deallocateCells(item)
        } else {

        }

        // make sure that the item occupies cells
        require(item.occupiesCells) { "The exiting item does not occupy any cells on the conveyor" }
        // has allocated cells and is occupying cells on the conveyor
        // TODO need to make sure that the item's current location matches the exit location
        require(item.destination != null) { "The item had no destination set" }
        require(exitLocations.contains(item.destination)) { "The destination is not on this conveyor" }
        // delegate to the segment
        // this will schedule an event to start the exiting process
        item.segment!!.scheduleConveyorExit(item)
    }

    //TODO how to stop and start the conveyor? also changing the velocity at start time
    //TODO accumulating conveyors allow the item after the leading item to continue moving if the leading item is blocked
    //TODO what happens if lead item is blocked

    /**
     *  An object that is conveyable can be allocated cells on a conveyor and occupy cells
     *  on a segment of a conveyor.
     */
    inner class Conveyable(
        override val entity: ProcessModel.Entity,
        override val numCellsNeeded: Int = 1,
        override val origin: IdentityIfc,
        override var destination: IdentityIfc? = null
    ) : QObject(), ConveyableIfc {
        init {
            require(entity.conveyable == null) { "The entity already has a conveyor allocation" }
            require(numCellsNeeded <= maxEntityCellsAllowed) {
                "The entity requested more cells ($numCellsNeeded) than " +
                        "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name})"
            }
            require(entryLocations.contains(origin)) { "The origin ($origin) is not a valid entry point on the conveyor" }
        }

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
            get() = numCellsAllocated == numCellsNeeded

        override val conveyor = this@Conveyor

        override val hasReachedEndOfSegment: Boolean
            get() {
                return if (segment == null) {
                    false
                } else if (frontCell != null) {
                    frontCell == segment!!.lastCell
                } else {
                    false
                }
            }

        override val hasReachedDestination: Boolean
            get() {
                return if (currentLocation == null) {
                    false
                } else if (destination == null) {
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
         *  The cell that is occupied by the item that is the furthest forward (closest to the end) on the segment
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor.  If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then the front cell is 3.
         */
        override val frontCell: Segment.Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.last() else null

        /**
         *  The cell that is occupied by the item that is closest to the origin of the segment
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  An item may occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor. If the segment has 5 cells (1, 2, 3, 4, 5) and
         *  the item needs 2 cells and is occupying cells 2 and 3, then the end cell is 2.
         */
        override val endCell: Segment.Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.first() else null

        /**
         *  Causes an item already on the segment to move forward by one cell.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         */
        internal fun moveForwardOneCell() {
            require(isConveyable) { "The item cannot move forward because it has no allocated cells" }
            require(frontCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the front cell cannot be null, safe to use
            require(frontCell!!.isNotLast) { "The item cannot move forward because it has reached the end of the segment" }
            // the front cell is not the last cell of the segment
            // this means that there must be a next cell
            // each occupied cell becomes the next occupied cell
            occupyCell(frontCell!!.nextCell!!)
        }

        /**
         *  Causes the item to occupy the supplied cell.  No checking of the contiguous nature of cells
         *  is performed.  The cell is added to the end of the cells occupied and if the number of
         *  cells needs is reached, the oldest cell is removed from the cells occupied.
         */
        internal fun occupyCell(cell: Segment.Cell) {
            if (myCellsOccupied.size < numCellsNeeded) {
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
        /**
         *  This queue holds items that are waiting at the start of the segment for the appropriate number of cells on the
         *  conveyor in order to ride (move) to their destination. An item should be in the
         *  access queue until a sufficient number of cells are available for the item to begin moving onto the conveyor
         */
        private val myAccessQ = ConveyorQ(this, "${this.name}:AccessQ")
        val accessQ: QueueCIfc<Conveyor.Conveyable>
            get() = myAccessQ

        /**
         *  Holds the items that need to move along (use cells) associated with the segment
         */
        private val myConveyables = mutableListOf<Conveyable>()

        private val endCellTraversalAction = EndOfCellTraversalAction()
        private val exitSegmentAction = ExitSegmentAction() //TODO need to schedule

        private var cellTraversalEvent: KSLEvent<Conveyable>? = null
        private var exitSegmentEvent: KSLEvent<Conveyable>? = null //TODO need to capture

        val start: IdentityIfc = segmentData.start

        val end: IdentityIfc = segmentData.end

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

        val firstCell: Cell
            get() = myCells.first()

        val lastCell: Cell
            get() = myCells.last()

        /**
         * The number of available (unoccupied) consecutive cells starting from
         * the beginning of the segment.
         */
        val numAvailableCells: Int
            get() {
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
            for (cell in myCells) {
                cell.occupyingItem = null
            }
            myConveyables.clear()
        }

        /**
         *  Checks if the number of available cells at the beginning of the segment
         *  are sufficient to allow allocation
         */
        fun canAllocate(numCellsNeeded: Int): Boolean {
            return numAvailableCells >= numCellsNeeded
        }

        /**
         *  Causes the events associated with the movement of time
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
        internal fun reStartMovement() {
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
         * This method only allocates the cells on the segment. There is no movement
         * associated with the allocation. Allocation means that the conveyable has control
         * over the cells.  This method is called from the conveyor when a conveyable requests
         * cells for allocation.
         */
        internal fun allocateCells(item: Conveyable) {
            require(canAllocate(item.numCellsNeeded)) { "Tried to allocate cells when an insufficient amount of cells was available" }
            // cells are only allocated at the start of the segment, start with cell 0
            // attach it to the entity, to ensure that an entity cannot be on more than one conveyor at a time
            item.entity.conveyable = item
            // make the cells allocated and give them to the item
            for (i in 0 until item.numCellsNeeded) {
                myCells[i].allocated = true
                item.numCellsAllocated = item.numCellsAllocated + 1
            }
            // indicate that the item is using this segment
            item.segment = this
        }

        /**
         *  This is called from the enclosing conveyor. The enclosing conveyor
         *  is responsible for ensuring that the item can be conveyed.
         *  The entity associated with the item should be suspended after this call.
         *  This function does not do the suspension.
         */
        internal fun conveyItem(item: Conveyable) {
            // if the conveyor is empty, then we need to start the movement and have the item
            // occupy the first cell
            if (myConveyables.isEmpty()) {
                item.occupyCell(firstCell)
                // this item becomes the lead item
                cellTraversalEvent = endCellTraversalAction.schedule(cellTravelTime, item)
            }
            // add it to the list of items managed by the segment
            // notice that the item may not be occupying any cells
            // when the lead item moves forward all items associated with the segment will move forward
            // make the item associated with the segment
            myConveyables.add(item)
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
        private fun endCellTraversalEventActions(leadItem: Conveyable) {
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

        private fun moveItemsForward(leadItem: Conveyable) {
            val i = myConveyables.indexOf(leadItem)
            val itr = myConveyables.listIterator(i)
            while (itr.hasNext()) {
                itr.next().moveForwardOneCell()
            }
        }

        internal fun scheduleConveyorExit(exitingItem: Conveyable) {
            exitSegmentAction.schedule(cellTravelTime, exitingItem)
        }

        private fun exitSegmentEventActions(exitingItem: Conveyable) {
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

        fun deallocateCells(item: Conveyable) {
            require(!item.occupiesCells){"Cannot deallocate cells associated with the item because it still occupies cells"}
            //TODO give cells back
            item.numCellsAllocated = 0

            // check queue
            if (myAccessQ.isNotEmpty){
                val nextItem = myAccessQ.peekNext()!!
                if (canAllocate(nextItem.numCellsNeeded)){
                    myAccessQ.removeNext()
                    conveyorHoldQ.removeAndResume(nextItem.entity, nextItem.resumePriority)
                }
            }
            TODO("handle de-allocation of cells")
        }

        private inner class EndOfCellTraversalAction : EventAction<Conveyable>() {
            override fun action(event: KSLEvent<Conveyable>) {
                endCellTraversalEventActions(event.message!!)
            }

        }

        private inner class ExitSegmentAction : EventAction<Conveyable>() {
            override fun action(event: KSLEvent<Conveyable>) {
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

            val nextCell: Cell?
                get() {
                    return if (isLast) null
                    else
                        cellList[cellNumber] // because cells are numbered starting at 1, but list is 0 index based
                }

            val previousCell: Cell?
                get() {
                    return if (isFirst) null
                    else
                        cellList[cellNumber - 1] // because cells are numbered starting at 1, but list is 0 index based
                }

            /**
             *  A cell can be allocated but not yet occupied //TODO is this really true
             */
            var allocated: Boolean = false //TODO is this really needed??
                internal set(value) {
                    require(occupyingItem == null) { "Tried to allocate an already occupied cell" }
                    field = value
                }

            /**
             *  A cell is occupied if it is covered by an item, and it is allocated.
             */
            val isOccupied: Boolean
                get() = (occupyingItem != null) && (allocated)

            var occupyingItem: Conveyable? = null
                internal set(value) {
                    require(allocated) { "Tried to occupy the cell without it be allocated" }
                    field = value
                    if (value == null) {
                        allocated = false
                    }
                }

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