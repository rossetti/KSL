package ksl.modeling.entity

import ksl.utilities.IdentityIfc

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