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
class RSplineSolver(
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
     * @param sampleSizeGrowthRate the growth rate for the replications. The default is set by [defaultGrowthRate].
     * @param maxNumReplications the maximum number of replications permitted. If
     * the growth exceeds this value, then this value is used for all future replications.
     * The default is determined by [defaultMaxNumReplications]
     * @param streamNum the random number stream number, defaults to 0, which means the next stream
     * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
     * @param name Optional name identifier for this instance of the solver.
     */
    @Suppress("unused")
    constructor(
        evaluator: EvaluatorIfc,
        maxIterations: Int = defaultMaxNumberIterations,
        initialNumReps: Int = defaultInitialSampleSize,
        sampleSizeGrowthRate: Double = defaultGrowthRate,
        maxNumReplications: Int = defaultMaxNumReplications,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(
        evaluator, maxIterations, FixedGrowthRateReplicationSchedule(
            initialNumReps, sampleSizeGrowthRate, maxNumReplications
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

    var splineOracleCallGrowthRate: Double = defaultSplineCallGrowthRate
        set(value) {
            require(value > 0) { "The spline growth rate must be > 0" }
            field = value
        }

    var initialMaxSplineCallLimit: Int = defaultInitialMaxSplineCalls
        set(value) {
            require(value > 0) { "The initial maximum number of SPLINE calls must be > 0" }
        }

    var maxSplineOracleCallLimit: Int = defaultMaxSplineCallLimit
        set(value) {
            require(value > 0) { "The maximum for the number of SPLINE call growth limit must be > 0" }
        }

    val splineOracleCallLimit: Int
        get() {
            val k = iterationCounter
            if (iterationCounter == 1){
                return initialMaxSplineCallLimit
            }
            val m = initialMaxSplineCallLimit * (1.0 + splineOracleCallGrowthRate).pow(k)
            return minOf(maxSplineOracleCallLimit, ceil(m).toInt())
        }

    var lineSearchIterMax = defaultLineSearchIterMax
        set(value) {
            require(value > 0) { "The maximum number of spline line search iterations must be > 0" }
        }

    val rsplineSampleSize: Int
        get() = fixedGrowthRateReplicationSchedule.numReplicationsPerEvaluation(this)

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
     *  The current sample [rsplineSampleSize] size upon initialization is the initial sample
     *  size of the replication schedule (m_k). The current spline call limit (b_k)
     *  is determined by the property [splineOracleCallLimit].
     *  If the kth sample path problem beats the previous sample path problem,
     *  then the current solution is updated. The initial solution is determined
     *  randomly when the solver is initialized.
     */
    override fun mainIteration() {
        // The initial (current) solution is randomly selected or specified by the user.
        // It will be the current solution until beaten by the SPLINE search process.
        // Call SPLINE for the next solution using the current sample size (m_k) and
        // current SPLINE oracle call limit (b_k).

        logger.info {"SPLINE search: main iteration = $iterationCounter : sample size = $rsplineSampleSize : oracle call limit = $splineOracleCallLimit"}
        val splineSolution = spline(
            currentSolution,
            rsplineSampleSize, splineOracleCallLimit
        )
        // keep track of the total number of oracle calls
        numOracleCalls = numOracleCalls + splineSolution.numOracleCalls
        logger.info {"SPLINE search: main iteration = $iterationCounter : numOracleCalls = $numOracleCalls"}
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
     * @param oracleCallLimit the call limit for the oracle evaluations
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
        logger.info {"spline(): Evaluated the initial point with the new sample size to get the starting solution"}
        // use the starting solution as the new solution for the line search (SPLI)
        var newSolution = startingSolution
        for (i in 1..oracleCallLimit) {
            // perform the line search to get a solution based on the new solution
            logger.info {"SPLI search: iteration: $i : sample size = $sampleSize : spline call limit = $oracleCallLimit"}
            val spliResults = searchPiecewiseLinearInterpolation(
                newSolution, sampleSize, oracleCallLimit)
            // search the neighborhood starting from the SPLI solution
            logger.info {"NE search: iteration: $i : sample size = $sampleSize"}
            val neSearchResults = neighborhoodSearch(spliResults.solution, sampleSize)
            splineOracleCalls = splineOracleCalls + spliResults.numOracleCalls + neSearchResults.numOracleCalls
            logger.info {"SPLINE search: iteration: $i : splineOracleCalls = $splineOracleCalls"}
            // use the neighborhood search to seed the next SPLI search
            newSolution = neSearchResults.solution
            // if the line search and the neighborhood search results are the same, we can stop
            //TODO matlab and R code used some kind of tolerance when testing equality
            if (compare(spliResults.solution, neSearchResults.solution) == 0) {
                logger.info {"SPLINE search: iteration: $i : break loop : SPLI solution is same as NE solution"}
                break
            }
        }
        // Check if the starting solution is better than the solution from the SPLINE search.
        // If the starting solution is still better return it. The starting solution
        // must be input-feasible.
        //TODO matlab and R code used some kind of tolerance when testing equality
        return if (compare(startingSolution, newSolution) < 0) {
            logger.info {"SPLINE search: SPLINE solution was no improvement, returned starting solution"}
            SPLINESolution(startingSolution, splineOracleCalls)
        } else {
            // The new solution might be better, but it might be input-infeasible.
            if (newSolution.isInputFeasible()) {
                logger.info {"SPLINE search: returning SPLINE search solution"}
                SPLINESolution(newSolution, splineOracleCalls)
            } else {
                // not feasible, go back to the last feasible solution
                logger.info {"SPLINE search: SPLINE solution was infeasible, returned starting solution"}
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
            logger.info {"PLI search: no feasible simplex inputs, returning no gradients, bad solution"}
            return PLIResults(numOracleCalls = 0, gradients = null, solution = badSolution)
        }
        //TODO The request for evaluation of the simplex vertices should use CRN
        val results = requestEvaluations(feasibleInputs.keys, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned. We assume that no oracle evaluations happened, even if they did.
            logger.info {"PLI search: no evaluation results, returning no gradients, bad solution"}
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
            logger.info {"PLI search: returning no gradients, best solution"}
            return PLIResults(numOracleCalls = results.size * sampleSize, gradients = null, solution = bestSolution)
        }
        // The full simplex has been evaluated. Thus, the gradients can be computed.
        val gradients = DoubleArray(simplexData.sortedFractionIndices.size)
        for ((i, indexValue) in simplexData.sortedFractionIndices.withIndex()) {
            gradients[indexValue] = results[i+1].penalizedObjFncValue - results[i].penalizedObjFncValue
        }
        // Return the current best solution along with the computed gradients.
        logger.info {"PLI search: returning gradients, best solution"}
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

//    /**
//     *  This function represents Algorithm 4: Search Piecewise Linear Interpolation (SPLI) in the paper:
//     *
//     *  H. Wang, R. Pasupathy, and B. W. Schmeiser, “Integer-Ordered Simulation
//     *  Optimization using R-SPLINE: Retrospective Search with Piecewise-Linear
//     *  Interpolation and Neighborhood Enumeration,” ACM Transactions on Modeling
//     *  and Computer Simulation (TOMACS), vol. 23, no. 3, pp. 17–24, July 2013,
//     *  doi: 10.1145/2499913.2499916.
//     *
//     * @param solution the initial solution for the line search
//     * @param sampleSize the number of replications to be associated with the simulation
//     * oracle evaluations associated with the simplex vertices
//     * @param splineCallLimit the call limit for the oracle evaluations
//     * @return a pair (Int, Solution) = (number of oracle calls executed, spline solution)
//     */
//    private fun searchPiecewiseLinearInterpolation(
//        solution: Solution,
//        sampleSize: Int,
//        splineCallLimit: Int
//    ): SPLIResults {
//        //Set X_best = x_0 and n′ = 0
//        var bestSoln = solution
//        var numOracleCalls = 0
//        for (j in 1..splineIterMax) {
//            // Call PLI(x1, mk) to observe gmk (x1) and (possibly) gradient
//            val pliResults = piecewiseLinearInterpolation(bestSoln, sampleSize)
//            numOracleCalls = numOracleCalls + pliResults.numOracleCalls
//            // regardless of gradient computation, update the current best solution
//            bestSoln = minimumSolution(pliResults.solution, bestSoln)
//            if (pliResults.gradients == null) {
//                // Stop if no direction
//                return SPLIResults(numOracleCalls, bestSoln)
//            }
//            if (numOracleCalls > splineCallLimit) {
//                // Stop if too many oracle calls
//                return SPLIResults(numOracleCalls, bestSoln)
//            }
//            // Setup to do the line search
//            val s0 = initialStepSize
//            val c = stepSizeMultiplier
//            val direction = pliResults.gradients.direction()
//            val x0 = bestSoln.inputMap.inputValues
//            // The matlab/R code has a limit on the number of line searches.
//            var lastIndex = -1
//            for (i in 1..lineSearchIterMax) {
//                lastIndex = i
//                // determine the step-size for this interation
//                val stepSize = s0 * c.pow(i - 1)
//                // translate the step to an array towards the proposed direction
//                val sd = KSLArrays.multiplyConstant(direction, stepSize) //step-array
//                // make the step in the proposed direction
//                val x1 = KSLArrays.subtractElements(x0, sd)
//                // This will shift x1 to the nearest integer point.
//                val inputs = problemDefinition.toInputMap(x1)
//                if (!inputs.isInputFeasible()) {
//                    // not a feasible step, return the best solution so far
//                    return SPLIResults(numOracleCalls, bestSoln)
//                }
//                // Use the simulation oracle to evaluate the new point represented by the step.
//                val x1Solution = requestEvaluation(inputs, sampleSize)
//                numOracleCalls = numOracleCalls + sampleSize
//                if (numOracleCalls > splineCallLimit) {
//                    // Stop if too many oracle calls, return whichever is better
//                    return SPLIResults(numOracleCalls, minimumSolution(x1Solution, bestSoln))
//                }
////                if (compare(x1Solution, bestSoln) > 0){
////                    // the step produced an inferior solution
////                    break
////                } else {
////                    // the step produced a better solution or a tied solution
////                    // update the best solution
////                    bestSoln = x1Solution
////                }
//                // if the x1Solution is worse than the current best, and we have only taken a small number
//                // of steps then assume that we are headed in the wrong direction and stop with the current best
//                if ((compare(x1Solution, bestSoln) >= 0) && i <= 2) {
//                    return SPLIResults(numOracleCalls, bestSoln)
//                }
//                // to get here i > 2 and x
//
//
//                // if the x1Solution is worse than the current best, and we have taken some improving steps
//                // then break out of the line search and try to get a new starting point
//                if ((compare(x1Solution, bestSoln) > 0)) {
//                    break
//                }
//                // if we get here then x1 produced a better solution than the current best
//                // update the best solution
//                bestSoln = x1Solution
//            }
//        }
//        return SPLIResults(numOracleCalls, bestSoln)
//    }

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
        var numOracleCalls = 0 // counts the calls internal to this routine
        do {
            // Call PLI(x1, mk) to observe gmk (x1) and (possibly) gradient
            logger.info {"PLI search: "}
            val pliResults = piecewiseLinearInterpolation(bestSoln, sampleSize)
            numOracleCalls = numOracleCalls + pliResults.numOracleCalls
            logger.info {"SPLI search: called PLI used ${pliResults.numOracleCalls} oracle calls : numOracleCalls = $numOracleCalls"}
            // regardless of gradient computation, update the current best solution
            bestSoln = minimumSolution(pliResults.solution, bestSoln)
            if (pliResults.gradients == null) {
                // Stop if no direction
                logger.info {"SPLI search: no gradient available, returned current best solution"}
                return SPLIResults(numOracleCalls, bestSoln)
            }
            // If we are here there are gradients.
            if (numOracleCalls > splineCallLimit) {
                // Stop if too many oracle calls. No need to do any line searching. We don't have any
                // oracle calls left.
                logger.info {"SPLI search: exceed available oracle calls, returned current best solution"}
                return SPLIResults(numOracleCalls, bestSoln)
            }
            // If we are here we have gradients to use and oracle calls expend
            // Setup to do the line search
            val s0 = initialStepSize
            val c = stepSizeMultiplier
            val direction = pliResults.gradients.direction()
            val x0 = bestSoln.inputMap.inputValues
            // The matlab/R code has a limit on the number of line searches.
            logger.info {"SPLI search: preparing for line search"}
            for (i in 1..lineSearchIterMax) {
                // determine the step-size for this interation
                val stepSize = s0 * c.pow(i - 1)
                logger.info {"SPLI search: Line search: iteration $i : step size = $stepSize"}
                // translate the step to an array towards the proposed direction
                val sd = KSLArrays.multiplyConstant(direction, stepSize) //step-array
                // make the step in the proposed direction
                val x1 = KSLArrays.subtractElements(x0, sd)
                // This will shift x1 to the nearest integer point.
                val inputs = problemDefinition.toInputMap(x1)
                if (!inputs.isInputFeasible()) {
                    // not a feasible step, don't continue the line searching, return the best solution so far
                    logger.info {"SPLI search: Line search: iteration $i : infeasible step, returning best solution"}
                    return SPLIResults(numOracleCalls, bestSoln)
                }
                // Use the simulation oracle to evaluate the new point represented by the step.
                val x1Solution = requestEvaluation(inputs, sampleSize)
                numOracleCalls = numOracleCalls + sampleSize
                if (numOracleCalls > splineCallLimit) {
                    // Stop if too many oracle calls, return whichever is better
                    logger.info {"SPLI search: Line search: iteration $i : exceeded oracle call limit, returning best solution"}
                    return SPLIResults(numOracleCalls, minimumSolution(x1Solution, bestSoln))
                }
                if (i <= 2){
                    if ((compare(x1Solution, bestSoln) >= 0)) {
                        // if the x1Solution is worse than the current best, and we have only taken a small number
                        // of steps. Assume that we are headed in the wrong direction and stop with the current best
                        logger.info {"SPLI search: Line search: iteration $i : line search candidate was no improvement, returning best"}
                        return SPLIResults(numOracleCalls, bestSoln)
                    }
                } else { // 2 < i <= lineSearchIterMax
                    // we have taken at least 3 improving steps
                    if ((compare(x1Solution, bestSoln) >= 0)) {
                        logger.info {"SPLI search: Line search: iteration $i : line search candidate was no improvement, breaking main loop"}
                        // The last step did not improve, break to continue overall search.
                        break
                    }
                }
                // If we get here, then x1 produced a better solution than the current best.
                // Update the best solution. Continue the line searching.
                logger.info {"SPLI search: Line search: iteration $i : line search improved solution, updating, and continuing line search"}
                bestSoln = x1Solution
            }
        } while (numOracleCalls < splineCallLimit)
        logger.info {"SPLI search: Line search: exceed oracle limit, returned best solution"}
        return SPLIResults(numOracleCalls, bestSoln)
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

    class NESearchResults(
        val numOracleCalls: Int,
        val solution: Solution
    )

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
    ): NESearchResults {
        require(solution.isInputFeasible()) { "The initial solution to the SPLINE function must be input feasible!" }
        // By construction, the neighborhood does not contain the center (initial) solution.
        // It has already been evaluated by the oracle.
        val neighborHood = neighborhoodFinder.neighborhood(
            solution.inputMap, this)
        // Perform search only on feasible points in the neighborhood.
        val feasible = neighborHood.filter { it.isInputFeasible() }.toSet()
        if (feasible.isEmpty()) {
            // No feasible points in the neighborhood. Return the starting point.
            logger.info {"NE search: No feasible points in the neighborhood, returning the starting point"}
            return NESearchResults(0, solution)
        }
        // Evaluate the feasible points in the neighborhood.
        val results = requestEvaluations(feasible, sampleSize)
        // Something could have gone wrong with the oracle processing.
        if (results.isEmpty()) {
            // No solutions returned. Return the starting point. Assume no oracle evaluations.
            logger.info {"NE search: No oracle solutions returned, returning the starting point"}
            return NESearchResults(0, solution)
        }
        // need to find the best of the results
        val candidate = results.minOf { it }
        //TODO matlab and R code uses a tolerance for comparison
        return if (compare(solution, candidate) < 0) {
            logger.info {"NE search: neighborhood solution was not better than starting solution, returned starting solution"}
            NESearchResults(results.size * sampleSize, solution)
        } else {
            logger.info {"NE search: neighborhood solution was better than starting solution, returned neighborhood solution"}
            NESearchResults(results.size * sampleSize, candidate)
        }
    }

    companion object {

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
    val simpleData = RSplineSolver.piecewiseLinearSimplex(x)
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