package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultGrowthRate
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultMaxNumReplications
import ksl.simopt.solvers.NeighborhoodFinderIfc
import ksl.utilities.KSLArrays
import ksl.utilities.collections.pow
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.sortIndices
import kotlin.math.ceil
import kotlin.math.floor

/**
 *  This class represents an implementation of R-SPLINE from the paper:
 *
 *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
 *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
 *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
 *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
 *  doi: 10.1145/2499913.2499916.
 *
 * @constructor Creates a R-SPLINE solver with the specified parameters.
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param maxIterations The maximum number of iterations allowed for the solving process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name Optional name identifier for this instance of the solver.
 */
class RSpline(
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: FixedGrowthRateReplicationSchedule,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(evaluator, maxIterations, replicationsPerEvaluation, streamNum, streamProvider, name) {

    init {
        require(problemDefinition.isIntegerOrdered) { "R-SPLINE requires that the problem definition be integer ordered!" }
    }

    /**
     *  This class represents an implementation of R-SPLINE from the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @constructor Creates a R-SPLINE solver with the specified parameters.
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param initialNumReps the initial starting number of replications
     * @param maxIterations The maximum number of iterations allowed for the solving process.
     * @param growthRate the growth rate for the replications. The default is set by [defaultGrowthRate].
     * @param maxNumReplications the maximum number of replications permitted. If
     * the growth exceeds this value, then this value is used for all future replications.
     * The default is determined by [defaultMaxNumReplications]
     * @param streamNum the random number stream number, defaults to 0, which means the next stream
     * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
     * @param name Optional name identifier for this instance of the solver.
     */
    constructor(
        evaluator: EvaluatorIfc,
        initialNumReps: Int = defaultInitialSampleSize,
        maxIterations: Int = defaultMaxNumberIterations,
        growthRate: Double = defaultGrowthRate,
        maxNumReplications: Int = defaultMaxNumReplications,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(evaluator, maxIterations, FixedGrowthRateReplicationSchedule(
        initialNumReps, growthRate, maxNumReplications), streamNum, streamProvider, name)

    val fixedGrowthRateReplicationSchedule: FixedGrowthRateReplicationSchedule
        get() = replicationsPerEvaluation as FixedGrowthRateReplicationSchedule

    /**
     *  The amount of the perturbation for Algorithm 4 in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     */
    var perturbation: Double = defaultPerturbation
        set(value) {
            require((0.0 < value) && (value < 1.0)) { "The perturbation factor must be in (0,1)" }
            field = value
        }

    var initialStepSize: Double = 2.0
        set(value) {
            require(value > 1.0) { "The initial step size must be > 1.0" }
            field = value
        }

    var stepSizeMultiplier: Double = 2.0
        set(value) {
            require(value >= 1.0) { "The step multiplier must be >= 1.0" }
            field = value
        }

    var neighborhoodFinder: NeighborhoodFinderIfc = problemDefinition.vonNeumannNeighborhoodFinder()

    var numOracleCalls: Int = 0
        private set

    var splineCallGrowthRate : Double = defaultSplineCallGrowthRate
        set(value) {
            require(value > 0 ) {"The spline growth rate must be > 0"}
            field = value
        }

    var initialMaxSplineCallLimit: Int = defaultInitialMaxSplineCalls
        set(value) {
            require(value > 0) {"The initial maximum number of SPLINE calls must be > 0"}
        }

    var maxSplineCallLimit: Int = defaultMaxSplineCallLimit
        set(value) {
            require(value > 0) {"The maximum for the number of SPLINE call growth limit must be > 0"}
        }

    var currentSplineCallLimit: Int = initialMaxSplineCallLimit
        private set

    val splineCallLimit: Int
        get() {
            val k = iterationCounter
            val m = initialMaxSplineCallLimit * (1.0 + splineCallGrowthRate).pow(k)
            currentSplineCallLimit = minOf(maxSplineCallLimit, ceil(m).toInt())
            return currentSplineCallLimit
        }

    override fun initializeIterations() {
        super.initializeIterations()

    }

    override fun mainIteration() {
        TODO("Not yet implemented")
    }

    /**
     *  This function represents Algorithm 2 SPLINE in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @param initialSolution the initial solution for the search process
     * @param sampleSize the number of replications to be associated with the simulation
     * oracle evaluations associated with the simplex vertices
     * @param splineCallLimit the call limit for the oracle evaluations
     */
    private fun spline(
        initialSolution: Solution,
        sampleSize: Int,
        oracleCallLimit: Int
    ){
        numOracleCalls = 0
        // use the matlab/R code as a guide
        // evaluate the initial point with the new sample size to get the initial solution
        
        for(i in 1..oracleCallLimit){
            // call SPLI
            // call NE
        }
        // check if the starting solution is better than the new solution
        // if the starting solution is still better return it
    }

    /**
     *  This function represents Algorithm 3 Piecewise Linear Interpolation (PLI) in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @param point a non-integer point within the problem space representing the "center"
     * of the interpolation simplex
     * @param sampleSize the number of replications to be associated with the simulation
     * oracle evaluations associated with the simplex vertices
     * @return a pair (Double, DoubleArray?) = (interpolated objective function at the point,
     * the gradient at the point (if available))
     */
    private fun piecewiseLinearInterpolation(
        point: DoubleArray,
        sampleSize: Int
    ): Pair<Double, DoubleArray?> {
        // determine the next simplex based on the supplied point
        val (simplex, sortedIndices) = piecewiseLinearSimplex(point)
        // filter out the infeasible vertices in the simplex
        val feasibleInputs = filterToFeasibleInputs(simplex)
        // the feasible input is mapped to the vertex's weight in the simplex
        if (feasibleInputs.isEmpty()) {
            // no feasible points to evaluate
            return Pair(Double.POSITIVE_INFINITY, null)
        }
        //TODO this needs to be via CRN
        // request evaluations for solutions
        val results = requestEvaluations(feasibleInputs.keys, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned
            return Pair(Double.POSITIVE_INFINITY, null)
        }
        // compute the interpolated objective function value
        var interpolatedObjFnc = 0.0
        var wSum = 0.0
        for (solution in results) {
            val weight = feasibleInputs[solution.inputMap]!!
            wSum = wSum + weight
            interpolatedObjFnc = interpolatedObjFnc + weight * solution.penalizedObjFncValue
        }
        if (wSum <= 0.0) {
            return Pair(Double.POSITIVE_INFINITY, null)
        }
        interpolatedObjFnc = interpolatedObjFnc / wSum
        // The simplex may be missing infeasible vertices. This means that the gradient cannot be computed.
        if (solutions.size < simplex.size) {
            return Pair(interpolatedObjFnc, null)
        }
        // can compute the gradients
        val gradients = DoubleArray(sortedIndices.size)
        for ((i, indexValue) in sortedIndices.withIndex()) {
            gradients[indexValue] = solutions[i].penalizedObjFncValue - solutions[i - 1].penalizedObjFncValue
        }
        return Pair(interpolatedObjFnc, gradients)
    }

    /**
     *  This function represents Algorithm 4 Search Piecewise Linear Interpolation (SPLI) in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @param solution the initial solution for the line search
     * @param sampleSize the number of replications to be associated with the simulation
     * oracle evaluations associated with the simplex vertices
     * @param splineCallLimit the call limit for the oracle evaluations
     * @return the solution found from the search
     */
    private fun searchPiecewiseLinearInterpolation(
        solution: Solution,
        sampleSize: Int,
        splineCallLimit: Int
    ): Solution {
        val x0 = solution.inputMap.inputValues
        //TODO its supposed to return n' as well as the solution

        TODO("Not implemented yet")
    }

    /**
     *  This function filters out the infeasible points from the simplex prior
     *  to evaluation. It also converts the feasible points to InputMap instances
     *  to be used for evaluation. The weight for gradient calculations is
     *  associated with the input via the Map.
     */
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

    /**
     *  This function represents PERTURB function of Algorithm 4 in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @param point the point that needs to be perturbed
     * @return the same point with the added perturbation
     */
    private fun perturb(point: DoubleArray): DoubleArray {
        return addRandomPerturbation(point, perturbation, rnStream)
    }

    /**
     *  Performs the neighborhood search around the provided [solution].
     *  Only feasible neighbors are considered. The best as determined by
     *  the compare() function is returned.
     *
     *  @param solution the solution at the "center" of the neighborhood
     *  @param sampleSize the sample size to use when evaluating the neighborhood solutions
     *  @return the best from the search, which might, in fact, be the provided
     *  solution.
     */
    private fun neighborhoodSearch(
        solution: Solution,
        sampleSize: Int
    ): Solution {
        val neighborHood = neighborhoodFinder.neighborhood(
            solution.inputMap, this
        )
        val feasible = neighborHood.filter { it.isInputFeasible() }.toSet()
        if (feasible.isEmpty()) {
            return solution
        }
        val results = requestEvaluations(feasible, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned
            return solution
        }
        // need to find the best of the results
        val candidate = results.minOf { it }
        return if (compare(solution, candidate) < 0) {
            solution
        } else {
            candidate
        }
    }

    companion object {

        var defaultInitialSampleSize: Int = 8
            set(value) {
                require(value > 0) {"The default initial sample size for replications must be > 0"}
            }

        var defaultPerturbation: Double = 0.15
            set(value) {
                require((0.0 < value) && (value < 1.0)) { "The perturbationFactor must be in (0,1)" }
                field = value
            }

        var defaultSplineCallGrowthRate : Double = 0.1
            set(value) {
                require(value > 0 ) {"The default spline growth rate must be > 0"}
                field = value
            }

        var defaultInitialMaxSplineCalls: Int = 10
            set(value) {
                require(value > 0) {"The default initial maximum number of SPLINE calls must be > 0"}
            }

        var defaultMaxSplineCallLimit: Int = 1000
            set(value) {
                require(value > 0) {"The default maximum for the number of SPLINE call growth limit must be > 0"}
            }

        class SimplexPoint(val vertex: DoubleArray, val weight: Double)

        /**
         * Determines a piecewise-linear simple consisting of d + 1 vertices, where d is
         * the size of the point. The simplex is formed around the supplied point, and the
         * weights are such that the vertices form a convex combination of the vertices who
         * convex hull contains the supplied point
         *
         * @param point a non-integral point around which the simplex is to be formed
         * @return a pair that represents the simplex vertices and their weights with the indices of
         * the sorted fractional parts for the offered point
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

        fun addRandomPerturbation(
            point: DoubleArray,
            perturbationFactor: Double,
            rnStream: RNStreamIfc
        ): DoubleArray {
            require((0.0 < perturbationFactor) && (perturbationFactor < 1.0)) { "The perturbationFactor must be in (0,1)" }
            for (i in point.indices) {
                point[i] = point[i] + rnStream.rUniform(-perturbationFactor, perturbationFactor)
            }
            return point
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