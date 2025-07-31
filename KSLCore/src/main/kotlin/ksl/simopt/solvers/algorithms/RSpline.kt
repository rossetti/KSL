package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
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

    init {
        require(problemDefinition.isIntegerOrdered) { "R-SPLINE requires that the problem definition be integer ordered!" }
    }

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

    private fun piecewiseLinearInterpolation(
        point: DoubleArray,
        sampleSize: Int
    ): PWLFunction {
        // determine the next simplex
        val (simplex, sortedIndices) = piecewiseLinearSimplex(point)
        // filter out the infeasible vertices in the simplex
        val feasibleInputs = filterToFeasibleInputs(simplex)
        // the feasible input is mapped to the vertex's weight in the simplex
        if (feasibleInputs.isEmpty()) {
            // no feasible points to evaluate
            return PWLFunction(Double.POSITIVE_INFINITY, null)
        }
        //TODO this needs to be via CRN and use the specified sample size
        // request evaluations for solutions
        val results = requestEvaluations(feasibleInputs.keys, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned
            return PWLFunction(Double.POSITIVE_INFINITY, null)
        }
        // compute the interpolated objective function value
        var interpolatedObjFnc = 0.0
        var wSum = 0.0
        for (solution in results) {
            val weight = feasibleInputs[solution.inputMap]!!
            wSum = wSum + weight
            interpolatedObjFnc = interpolatedObjFnc + weight * solution.penalizedObjFncValue
        }
        if (wSum <= 0.0){
            return PWLFunction(Double.POSITIVE_INFINITY, null)
        }
        interpolatedObjFnc = interpolatedObjFnc/wSum
        // The simplex may be missing infeasible vertices. This means that the gradient cannot be computed.
        if (solutions.size < simplex.size){
            return PWLFunction(interpolatedObjFnc, null)
        }
        // can compute the gradients
        val gradients = DoubleArray(sortedIndices.size)
        for((i, indexValue) in sortedIndices.withIndex()){
            gradients[indexValue] = solutions[i].penalizedObjFncValue - solutions[i-1].penalizedObjFncValue
        }
        return PWLFunction(interpolatedObjFnc, gradients)
    }

    private fun filterToFeasibleInputs(simplex: List<SimplexPoint>): Map<InputMap, Double> {
        val map = mutableMapOf<InputMap, Double>()
        for (point in simplex) {
            if (problemDefinition.isInputFeasible(point.vertex)) {
                val input = problemDefinition.toInputMap(point.vertex)
                map[input] = point.weight
            }
        }
        return map
    }

    companion object {

        class SimplexPoint(val vertex: DoubleArray, val weight: Double)

        /**
         * Determines a piecewise-linear simple consisting of d + 1 vertices, where d is
         * the size of the point. The simplex is formed around the supplied point, and the
         * weights are such that the vertices form a convex combination of the vertices who
         * convex hull contains the supplied point
         *
         * @param point a non-integral point around which the simplex is to be formed
         * @return a pair (List<DoubleArray>, DoubleArray) that represent the simplex and the weights
         */
        fun piecewiseLinearSimplex(point: DoubleArray): Pair<List<SimplexPoint>, IntArray> {
            require(point.isNotEmpty()) { "The points must not be empty!" }
            // vertices will hold the vertices of the simplex
            val vertices = mutableListOf<DoubleArray>()
            // Get the first vertex by taking the integer floor of the offered point
            val x0 = DoubleArray(point.size) { floor(point[it]) }
            vertices.add(x0)
            // compute the fractional parts of the offered point
            val z = DoubleArray(point.size) { point[it] - x0[it] }
            // get the ordered indices to form the convex hull
            val zSortedIndices = z.sortIndices(descending = true)
            // the list of arrays holds the unit vectors that are used to form the vertices
            val e = mutableListOf<DoubleArray>()
            for ((index, _) in zSortedIndices.withIndex()) {
                val ei = DoubleArray(z.size)
                // assign 1 according to the next largest fractional part
                ei[zSortedIndices[index]] = 1.0
                e.add(ei)
            }
            // construct the vertices by adding 1 to the component with the next
            // largest fractional part
            var np = x0
            for (array in e) {
                val nx = KSLArrays.addElements(np, array)
                np = nx
                vertices.add(nx)
            }
            // the vertices are now constructed, compute the weights
            // augment the fractional array with 1 and 0
            val zList = mutableListOf<Double>()
            zList.add(0, 1.0)
            for (index in zSortedIndices) {
                zList.add(z[index])
            }
            zList.add(0.0)
            // compute the weights, one for each vertex
            val w = DoubleArray(z.size + 1) { 0.0 }
            for (i in 0..z.size) {
                w[i] = zList[i] - zList[i + 1]
            }
            val simplex = mutableListOf<SimplexPoint>()
            for ((i, vertex) in vertices.withIndex()) {
                simplex.add(SimplexPoint(vertex, w[i]))
            }
            return Pair(simplex, zSortedIndices)
        }
    }

}

