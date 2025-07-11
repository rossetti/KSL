package ksl.modeling.entity


/**
 * This class represents the data associated with segments of a conveyor and facilitates
 * the specification of segments for a conveyor.  See the class Conveyor for more
 * details on how segments are used to represent a conveyor.
 */
class ConveyorSegments(val cellSize: Int = 1, val firstLocation: String) {
    private val mySegments = mutableListOf<Segment>()
    private val myDownStreamLocations: MutableMap<String, MutableList<String>> = mutableMapOf()

    var lastLocation: String = firstLocation
        private set
    var minimumSegmentLength = Integer.MAX_VALUE
        private set
    val segments: List<Segment>
        get() = mySegments

    fun toLocation(next: String, length: Int) {
        require(length >= 1) { "The length ($length) of the segment must be >= 1 unit" }
        require(next != lastLocation) { "The next location (${next}) as the last location (${lastLocation})" }
        require(length % cellSize == 0) { "The length of the segment ($length) was not an integer multiple of the cell size ($cellSize)" }
        val numCells = length / cellSize
        require(numCells >= 2) { "There must be at least 2 cells on each segment: length = $length, cellSize = $cellSize, results in $numCells cells" }
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

    val entryLocations: List<String>
        get() {
            val list = mutableListOf<String>()
            for (seg in mySegments) {
                list.add(seg.entryLocation)
            }
            return list
        }

    val exitLocations: List<String>
        get() {
            val list = mutableListOf<String>()
            for (seg in mySegments) {
                list.add(seg.exitLocation)
            }
            return list
        }

    val isCircular: Boolean
        get() = firstLocation == lastLocation

    fun isReachable(start: String, end: String): Boolean {
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
        sb.appendLine("first location = ${firstLocation}")
        sb.appendLine("last location = ${lastLocation}")
        for ((i, segment) in mySegments.withIndex()) {
            sb.appendLine("Segment: ${(i + 1)} = $segment")
        }
        sb.appendLine("total length = $totalLength")
        sb.appendLine("Downstream locations:")
        for ((loc, list) in myDownStreamLocations) {
            sb.appendLine(
                "${loc} : ${
                    list.joinToString(
                        separator = " -> ",
                        prefix = "[",
                        postfix = "]",
                        transform = { it})
                }"
            )
        }
        return sb.toString()
    }
}