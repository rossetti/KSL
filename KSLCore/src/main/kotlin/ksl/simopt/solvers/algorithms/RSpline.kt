package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.KSLArrays
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.sortIndices
import kotlin.math.floor

class RSpline(
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(evaluator, maxIterations, replicationsPerEvaluation, streamNum, streamProvider, name) {

    /**
     *  Save the input variable granularity values for repeated processing
     */
    private val granularities = problemDefinition.inputGranularities

    override fun mainIteration() {
        TODO("Not yet implemented")
    }

    private data class PWLFunction(val value: Double, val gradient: DoubleArray?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PWLFunction

            if (value != other.value) return false
            if (!gradient.contentEquals(other.gradient)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = value.hashCode()
            result = 31 * result + (gradient?.contentHashCode() ?: 0)
            return result
        }
    }

//    private class Simplex(dimension: Int) {
//        val vertices: List<DoubleArray>
//        init {
//
//        }
//        val weights: DoubleArray = DoubleArray(vertices.size)
//        fun setVertex(vertex:Int, values: DoubleArray) {
//            for(i in vertices[vertex].indices){
//                vertices[vertex][i] = values[i]
//            }
//        }
//    }

    private fun piecewiseLinearInterpolation(
        point: DoubleArray,
        sampleSize: Int
    ): PWLFunction {
        // hold the vertices
        val vertices = mutableListOf<DoubleArray>()
        // get the granular vertex associated with the general non-granular point
        val x0 = KSLArrays.gRound(point, granularities)
        vertices.add(x0)
        // need to make the d other vertices of the simplex
        // get the fractional part
        val z = DoubleArray(point.size) { point[it] - x0[it] }
        val zSortedIndices = z.sortIndices(descending = true)

        TODO("Not yet implemented")
    }

    private fun constructSimplex(point: DoubleArray) {

    }
}

fun main() {
    val g = doubleArrayOf(1.0, 1.0, 1.0)
    val x = doubleArrayOf(1.8, 2.3, 3.6)
    println("x = ${x.contentToString()}")
    val vertices = mutableListOf<DoubleArray>()
    //val x0 = KSLArrays.gRound(x, g)
    val x0 = DoubleArray(x.size) { floor(x[it]) }
    println("x0 = ${x0.contentToString()}")
    vertices.add(x0)
    val z = DoubleArray(x.size) { x[it] - x0[it] }
    println("z = ${z.contentToString()}")
    val zSortedIndices = z.sortIndices(descending = true)
    println("zSortedIndices = ${zSortedIndices.contentToString()}")
    val e = mutableListOf<DoubleArray>()
    for((index,value) in zSortedIndices.withIndex()) {
        val ei = DoubleArray(z.size)
        ei[zSortedIndices[index]] = 1.0
        println("e$index = ${ei.contentToString()}")
        e.add(ei)
    }
    var np = x0
    for(array in e) {
        val nx = KSLArrays.addElements(np, array)
        np = nx
        vertices.add(nx)
    }
    println()
    for((i, v) in vertices.withIndex()) {
        println("v$i = ${v.contentToString()}")
    }

    println()
    val zList = z.toMutableList()
    zList.add(0, 1.0)
    zList.add(0.0)
    println("zList = ${zList.joinToString()}")
    println()
    for(i in 0..z.size) {
        val w = zList[i] - zList[i+1]
        println("w = $w")
    }
}