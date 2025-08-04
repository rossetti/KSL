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
import ksl.utilities.direction
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
    ) : this(
        evaluator, maxIterations, FixedGrowthRateReplicationSchedule(
            initialNumReps, growthRate, maxNumReplications
        ), streamNum, streamProvider, name
    )

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

    var splineCallGrowthRate: Double = defaultSplineCallGrowthRate
        set(value) {
            require(value > 0) { "The spline growth rate must be > 0" }
            field = value
        }

    var initialMaxSplineCallLimit: Int = defaultInitialMaxSplineCalls
        set(value) {
            require(value > 0) { "The initial maximum number of SPLINE calls must be > 0" }
        }

    var maxSplineCallLimit: Int = defaultMaxSplineCallLimit
        set(value) {
            require(value > 0) { "The maximum for the number of SPLINE call growth limit must be > 0" }
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

    var splineIterMax = defaultSplineIterMax
        set(value) {
            require(value > 0) { "The maximum number of spline iterations must be > 0" }
        }

    var lineSearchIterMax = defaultLineSearchIterMax
        set(value) {
            require(value > 0) { "The maximum number of spline line search iterations must be > 0" }
        }

    val currenSampleSize: Int
        get() = fixedGrowthRateReplicationSchedule.currentNumReplications

    private val badSolution = problemDefinition.badSolution()

    /**
     *  The default implementation ensures that the initial point and solution
     *  are input-feasible (feasible with respect to input ranges and deterministic constraints).
     */
    override fun initializeIterations() {
        super.initializeIterations()
        numOracleCalls = 0
    }

    /**
     *  This function represents Algorithm 1: R-SPLINE in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     *  The current sample [currenSampleSize] size upon initialization is the initial sample
     *  size of the replication schedule (m_k). The current spline call limit (b_k)
     *  is determined by the property [currentSplineCallLimit].
     *  If the kth sample path problem beats the previous sample path problem,
     *  then the current solution is updated. The initial solution is determined
     *  randomly when the solver is initialized.
     */
    override fun mainIteration() {
        // The initial (current) solution is randomly selected or specified by the user.
        // It will be the current solution until beaten by the SPLINE search process.
        // Call SPLINE for the next solution using the current sample size (m_k) and
        // current SPLINE oracle call limit (b_k).
        val splineSolution = spline(
            currentSolution,
            currenSampleSize, currentSplineCallLimit
        )
        // keep track of the total number of oracle calls
        numOracleCalls = numOracleCalls + splineSolution.numOracleCalls
        if (compare(splineSolution.solution, currentSolution) < 0) {
            currentSolution = splineSolution.solution
        }
        //TODO what if sequential SPLINE search returns the same solution?
        //TODO need to incorporate number of oracle calls into stopping criteria
    }

    /**
     *  This function represents Algorithm 2: SPLINE in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * This implementation ensures that the returned solution will be input-feasible.
     * If the point found from the SPLINE search is not better than the initial
     * starting point or is input-infeasible, then the initial point is returned.
     *
     * @param initSolution the initial solution for the search process
     * @param sampleSize the number of replications to be associated with the simulation
     * oracle evaluations associated with the simplex vertices
     * @param splineCallLimit the call limit for the oracle evaluations
     * @return a pair (Int, Solution) = (number of oracle calls executed, spline solution)
     */
    private fun spline(
        initSolution: Solution,
        sampleSize: Int,
        oracleCallLimit: Int
    ): SPLINESolution {
        // The SPLINE search starts with a feasible point.
        require(initSolution.isInputFeasible()) { "The initial solution to the SPLINE function must be input feasible!" }
        var splineOracleCalls = 0
        // This implementation is based in part on available matlab/R code as a guide.
        // Evaluate the initial point with the new sample size to get the starting solution
        val initialInputs = initSolution.inputMap
        val startingSolution = requestEvaluation(initialInputs, sampleSize)
        // use the starting solution as the new solution for the line search (SPLI)
        var newSolution = startingSolution
        for (i in 1..oracleCallLimit) {
            // perform the line search to get a solution based on the new solution
            val spliResults = searchPiecewiseLinearInterpolation(
                newSolution, sampleSize, oracleCallLimit
            )
            // search the neighborhood starting from the SPLI solution
            val (neCalls, neSolution) = neighborhoodSearch(spliResults.solution, sampleSize)
            splineOracleCalls = splineOracleCalls + spliResults.numOracleCalls + neCalls
            // use the neighborhood search to seed the next SPLI search
            newSolution = neSolution
            // if the line search and the neighborhood search results are the same, we can stop
            //TODO matlab and R code used some kind of tolerance when testing equality
            if (compare(spliResults.solution, neSolution) == 0) {
                break
            }
        }
        // Check if the starting solution is better than the solution from the SPLINE search.
        // If the starting solution is still better return it. The starting solution
        // must be input-feasible.
        //TODO matlab and R code used some kind of tolerance when testing equality
        return if (compare(startingSolution, newSolution) < 0) {
            SPLINESolution(startingSolution, splineOracleCalls)
        } else {
            // The new solution might be better, but it might be input-infeasible.
            if (newSolution.isInputFeasible()) {
                SPLINESolution(newSolution, splineOracleCalls)
            } else {
                // not feasible, go back to the last feasible solution
                SPLINESolution(startingSolution, splineOracleCalls)
            }
        }
    }

    class SPLINESolution(val solution: Solution, val numOracleCalls: Int)

    /**
     *  This function represents Algorithm 3: Piecewise Linear Interpolation (PLI) in the paper:
     *
     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
     *  doi: 10.1145/2499913.2499916.
     *
     * @param solution a seeding solution for the piecewise linear interpolation. This
     * solution should be at an input value around which the interpolation will occur.
     * @param sampleSize the number of replications to be associated with the simulation
     * oracle evaluations associated with the simplex vertices
     * @return the results of the piecewise linear interpolation as a [PLIResults] instance
     */
    private fun piecewiseLinearInterpolation(
        solution: Solution,
        sampleSize: Int
    ): PLIResults {
        // get the point to be associated with the center of the simplex
        val x = solution.inputMap.inputValues
        // perturb the point
        val point = perturb(x)
        // determine the next simplex based on the perturbed point
        val simplexData = piecewiseLinearSimplex(point)
        // filter out the infeasible vertices in the simplex
        val feasibleInputs = filterToFeasibleInputs(simplexData.simplexPoints)
        // the feasible input is mapped to the vertex's weight in the simplex
        if (feasibleInputs.isEmpty()) {
            // no feasible points to evaluate
            return PLIResults(numOracleCalls = 0, gradients = null, solution = badSolution)
        }
        //TODO The request for evaluation of the simplex vertices should use CRN
        val results = requestEvaluations(feasibleInputs.keys, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned. We assume that no oracle evaluations happened, even if they did.
            return PLIResults(numOracleCalls = 0, gradients = null, solution = badSolution)
        }
        // There must be new results available for some simplex vertices.
        // Find the best of the simplex vertices.
        val resultsBest = results.minOf { it }
        // Determine if the best solution should be updated. If tied, prefer the solution with more oracle evaluations.
        val bestSolution = minimumSolution(resultsBest, solution)
        // The simplex results may be missing infeasible vertices.
        if (results.size < simplexData.vertices.size) {
            // The simplex has infeasible vertices. Return the current best solution without the gradients.
            return PLIResults(numOracleCalls = results.size * sampleSize, gradients = null, solution = bestSolution)
        }
        // The full simplex has been evaluated. Thus, the gradients can be computed.
        val gradients = DoubleArray(simplexData.sortedFractionIndices.size)
        for ((i, indexValue) in simplexData.sortedFractionIndices.withIndex()) {
            gradients[indexValue] = results[i].penalizedObjFncValue - results[i - 1].penalizedObjFncValue
        }
        // Return the current best solution along with the computed gradients.
        return PLIResults(numOracleCalls = results.size * sampleSize, gradients = gradients, solution = bestSolution)
    }

    class PLIResults(
        val numOracleCalls: Int,
        val gradients: DoubleArray? = null, // gradient size is d, one for each input variable
        val solution: Solution
    )

    class SPLIResults(
        val numOracleCalls: Int,
        val solution: Solution
    )

    /**
     *  This function represents Algorithm 4: Search Piecewise Linear Interpolation (SPLI) in the paper:
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
     * @return a pair (Int, Solution) = (number of oracle calls executed, spline solution)
     */
    private fun searchPiecewiseLinearInterpolation(
        solution: Solution,
        sampleSize: Int,
        splineCallLimit: Int
    ): SPLIResults {
        //Set X_best = x_0 and n′ = 0
        var bestSoln = solution
        var numOracleCalls = 0
        for (j in 1..splineIterMax) {
            // Call PLI(x1, mk) to observe gmk (x1) and (possibly) gradient
            val pliResults = piecewiseLinearInterpolation(bestSoln, sampleSize)
            // update the best solution if PLI found a better solution
            bestSoln = minimumSolution(pliResults.solution, bestSoln)
            numOracleCalls = numOracleCalls + pliResults.numOracleCalls
            if (pliResults.gradients == null) {
                // Stop if no direction
                return SPLIResults(numOracleCalls, bestSoln)
            }
            if (numOracleCalls > splineCallLimit) {
                // Stop if too many oracle calls
                return SPLIResults(numOracleCalls, bestSoln)
            }
            // Setup to do the line search
            val s0 = initialStepSize
            val c = stepSizeMultiplier
            val d = pliResults.gradients.direction()
            val x0 = bestSoln.inputMap.inputValues
            // The matlab/R code has a limit on the number of line searches.
            for (i in 1..lineSearchIterMax) {
                val s = s0 * c.pow(i - 1) // step-size
                val sd = KSLArrays.multiplyConstant(d, s) //step-array
                val x1 = KSLArrays.subtractElements(x0, sd)
                // This will shift x1 to the nearest integer point.
                val inputs = problemDefinition.toInputMap(x1)
                if (!inputs.isInputFeasible()){
                    return SPLIResults(numOracleCalls, bestSoln)
                }
                val x1Solution = requestEvaluation(inputs, sampleSize)
                numOracleCalls = numOracleCalls + sampleSize
                bestSoln = minimumSolution(x1Solution, bestSoln)
                //TODO check conditions
            }
            //TODO check for short line search
        }
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
     *  @return a pair (Int, Solution) = (number of oracle calls executed, neighborhood solution),
     *  the best from the search, which might, in fact, be the provided solution.
     */
    private fun neighborhoodSearch(
        solution: Solution,
        sampleSize: Int
    ): Pair<Int, Solution> {
        require(solution.isInputFeasible()) { "The initial solution to the SPLINE function must be input feasible!" }
        // By construction, the neighborhood does not contain the center (initial) solution.
        // It has already been evaluated by the oracle.
        val neighborHood = neighborhoodFinder.neighborhood(
            solution.inputMap, this
        )
        // Perform search only on feasible points in the neighborhood.
        val feasible = neighborHood.filter { it.isInputFeasible() }.toSet()
        if (feasible.isEmpty()) {
            // No feasible points in the neighborhood. Return the starting point.
            return Pair(0, solution)
        }
        // Evaluate the feasible points in the neighborhood.
        val results = requestEvaluations(feasible, sampleSize)
        // Something could have gone wrong with the oracle processing.
        if (results.isEmpty()) {
            // No solutions returned. Return the starting point. Assume no oracle evaluations.
            return Pair(0, solution)
        }
        // need to find the best of the results
        val candidate = results.minOf { it }
        //TODO matlab and R code uses a tolerance for comparison
        return if (compare(solution, candidate) < 0) {
            Pair(results.size * sampleSize, solution)
        } else {
            Pair(results.size * sampleSize, candidate)
        }
    }

    companion object {

        var defaultSplineIterMax = 100
            set(value) {
                require(value > 0) { "The default maximum number of spline iterations must be > 0" }
            }

        var defaultLineSearchIterMax = 5
            set(value) {
                require(value > 0) { "The default maximum number of spline line search iterations must be > 0" }
            }

        var defaultInitialSampleSize: Int = 8
            set(value) {
                require(value > 0) { "The default initial sample size for replications must be > 0" }
            }

        var defaultPerturbation: Double = 0.15
            set(value) {
                require((0.0 < value) && (value < 1.0)) { "The perturbationFactor must be in (0,1)" }
                field = value
            }

        var defaultSplineCallGrowthRate: Double = 0.1
            set(value) {
                require(value > 0) { "The default spline growth rate must be > 0" }
                field = value
            }

        var defaultInitialMaxSplineCalls: Int = 10
            set(value) {
                require(value > 0) { "The default initial maximum number of SPLINE calls must be > 0" }
            }

        var defaultMaxSplineCallLimit: Int = 1000
            set(value) {
                require(value > 0) { "The default maximum for the number of SPLINE call growth limit must be > 0" }
            }

        class SimplexPoint(val vertex: DoubleArray, val weight: Double)

        class SimplexData(
            val originalPoint: DoubleArray,
            val fractionalParts: DoubleArray,
            val sortedFractionIndices: IntArray,
            val sortedFractions: List<Double>,
            val vertices: List<DoubleArray>,
            val weights: DoubleArray,
        ) {
            val simplexPoints: List<SimplexPoint>
                get() {
                    val list = mutableListOf<SimplexPoint>()
                    for ((i, v) in vertices.withIndex()) {
                        list.add(SimplexPoint(v, weights[i]))
                    }
                    return list
                }

            override fun toString(): String {
                val sb = StringBuilder().apply {
                    appendLine("original point = ${originalPoint.joinToString()}")
                    appendLine("fractional parts = ${fractionalParts.joinToString()}")
                    appendLine("sorted Fraction Indices = ${sortedFractionIndices.joinToString()}")
                    appendLine("sortedFractions = ${sortedFractions.joinToString()}")
                    appendLine("Vertices")
                    for ((i, v) in vertices.withIndex()) {
                        appendLine("vertex[$i] = ${v.joinToString()} \t weight = ${weights[i]}")
                    }
                }
                return sb.toString()
            }
        }

        /**
         * Determines a piecewise-linear simple consisting of d + 1 vertices, where d is
         * the size of the point. The simplex is formed around the supplied point, and the
         * weights are such that the vertices form a convex combination of the vertices where the
         * convex hull contains the supplied point.
         *
         * @param point a non-integral point around which the simplex is to be formed
         * @return the data associated with the simplex
         */
        fun piecewiseLinearSimplex(point: DoubleArray): SimplexData {
            require(point.isNotEmpty()) { "The point must not be empty!" }
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
            return SimplexData(
                originalPoint = point,
                fractionalParts = z,
                sortedFractionIndices = zSortedIndices,
                sortedFractions = zList,
                vertices = vertices,
                weights = w
            )
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
    val simpleData = RSpline.piecewiseLinearSimplex(x)
    println(simpleData)
}

/*
x = [1.8, 2.3, 3.6]
original point = 1.8, 2.3, 3.6
fractional parts = 0.8, 0.2999999999999998, 0.6000000000000001
sorted Fraction Indices = 0, 2, 1
sortedFractions = 1.0, 0.8, 0.6000000000000001, 0.2999999999999998, 0.0
Vertices
vertex[0] = 1.0, 2.0, 3.0 	 weight = 0.19999999999999996
vertex[1] = 2.0, 2.0, 3.0 	 weight = 0.19999999999999996
vertex[2] = 2.0, 2.0, 4.0 	 weight = 0.30000000000000027
vertex[3] = 2.0, 3.0, 4.0 	 weight = 0.2999999999999998
*/