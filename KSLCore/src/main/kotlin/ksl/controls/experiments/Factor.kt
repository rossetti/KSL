package ksl.controls.experiments

import ksl.utilities.*

/**
 *  This class represents an individual factor within a factorial design.
 *  The [name] of the factor must be provided and will be required to be unique
 *  when placed within a design.  The supplied values of the levels must be strictly increasing.
 *  That is, they must be unique and increasing in value.
 *  @param values the value for each level as an array. There must be 2 or more values supplied.
 */
class Factor(
    name: String,
    values: DoubleArray = doubleArrayOf(-1.0, 1.0),
) : Identity(name) {

    /**
     *  The levels as a list
     */
    val levels: List<Double>

    init {
        require(values.size >= 2) { "At least 2 values must be in the array." }
        require(values.isStrictlyIncreasing()) { "The supplied values must be strictly increasing in value" }
        levels = values.asList()
    }

    /**
     *  @param name the name of the factor
     *  @param values a list  2 or more of unique strictly increasing values
     */
    constructor(name: String, values: List<Double>) : this(name, values.toPrimitives())

    /**
     *  @param name the name of the factor
     *  @param values a list 2 or more of unique strictly increasing values
     */
    constructor(name: String, values: IntProgression) : this(name, values.toList().toPrimitives().toDoubles())

    /** Creates a two level factor with provided [low] and [high] values.
     *  @param name the name of the factor
     *  @param low the low value of factor, must be strictly less than the high value
     *  @param high the high value of the factor, must be strictly greater than the low value
     */
    constructor(name: String, low: Double, high: Double) : this(name, doubleArrayOf(low, high))

    val halfRange: Double
        get() = (levels.last() - levels.first()) / 2.0

    val midPoint: Double
        get() = (levels.last() + levels.first()) / 2.0

    val codedLevels: List<Double>
        get() {
            val h = halfRange
            val m = midPoint
            return List(levels.size) { (levels[it] - m) / h }
        }

    fun levels() : DoubleArray {
        return levels.toDoubleArray()
    }

    fun codedLevels() : DoubleArray {
        return codedLevels.toDoubleArray()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("Factor: $name")
        sb.append("Levels = ")
        sb.appendLine(levels.joinToString(","))
        sb.appendLine("halfRange = $halfRange")
        sb.appendLine("midPoint = $midPoint")
        sb.append("Coded Levels = ")
        sb.appendLine(codedLevels.joinToString(","))
        return sb.toString()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Factor

        if (name != other.name) return false
        if (!levels.toPrimitives().contentEquals(other.levels.toPrimitives())) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + levels.toPrimitives().contentHashCode()
        return result
    }

}

