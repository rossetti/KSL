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
     *  @param values a list of unique strictly increasing values
     */
    constructor(name: String, values: List<Double>) : this(name, values.toPrimitives())

    /**
     *  @param name the name of the factor
     *  @param values a list of unique strictly increasing values
     */
    constructor(name: String, values: IntProgression) : this(name, values.toList().toPrimitives().toDoubles())

    /** Creates a two level factor with provided [low] and [high] values.
     *  @param name the name of the factor
     *  @param low the low value of factor, must be strictly less than the high value
     *  @param high the high value of the factor, must be strictly greater than the low value
     */
    constructor(name: String, low: Double, high: Double) : this(name, doubleArrayOf())

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

fun main() {
//    testCP()
//    println()
//    testCPRow()

    testFactor()
}

fun testFactor() {
    val f = Factor("A", doubleArrayOf(5.0, 10.0, 15.0, 20.0, 25.0))
    println(f)
    val g = Factor("G", 5..25 step 5)
    println(g)
    val x = Factor("X")
    println(x)
}

fun testCP() {
    val a = setOf(1, 2)
    val b = setOf(3, 4)
    val c = setOf(5)
    val d = setOf(6, 7, 8)

    val abcd = KSLArrays.cartesianProduct(a, b, c, d)

    println(abcd)
    println()

    val s1 = setOf(1.0, 2.0)
    val s2 = setOf(3.0, 4.0)
    val s3 = setOf(5.0)
    val s4 = setOf(6.0, 7.0, 8.0)
    val s1s2s3s4 = KSLArrays.cartesianProductOfDoubles(s1, s2, s3, s4)
    println()
    for ((i, s) in s1s2s3s4.withIndex()) {
        println("The element at index $i is: ${s1s2s3s4[i].joinToString()}")
    }
}

fun testCPRow() {
    val a = intArrayOf(1, 2)
    val b = intArrayOf(3, 4)
    val c = intArrayOf(5)
    val d = intArrayOf(6, 7, 8)
    val n = a.size * b.size * c.size * d.size
    val array = arrayOf(a, b, c, d)

    println()
    val index = 4
    val r = KSLArrays.cartesianProductRow(array, index)
    println("The element at index $index is: ${r.joinToString()}")

    println()
    println("Elements via indexed rows:")
    for (i in 0..<n) {
        val result = KSLArrays.cartesianProductRow(array, i)
        println("The element at index $i is: ${result.joinToString()}")
    }
    println()
}