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

import ksl.modeling.queue.Queue
import ksl.modeling.variable.TWResponse
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import org.jetbrains.kotlinx.dataframe.impl.asList


data class SegmentData(val start: IdentityIfc, val end: IdentityIfc, val length: Int) {
    init {
        require(start != end) { "The start and the end of the segment must be different!" }
        require(length >= 1) { "The length of the segment must be >= 1 unit" }
    }

    override fun toString(): String {
        return "(start = ${start.name} --> end = ${end.name} : length = $length)"
    }

}

class Segments(val cellSize: Int = 1, val firstLocation: IdentityIfc) {
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

class ConveyorAllocation(
    val conveyable: Conveyor.Conveyable,
    val allocationName: String?
) {

    val numCellsAllocated: Int
        get() = conveyable.numCellsOccupied

    /**
     *  True if the allocation has the number of cells
     *  needed to move on the conveyor
     */
    val isAllocated: Boolean
        get() = conveyable.isConveyable

    /**
     *  True if insufficient cells are allocated
     */
    val isDeallocated: Boolean
        get() = !isAllocated
}

class Conveyor(
    parent: ModelElement,
    val velocity: Double = 1.0,
    segmentData: Segments,
    val maxEntityCellsAllowed: Int = 1,
    name: String? = null
) : ModelElement(parent, name) {

    private val mySegmentMap = mutableMapOf<IdentityIfc, Segment>()
    private val mySegmentList = mutableListOf<Segment>()
    private val mySegmentData: Segments

    /**
     *  This holds the entities that are suspended because they are currently
     *  riding on (moving) the conveyor to their destination.  This is used to resume the entity's process
     *  after the entity reaches its destination on the conveyor. The process is
     *  then resumed and the entity can decide to exit the conveyor, experience
     *  a process while on the conveyor, or continue riding to another destination.
     *  Because this queue is internal to the conveyor, statistics are not collected.
     *
     */
    internal val conveyorHoldQ = HoldQueue(this, "${this.name}:HoldQ")

    init {
        require(maxEntityCellsAllowed >= 1) { "The maximum number of cells that can be occupied by an entity must be >= 1" }
        require(velocity > 0.0) { "The velocity of the conveyor must be > 0.0" }
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

    val cellTravelTime: Double = cellSize / velocity

    /**
     *  This method should be called
     */
    internal fun accessConveyor(
        entity: ProcessModel.Entity,
        numCellsNeeded: Int,
        origin: IdentityIfc,
        destination: IdentityIfc
    ) {
        require(numCellsNeeded <= maxEntityCellsAllowed) {
            "The entity requested more cells ($numCellsNeeded) than " +
                    "the allowed maximum ($maxEntityCellsAllowed for for conveyor (${this.name}"
        }
        require(entryLocations.contains(origin)) { "The origin ($origin) is not a valid entry point on the conveyor" }
        require(exitLocations.contains(destination)) { "The destination ($destination) is not a valid entry point on the conveyor" }
        // make the conveyable
        val item = Conveyable(entity, numCellsNeeded, origin, destination)
        // send the item to the correct segment
        val segment = mySegmentMap[origin]!!
        segment.conveyItem(item)

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
     * It is an error to attempt to allocate cells to an entity if there are insufficient
     * cells available at the origin. Thus, the number of cells needed must be less than or equal to the number of cells
     * available at the origin point at the time of this call.
     *
     * @param entity the entity that is requesting the units
     * @param numCellsNeeded that amount to allocate, must be greater than or equal to 1
     * @param origin the origin associated with the allocation.  That is, where the entities are trying
     * to get on the conveyor
     * @param allocationName an optional name for the allocation
     * @return an allocation representing that the cells have been allocated to the entity. The reference
     * to this allocation is necessary in order to deallocate the allocated cells.
     */
    fun allocateCells(
        conveyable: Conveyable,
        allocationName: String? = null
    ): ConveyorAllocation {
        require(canAllocateCells(conveyable.numCellsNeeded, conveyable.origin))
        { "Cannot allocate ${conveyable.numCellsNeeded} at origin ${conveyable.origin.name} to the entity." }
        //TODO change the state of the conveyor to account for the allocation
        // allocate cells to the conveyable
        // update the conveyable

        //make the allocation
        val ca = ConveyorAllocation(conveyable, allocationName)
        // attach it to the entity
        conveyable.entity.conveyorAllocation = ca
        // return the allocation
        return ca
    }

    fun deallocateCells(allocation: ConveyorAllocation) {

    }

    //TODO how to stop and start the conveyor?


    inner class Conveyable(
        val entity: ProcessModel.Entity,
        val numCellsNeeded: Int = 1,
        val origin: IdentityIfc,
        val destination: IdentityIfc? = null
    ) : QObject() {
        private val myCellsOccupied: ArrayDeque<Segment.Cell> = ArrayDeque()
        val numCellsOccupied: Int
            get() = myCellsOccupied.size

        val isConveyable: Boolean
            get() = numCellsOccupied == numCellsNeeded

        val conveyor = this@Conveyor

        val segment : Conveyor.Segment?
            get() = if (myCellsOccupied.isNotEmpty()) myCellsOccupied[0].segment else null

        internal fun pushCell(cell: Segment.Cell) {
            if (myCellsOccupied.size < numCellsNeeded) {
                myCellsOccupied.add(cell)
                cell.item = this
            } else {
                popCell()
                myCellsOccupied.add(cell)
                cell.item = this
            }
        }

        internal fun popCell(): Boolean {
            return if (myCellsOccupied.isNotEmpty()) {
                val first = myCellsOccupied.removeFirst()
                first.item = null
                true
            } else {
                false
            }
        }

        //TODO attach the entity, destination, size, current cell, current segment
        // need to know when destination is reached, how about when fully on the conveyor
        // need to know which cells it is currently occupying
    }

    inner class Segment(val segmentData: SegmentData, name: String?) : ModelElement(this@Conveyor, name) {
        /**
         *  This queue holds items that are waiting at the start of the segment for the appropriate number of cells on the
         *  conveyor in order to ride (move) to their destination
         */
        val accessQ = Queue<Conveyable>(this, "${this.name}:AccessQ")

        /**
         *  The total number of cells on this segment of the conveyor
         */
        val numCells: Int = segmentData.length / cellSize
        private val myCells: List<Cell>

        init {
            val list = mutableListOf<Cell>()
            for (i in 1..numCells) {
                list.add(Cell(i))
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
                    if (!cell.occupied) {
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
                cell.item = null
            }
        }

        fun canAllocate(numCellsNeeded: Int): Boolean {
            return numAvailableCells >= numCellsNeeded
        }

        internal fun allocateCells(conveyable: Conveyable) {
            require(canAllocate(conveyable.numCellsNeeded)) { "Tried to allocate cells when insufficient amount of celle was available" }
            for (i in 0 until conveyable.numCellsNeeded) {
                conveyable.pushCell(myCells[i])
            }
        }

        fun conveyItem(item: Conveyable) {
            // enter the accessQ
            accessQ.enqueue(item)
            //TODO if first cell is
        }

        private inner class EndOfCellTraversal : EventAction<Conveyable>() {
            override fun action(event: KSLEvent<Conveyable>) {
                TODO("Not yet implemented")
            }

        }

        inner class Cell(val cellNumber: Int) {
            val occupied: Boolean
                get() = item != null

            var item: Conveyable? = null
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
        private lateinit var segments: Segments

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
            segments = Segments(cellSize, start)
            segments.toLocation(end, length)
            return this
        }

        override fun nextSegment(next: IdentityIfc, length: Int): SegmentStepIfc {
            require(length >= 1) { "The length of the segment must be >= 1 unit" }
            segments.toLocation(next, length)
            return this
        }

        override fun build(): Conveyor {
            return Conveyor(parent, velocity, segments, maxEntityCellsAllowed, name)
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
        sb.appendLine("velocity = $velocity")
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