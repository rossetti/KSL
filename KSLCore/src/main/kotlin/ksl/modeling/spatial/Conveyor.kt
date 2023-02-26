package ksl.modeling.spatial

import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import kotlin.properties.Delegates


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
    private var lastLoc: IdentityIfc = firstLocation
    val lastLocation: IdentityIfc
        get() = lastLoc

    val segments: List<SegmentData>
        get() = mySegments

    fun toLocation(next: IdentityIfc, length: Int) {
        require(length >= 1) { "The length ($length) of the segment must be >= 1 unit" }
        require(next != lastLoc) {"The next location (${next.name}) as the last location (${lastLoc.name})"}
        require(length % cellSize == 0) { "The length of the segment ($length) was not an integer multiple of the cell size ($cellSize)" }
        mySegments.add(SegmentData(lastLoc, next, length))
        lastLoc = next
    }

    val totalLength: Int
        get() {
            var sum = 0
            for(seg in mySegments){
                sum = sum + seg.length
            }
            return sum
        }

    fun isEmpty(): Boolean {
        return mySegments.isEmpty() || lastLoc == firstLocation
    }

    fun isNotEmpty() = !isEmpty()

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("first location = ${firstLocation.name}")
        sb.appendLine("last location = ${lastLocation.name}")
        for((i, segment) in mySegments.withIndex()){
            sb.appendLine("Segment: ${(i+1)} = $segment")
        }
        sb.appendLine("total length = $totalLength")
        return sb.toString()
    }
}

class Conveyor(
    parent: ModelElement,
    val velocity: Double = 1.0,
    private val segments: Segments,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(velocity > 0.0) { "The velocity of the conveyor must be > 0.0" }
        require(segments.isNotEmpty()) { "The segment data must not be empty." }
    }

    val cellSize = segments.cellSize

    val cellTravelTime: Double = cellSize / velocity



    inner class Segment(val start: IdentityIfc, val end: IdentityIfc, val length: Int) {
        init {
            require(length >= 1) { "The length of the segment must be >= 1 unit" }
            require(length % cellSize == 0)
        }
    }

    companion object {
        fun builder(parent: ModelElement, name: String? = null): VelocityStepIfc {
            return Builder(parent, name)
        }
    }

    private class Builder(val parent: ModelElement, val name: String? = null) : VelocityStepIfc, CellSizeStepIfc,
        FirstSegmentStepIfc, SegmentStepIfc {
        var velocity: Double by Delegates.notNull()
        var cellSize: Int by Delegates.notNull()
        lateinit var segments: Segments

        override fun velocity(value: Double): CellSizeStepIfc {
            require(value > 0.0) { "The velocity of the conveyor must be > 0.0" }
            velocity = value
            return this
        }

        override fun cellSize(value: Int): FirstSegmentStepIfc {
            cellSize = value
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
            return Conveyor(parent, velocity, segments, name)
        }

    }

    interface VelocityStepIfc {
        fun velocity(value: Double): CellSizeStepIfc
    }

    interface CellSizeStepIfc {
        fun cellSize(value: Int): FirstSegmentStepIfc
    }

    interface FirstSegmentStepIfc {
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
        sb.append(segments)
        return sb.toString()
    }
}

fun main(){
    val i1 = Identity()
    val i2 = Identity()
    val i3 = Identity()
    val c = Conveyor.builder(Model())
        .velocity(3.0)
        .cellSize(1)
        .firstSegment(i1, i2, 10)
        .nextSegment(i3, 20).build()

    println(c)

}