fun main() {
    val x = doubleArrayOf(1.8, 2.3, 3.6)
    println("x = ${x.contentToString()}")
    val (simplex, sortedIndices) = RSpline.piecewiseLinearSimplex(x)
    for ((i, point) in simplex.withIndex()) {
        println("w[$i] = ${point.weight} vertex = ${point.vertex.contentToString()}")
    }
}

fun tempTesting() {
    val x = doubleArrayOf(1.8, 2.3, 3.6)
    println("x = ${x.contentToString()}")
    val vertices = mutableListOf<DoubleArray>()
//    val x0 = KSLArrays.gRound(x, g)
    val x0 = DoubleArray(x.size) { floor(x[it]) }
    println("x0 = ${x0.contentToString()}")
    vertices.add(x0)
    val z = DoubleArray(x.size) { x[it] - x0[it] }
    println("z = ${z.contentToString()}")
    val zSortedIndices = z.sortIndices(descending = true)
    println("zSortedIndices = ${zSortedIndices.contentToString()}")
    val e = mutableListOf<DoubleArray>()
    for ((index, _) in zSortedIndices.withIndex()) {
        val ei = DoubleArray(z.size)
        ei[zSortedIndices[index]] = 1.0
        println("e$index = ${ei.contentToString()}")
        e.add(ei)
    }
    var np = x0
    for (array in e) {
        val nx = KSLArrays.addElements(np, array)
        np = nx
        vertices.add(nx)
    }
    println()
    for ((i, v) in vertices.withIndex()) {
        println("v$i = ${v.contentToString()}")
    }

    println()
    val zList = z.toMutableList()
    zList.add(0, 1.0)
    zList.add(0.0)
    println("zList = ${zList.joinToString()}")
    println()
    val w = DoubleArray(z.size + 1) { 0.0 }
    for (i in 0..z.size) {
        w[i] = zList[i] - zList[i + 1]
        println("w[$i] = ${w[i]}")
    }
}
/*
x = [1.8, 2.3, 3.6]
x0 = [1.0, 2.0, 3.0]
z = [0.8, 0.2999999999999998, 0.6000000000000001]
zSortedIndices = [0, 2, 1]
e0 = [1.0, 0.0, 0.0]
e1 = [0.0, 0.0, 1.0]
e2 = [0.0, 1.0, 0.0]

v0 = [1.0, 2.0, 3.0]
v1 = [2.0, 2.0, 3.0]
v2 = [2.0, 2.0, 4.0]
v3 = [2.0, 3.0, 4.0]

zList = 1.0, 0.8, 0.2999999999999998, 0.6000000000000001, 0.0

w = 0.19999999999999996
w = 0.5000000000000002
w = -0.30000000000000027
w = 0.6000000000000001
 */