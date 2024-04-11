package ksl.controls.experiments

import ksl.utilities.Identity
import ksl.utilities.isStrictlyIncreasing
import ksl.utilities.toDoubles
import ksl.utilities.toPrimitives

class Factor(
    name: String,
    values: DoubleArray = doubleArrayOf(-1.0, 1.0),
) : Identity(name) {

    val levels: List<Double>

    init {
        require(values.size >= 2) { "At least 2 values must be in the array." }
        require(values.isStrictlyIncreasing()) { "The supplied values must be strictly increasing in value" }
        levels = values.asList()
    }

    constructor(name: String, values: IntProgression) : this(name, values.toList().toPrimitives().toDoubles())

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

}

fun main(){
    val f = Factor("A", doubleArrayOf(5.0, 10.0, 15.0, 20.0, 25.0))
    println(f)
    val g = Factor("G", 5..25 step 5)
    println(g)
    val x = Factor("X")
    println(x)
}