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
    val entity: ProcessModel.Entity
    val numCellsNeeded: Int
    val origin: IdentityIfc
    val destination: IdentityIfc?
    val numCellsAllocated: Int
    val numCellsOccupied: Int
    val isConveyable: Boolean
    val conveyor: Conveyor
    val segment: Conveyor.Segment
    val frontCell: Conveyor.Segment.Cell?
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
 * Items that ride on the segment must be allocated cells and then occupy the cells while moving on the segment.  Items
 * can occupy more than one cell while riding on the conveyor.  An item trying to access the conveyor at a start of a
 * segment, waits until its required number of contiguous cells are available.  Then, the item is permitted to ride
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
 * @param initialVelocity the velocity of the conveyor
 * @param segmentData the specification of the segments
 * @param maxEntityCellsAllowed the maximum number of cells that an entity can occupy while riding on the conveyor
 * @param name the name of the conveyor
 */
class Conveyor(
    parent: ModelElement,
    velocity: Double = 1.0,
    segmentData: SegmentsData,
    val maxEntityCellsAllowed: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(velocity > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
    }

    private val mySegmentMap = mutableMapOf<IdentityIfc, Segment>()
    private val mySegmentList = mutableListOf<Segment>()
    private val mySegmentData: SegmentsData

    /**
     *  This holds the entities that are suspended because they are currently
     *  riding on (moving) the conveyor to their destination.  This is used to resume the entity's process
     *  after the entity reaches its destination on the conveyor. The process is
     *  then resumed and the entity can decide to exit the conveyor, experience
     *  a process while on the conveyor, or continue riding to another destination.
     *  Because this queue is internal to the conveyor, statistics are not collected.
     *
     */
    internal val conveyorHoldQ = HoldQueue(this, "${this.name}:HoldQ")//TODO??

    var initialVelocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The initial velocity of the conveyor must be > 0.0" }
            field = value
        }

    init {
        require(maxEntityCellsAllowed >= 1) { "The maximum number of cells that can be occupied by an entity must be >= 1" }
        require(segmentData.isNotEmpty()) { "The segment data must not be empty." }
        mySegmentData = segmentData
        for ((i, seg) in mySegmentData.segments.withIndex()) {
            val segment = Segment(seg, "${this.name}:Seg:$i")
            mySegmentMap[seg.start] = segment
            mySegmentList.add(segment)
        }
        conveyorHoldQ.waitTimeStatOption = false
        conveyorHoldQ.defaultReportingOption = false
    }

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

    val cellSize = mySegmentData.cellSize

    var velocity: Double = velocity
        set(value) {
            require(value > 0.0) { "The velocity of the conveyor must be > 0.0" }
            field = value
        }

    val cellTravelTime: Double
        get() = cellSize / velocity

    override fun initialize() {
        velocity = initialVelocity
    }

    /**
     *  Checks if the number of cells needed [numCellsNeeded] can be
     *  allocated at the origin [origin] of the segment of the conveyor.
     *  True means that the amount of cells needed at the start of the segment can be allocated at this
     *  instant in time.
     */
    fun canAllocateCells(numCellsNeeded: Int, origin: IdentityIfc): Boolean {
        require(entryLocations.contains(origin)) { "The origin ($origin) is not a valid entry point on the conveyor" }
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name}"
        }
        val segment = mySegmentMap[origin]!!
        return segment.canAllocate(numCellsNeeded)
    }

    /**
     * It is an error to attempt to allocate cells if there are insufficient
     * cells available. Thus, the number of cells needed must be less than or equal to the number of cells
     * available at the origin point at the time of this call.
     *
     * @param entity the entity to which the cells need to be allocated for riding on the conveyor
     * @param numCellsNeeded the number of cells needed to ride on the conveyor by the entity
     * @param origin the location at which the entity wants the cells to get on the conveyor
     * @return a conveyable representing that the item holding the cells and the entity while using the conveyor.
     * The reference to this conveyable is necessary in order to deallocate the allocated cells.
     */
    internal fun allocateCells(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int,
        origin: IdentityIfc
    ): Conveyable {
        require(entity.conveyable == null) { "The entity already has a conveyor allocation" }
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name})"
        }
        require(entryLocations.contains(origin)) { "The origin ($origin) is not a valid entry point on the conveyor" }
        require(canAllocateCells(numCellsNeeded, origin))
        { "Cannot allocate $numCellsNeeded at origin ${origin.name} to the entity at this time instant." }
        // get the segment for entry onto the conveyor
        val segment = mySegmentMap[origin]!!
        // ask the segment to allocate cells to the entity and make the conveyable
        val item = segment.allocateCells(entity, numCellsNeeded, origin)
        // return the conveyable to use when ready to convey
        // TODO when should the entity be removed from the access queue and its process resumed?
        return item
    }

    internal fun conveyItem(item: Conveyable, destination: IdentityIfc) {
        // the item should be conveyable, it needs to have a destination
        require(item.isConveyable) { "Tried to convey an item that is not conveyable" }
        require(item.conveyor == this) { "Item is not from this conveyor" }
        require(exitLocations.contains(destination)) { "The destination is not on this conveyor" }

    }

    internal fun exitConveyor(entity: ProcessModel.Entity) {
        require(entity.conveyable != null) { "The entity does not have conveyor allocation" }
        deallocateCells(entity.conveyable as Conveyable)

    }

    private fun deallocateCells(conveyable: Conveyable) {
        //TODO all calls to conveyable, thus maybe put this logic there
        require(conveyable.conveyor == this) { "The allocation was not from this conveyor" }
        require(conveyable.numCellsAllocated > 0) { "There were no cells allocated to deallocate." }
        require(conveyable.isConveyable) { "The allocation has already been deallocated" }
        require(conveyable.destination != null) { "The destination of the conveyable was not set" }
        // current location must be a valid destination
        //TODO how to give the cells back
    }

    //TODO how to stop and start the conveyor? also changing the velocity at start time


    /**
     *  An object that is conveyable can be allocated cells on a conveyor and occupy cells
     *  on a segment of a conveyor.
     */
    inner class Conveyable(
        override val entity: ProcessModel.Entity,
        override val numCellsNeeded: Int = 1,
        startingSegment: Segment,
        override val origin: IdentityIfc,
        override var destination: IdentityIfc? = null
    ) : QObject(), ConveyableIfc {
        //TODO not sure if this data structure should be used
        private val myCellsOccupied: ArrayDeque<Segment.Cell> = ArrayDeque()

        override var numCellsAllocated: Int = 0
            internal set

        override val numCellsOccupied: Int
            get() = myCellsOccupied.size

        override val isConveyable: Boolean
            get() = numCellsAllocated == numCellsNeeded

        override val conveyor = this@Conveyor

        override var segment: Conveyor.Segment = startingSegment
            internal set

        /**
         *  The cell that is occupied by the item that is the furthest forward on the segment
         *  that the item is currently on.  Null means that the item is not occupying any cells.
         *  The items will occupy 1 or more cells depending on the number of cells that it
         *  needs while riding on the conveyor
         */
        override val frontCell: Segment.Cell?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied.first() else null

        /**
         *  Causes an item already on the segment to move forward by one cell.  This
         *  causes the cells that the item occupies to be updated to the next cell.
         */
        internal fun moveForwardOneCell() {
            require(isConveyable){"The item cannot move forward because it has no allocated cells"}
            require(frontCell != null) { "The item cannot move forward because it does not occupy any cells" }
            // the front cell cannot be null, safe to use
            require(frontCell!!.isNotLast) {"The item cannot move forward because it has reached the end of the segment"}
            // the front cell is not the last cell of the segment
            // this means that there must be a next cell
            // each occupied cell becomes the next occupied cell
            for(cell in myCellsOccupied){
                occupyCell(cell.nextCell!!)//TODO
            }
        }

        /**
         *  Causes the item to occupy the supplied cell.  No checking of the contiguous nature of cells
         *  is performed.
         */
        private fun occupyCell(cell: Segment.Cell) {
            if (myCellsOccupied.size < numCellsNeeded) {
                myCellsOccupied.add(cell)
                cell.occupyingItem = this
            } else {
                popFrontCell() //TODO
                myCellsOccupied.add(cell)  //TODO this is adding it to the end of the list
                cell.occupyingItem = this
            }
        }

        private fun popFrontCell(): Boolean {
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
     * of segments represents the conveyor.
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
         *  Holds the items that are on the segment
         */
        private val myConveyables = mutableListOf<Conveyable>()

        private val endCellTraversalAction = EndOfCellTraversalAction()

        private var cellTraversalEvent: KSLEvent<Cell>? = null

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
         * The number of available (unallocated) consecutive cells starting from
         * the beginning of the segment.
         */
        val numAvailableCells: Int
            get() {
                var sum = 0
                for (cell in myCells) {
                    if (!cell.allocated) {
                        sum++
                    } else {
                        return sum
                    }
                }
                return sum
            }

        private val myNumCellsOccupied = TWResponse(this, "${this.name}:NumCellsOccupied")

        //TODO make the cells, need cell events, transfer from one segment to the next
        // should each cell have the events or should events handle any cell
        // first cell action, last cell action, intermediate cell action

        override fun initialize() {
            for (cell in myCells) {
                cell.occupyingItem = null
            }
            myConveyables.clear()
        }

        fun canAllocate(numCellsNeeded: Int): Boolean {
            return numAvailableCells >= numCellsNeeded
        }

        /**
         * This method only allocates the cells on the segment. There is no movement
         * associated with the allocation. Allocation means that the conveyable has control
         * over the cells.  This method is called from the conveyor when a conveyable requests
         * cells for allocation.
         */
        internal fun allocateCells(
            entity: ProcessModel.Entity,
            numCellsNeeded: Int,
            origin: IdentityIfc
        ) : Conveyable {
            require(canAllocate(numCellsNeeded)) { "Tried to allocate cells when an insufficient amount of cells was available" }
            val item = Conveyable(entity, numCellsNeeded, this, origin)
            // cells are only allocated at the start of the segment, start with cell 0
            // attach it to the entity, to ensure that an entity cannot be on more than one conveyor at a time
            entity.conveyable = item
            for (i in 0 until numCellsNeeded) {
                myCells[i].allocated = true
                item.numCellsAllocated = item.numCellsAllocated + 1
            }
            // TODO when should the entity be removed from the access queue and its process resumed?
            return item
        }

        internal fun conveyItem(item: Conveyable) {
            // the item should be conveyable, it needs to have a destination
            require(item.isConveyable) { "Tried to convey an item that is not conveyable" }
            require(item.destination != null) { "The destination of the item was not set" }

            //TODO the entity associated with the item should be suspended, where/when is it suspended

            // if the conveyor is empty, then we need to start the movement and have the item
            // occupy the first cell
            if (myConveyables.isEmpty()) {
                TODO("not done yet")
           //item.occupyCell(firstCell)
                endCellTraversalAction.schedule(cellTravelTime, firstCell)
            }
            // add it to the conveyor
            myConveyables.add(item)
        }

        private fun endCellTraversal(cell: Cell) {
            // the cell that was traversed should always be the "lead" cell
            if (cell.isLast) {
                // item associated with the cell has reached the end of the segment
            } else {
                // not at end of the segment, move every item forward by one cell

            }
            // move every item forward that is on the conveyor by one cell

            // if lead cell has not reached last cell, then schedule its movement forward

            // if lead cell has reached last cell, need to handle exit or continue to next segment

            // this is where accumulating and non-accumulating behavior will need to be addressed
            TODO("working on it")
        }

        private fun moveItemsForwardOneCell() {
            for (item in myConveyables) {

            }
        }

        private inner class EndOfCellTraversalAction : EventAction<Cell>() {
            override fun action(event: KSLEvent<Cell>) {
                endCellTraversal(event.message!!)
            }

        }

        /**
         *  A cell represents a length of space along the conveyor segment that
         *  can be allocated to and occupied by conveyable items.  A segment is divided
         *  into a set of cells to represent its length.  A cell acts like a general
         *  unit of distance along the segment.
         */
        inner class Cell(private val cellList: MutableList<Cell>) {

            val cellNumber: Int

            init {
                cellList.add(this)
                cellNumber = cellList.size
            }

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
             *  A cell can be allocated but not yet occupied
             */
            var allocated: Boolean = false
                internal set(value) {
                    require(occupyingItem == null) { "Tried to allocate an already occupied cell" }
                    field = value
                }

            /**
             *  A cell is occupied if it is covered by an item, and it is allocated.
             */
            val occupied: Boolean
                get() = (occupyingItem != null) && (allocated)

            var occupyingItem: Conveyable? = null
                internal set(value) {
                    require(allocated) { "Tried to occupy the cell without it be allocated" }
                    field = value
                    if (value == null) {
                        allocated = false
                    }
                }

            val segment: Segment = this@Segment
        }

    }

    companion object {
        fun builder(parent: ModelElement, name: String? = null): VelocityStepIfc {
            return Builder(parent, name)
        }
    }

    private class Builder(val parent: ModelElement, val name: String? = null) : VelocityStepIfc, CellSizeStepIfc,
        FirstSegmentStepIfc, SegmentStepIfc {
        private var velocity: Double = 1.0
        private var cellSize: Int = 1
        private var maxEntityCellsAllowed: Int = 1
        private lateinit var segmentsData: SegmentsData

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
            return Conveyor(parent, velocity, segmentsData, maxEntityCellsAllowed, name)
        }

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
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20)
        .build()

    println(c)

}