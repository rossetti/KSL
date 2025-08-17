package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.InputsAndConfidenceIntervalEquality
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionChecker
import ksl.simopt.evaluator.SolutionEqualityIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultReplicationGrowthRate
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
 *  The original algorithm used the total number of oracle calls as an approach to stopping the
 *  iterations. Matlab/R implementations did not use this approach, but had a limit on the total
 *  number of R-SPLINE iterations.  The basic implementation of all solvers has such a limit via the
 *  [maxIterations] property. The theory of the approach indicates that each sample-path problem, P_k,
 *  is used to seed the next sample path problem. This causes the solutions to the P_k problems to
 *  eventually converge to some solution. The original paper does not provide much insight into
 *  how to compare solutions for stopping. The approach implemented here follows the suggestions
 *  found within the cross-entropy literature. The sequence of P_k calls will stop when a fixed number
 *  of the last produced solutions test for equality. The default here is to indicate that solutions
 *  are equal if they compare equal according to a confidence interval test and have the same input
 *  variable values. The confidence interval test is based on the penalized objective function value.
 *  The stopping criteria is controlled by the [solutionChecker] property.  Users can supply
 *  their own checking procedure via this property and their own definition of equality via its
 *  [solutionEqualityChecker] property.
 *
 * @constructor Creates an R-SPLINE solver with the specified parameters.
 * @param problemDefinition the problem being solved.
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param maxIterations The maximum number of iterations allowed for the solving process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param solutionEqualityChecker Used when testing if solutions have converged for equality between solutions.
 * The default is [InputsAndConfidenceIntervalEquality], which checks if the inputs are the same and there
 * is no statistical difference between the solutions
 * @param streamNum the random number stream number, defaults to 0, which means the next stream
 * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
 * @param name Optional name identifier for this instance of the solver.
 */
class RSplineSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: FixedGrowthRateReplicationSchedule,
    solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(problemDefinition, evaluator, maxIterations, replicationsPerEvaluation, streamNum, streamProvider, name) {

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
     * @constructor Creates an R-SPLINE solver with the specified parameters.
     * @param problemDefinition the problem being solved.
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param initialNumReps the initial starting number of replications
     * @param maxIterations The maximum number of iterations allowed for the solving process.
     * @param sampleSizeGrowthRate the growth rate for the replications. The default is set by [defaultReplicationGrowthRate].
     * @param maxNumReplications the maximum number of replications permitted. If
     * the growth exceeds this value, then this value is used for all future replications.
     * The default is determined by [defaultMaxNumReplications]
     * @param solutionEqualityChecker Used when testing if solutions have converged for equality between solutions.
     * The default is [InputsAndConfidenceIntervalEquality], which checks if the inputs are the same and there
     * is no statistical difference between the solutions
     * @param streamNum the random number stream number, defaults to 0, which means the next stream
     * @param streamProvider the provider of random number streams, defaults to [KSLRandom.DefaultRNStreamProvider]
     * @param name Optional name identifier for this instance of the solver.
     */
    @Suppress("unused")
    @JvmOverloads
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        maxIterations: Int = defaultMaxNumberIterations,
        initialNumReps: Int = defaultInitialSampleSize,
        sampleSizeGrowthRate: Double = defaultReplicationGrowthRate,
        maxNumReplications: Int = defaultMaxNumReplications,
        solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(problemDefinition,
        evaluator, maxIterations, FixedGrowthRateReplicationSchedule(
            initialNumReps, sampleSizeGrowthRate, maxNumReplications
        ), solutionEqualityChecker, streamNum, streamProvider, name
    )

    /**
     *  This defines the growth schedule for the number of replications. In the
     *  original paper, this is the value of m_k.
     */
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

    /**
     *  The initial setting of the line search step-size. By default, this is 2.0
     */
    var initialLineSearchStepSize: Double = 2.0
        set(value) {
            require(value > 1.0) { "The initial step size must be > 1.0" }
            field = value
        }

    /**
     *  The multiplier that increases the line search step-size for each line
     *  search iteration. The default is 2.0
     */
    var lineSearchStepSizeMultiplier: Double = 2.0
        set(value) {
            require(value >= 1.0) { "The step multiplier must be >= 1.0" }
            field = value
        }

    /**
     *  The neighborhood finder for performing the neighborhood search. By default,
     *  this is a von Neumann neighborhood.
     */
    var neighborhoodFinder: NeighborhoodFinderIfc = problemDefinition.vonNeumannNeighborhoodFinder()

    /**
     * The rate at which the permissible number of SPLINE calls grows.
     * By default, this is defined by [defaultSplineCallGrowthRate].
     */
    var splineCallGrowthRate: Double = defaultSplineCallGrowthRate
        set(value) {
            require(value > 0) { "The spline growth rate must be > 0" }
            field = value
        }

    /**
     * Since the number of SPLINE calls can grow as the algorithm proceeds, this
     * variable provides the initial number of SPLINE calls to which the growth is applied.
     * The default value is [defaultInitialMaxSplineCalls].
     */
    var initialMaxSplineCallLimit: Int = defaultInitialMaxSplineCalls
        set(value) {
            require(value > 0) { "The initial maximum number of SPLINE calls must be > 0" }
            field = value
        }
    /**
    * Since the number of SPLINE calls can grow as the algorithm proceeds, this
    * variable provides the maximum number of calls that are permissible. The growth
    * with not exceed this value. By default, this is [defaultMaxSplineCallLimit].
    */
    var maxSplineCallLimit: Int = defaultMaxSplineCallLimit
        set(value) {
            require(value > 0) { "The maximum for the number of SPLINE call growth limit must be > 0" }
            field = value
        }

    /**
     * Since the number of SPLINE calls can grow as the algorithm proceeds, this
     * variable represents the current number of permissible SPLINE calls. In the
     * notation of the R-SPLINE paper, this is b_k.
     */
    val splineCallLimit: Int
        get() {
            val k = iterationCounter
            if (k == 1) {
                return initialMaxSplineCallLimit
            }
            val m = initialMaxSplineCallLimit * (1.0 + splineCallGrowthRate).pow(k - 1)
            return minOf(maxSplineCallLimit, ceil(m).toInt())
        }

    /**
     *  This variable limits the number of permissible line-searches within the SPLINE step.
     */
    var lineSearchIterMax = defaultLineSearchIterMax
        set(value) {
            require(value > 0) { "The maximum number of spline line search iterations must be > 0" }
            field = value
        }

    /**
     *  This variable limits the number of permissible SPLI-searches within the SPLINE step.
     */
    var spliMaxIterations = defaultSPLIMaxIterations
        set(value) {
            require(value > 0) { "The default maximum number of spline line search iterations must be > 0" }
            field = value
        }

    /**
     *  This variable tracks the current sample size for each SPLINE iteration. In the
     *  notation of the paper, this is m_k.
     */
    val rSPLINESampleSize: Int
        get() = fixedGrowthRateReplicationSchedule.numReplicationsPerEvaluation(this)


    /**
     *  Used to check if the last set of solutions that were captured
     *  are the same.
     */
    val solutionChecker: SolutionChecker = SolutionChecker(solutionEqualityChecker,
        defaultNoImproveThresholdForRSPLINE)

    private val badSolution = problemDefinition.badSolution()

    /**
     *  The default implementation ensures that the initial point and solution
     *  are input-feasible (feasible with respect to input ranges and deterministic constraints).
     */
    override fun initializeIterations() {
        solutionChecker.clear()
        super.initializeIterations()
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
     *  The current sample [rSPLINESampleSize] size upon initialization is the initial sample
     *  size of the replication schedule (m_k). The current spline call limit (b_k)
     *  is determined by the property [splineCallLimit].
     *  If the kth sample path problem beats the previous sample path problem,
     *  then the current solution is updated. The initial solution is determined
     *  randomly when the solver is initialized.
     */
    override fun mainIteration() {
        // The initial (current) solution is randomly selected or specified by the user.
        // It will be the current solution until beaten by the SPLINE search process.
        // Call SPLINE for the next solution using the current sample size (m_k) and
        // current SPLINE oracle call limit (b_k).

        logger.trace { "SPLINE search: main iteration = $iterationCounter : sample size = $rSPLINESampleSize : SPLINE call limit = $splineCallLimit" }
        println("SPLINE: main iteration = $iterationCounter : sample size = $rSPLINESampleSize")
        println("SPLINE: starting solution: ${currentSolution.asString()}")
        val splineSolution = spline(
            currentSolution,
            rSPLINESampleSize, splineCallLimit
        )
        logger.trace { "SPLINE search: completed main iteration = $iterationCounter : numOracleCalls = $numOracleCalls" }

        currentSolution = splineSolution
        println("SPLINE: ending solution: ${currentSolution.asString()}")
        // capture the last solution
        solutionChecker.captureSolution(currentSolution)
    }

    override fun afterMainIteration() {
        println("SPLINE: after main iteration = $iterationCounter")
        println("SPLINE: current solution: ${currentSolution.asString()}")
        println("SPLINE: best solution: ${bestSolution.asString()}")
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: solutionChecker.checkSolutions()
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
     * @param splineCallLimit the call limit for the number of times that SPLI can be called
     * @return a pair (Int, Solution) = (number of oracle calls executed, spline solution)
     */
    private fun spline(
        initSolution: Solution,
        sampleSize: Int,
        splineCallLimit: Int
    ): Solution {
        // The SPLINE search must start with a feasible point.
        require(initSolution.isInputFeasible()) { "The initial solution to the SPLINE function must be input feasible!" }
        // use the initial solution as the new (starting) solution for the line search (SPLI)
        // the new solution may need the extra sample size
        var newSolution = if (sampleSize > initSolution.count){
            logger.trace { "spline(): requested sample size ($sampleSize) is larger than the initial solution size (${initSolution.count})." }
            logger.trace { "spline(): requesting evaluations for sample size of $sampleSize" }
            requestEvaluation(initSolution.inputMap, sampleSize)
        } else {
            logger.trace { "spline(): requested sample size ($sampleSize) <= initial solution size: Using the initial solution with sample size = ${initSolution.count}." }
            initSolution
        }
        // save the starting solution
        val startingSolution = newSolution
        // initialize the number of oracle calls
        var splineOracleCalls = 0
        // Note: This follows the matlab/R code, which has a limit on the number of calls to SPLI + NE
        logger.trace { "spline(): Starting SPLI+NE search with sample size = $sampleSize : spline call limit = $splineCallLimit" }
        for(i in 1..splineCallLimit){
            // perform the line search to get a solution based on the new solution
            logger.trace { "\t SPLI search: iteration: $i of $splineCallLimit"}
            logger.trace {"\t Starting solution for SPLI: ${newSolution.asString()}"}
            val spliResults = searchPiecewiseLinearInterpolation(newSolution, sampleSize)
            logger.trace {"\t Completed SPLI search."}
            // SPLI cannot cause harm
            val neStartingSolution = if (compare(spliResults.solution, newSolution) <= 0){
                logger.trace { "\t SPLI search: resulted in a candidate solution better than current solution."}
                logger.trace { "\t SPLI search: using the new candidate solution to seed the NE search."}
                spliResults.solution
            } else {
                logger.trace { "\t SPLI search: resulted in a candidate solution not better than current solution."}
                logger.trace { "\t SPLI search: keeping the current solution to seed NE search."}
                newSolution
            }
            // search the neighborhood starting from the SPLI solution
            logger.trace {"\t Starting solution for NE search: ${neStartingSolution.asString()}"}
            val neSearchResults = neighborhoodSearch(neStartingSolution, sampleSize)
            logger.trace {"\t Completed NE search."}
            splineOracleCalls = splineOracleCalls + spliResults.numOracleCalls + neSearchResults.numOracleCalls
            // The starting solution to NE could be:
            // 1) the previous starting solution or
            // 2) the result of the SPLI search.
            // The result of the NE search will be:
            // 1) its starting point (previous starting point or candidate from SPLI search), or
            // 2) some neighbor that is better than the starting point (an improvement).
            // If there is no improvement from NE, then what?
            // Capture the newest solution from the neighborhood search to seed the next SPLI search.
            newSolution = neSearchResults.solution
            // if the candidate solution and the NE search starting solution are the same, we can stop
            // matlab and R code used some kind of tolerance when testing equality
            if ((neStartingSolution == neSearchResults.solution) || compare(neStartingSolution, neSearchResults.solution) == 0) {
                logger.trace { "\t SPLINE search: iteration: $i : break loop : NE verified N1 local optimality" }
                break
            }
        }
        logger.trace { "spline(): Completed SPLINE search with sample size = $sampleSize" }
        logger.trace { "spline(): Completed SPLINE search with number of oracle calls = $splineOracleCalls" }
        // Check if the starting solution is better than the solution from the SPLINE search.
        // If the starting solution is still better return it. The returned solution must be input-feasible.
        // matlab and R code used some kind of tolerance when testing equality
        return if (compare(startingSolution, newSolution) < 0) {
            logger.trace { "SPLINE search completed: SPLINE solution was no improvement over starting solution, returned starting solution" }
            startingSolution
        } else {
            // The new solution might be better, but it might be input-infeasible.
            if (newSolution.isInputFeasible()) {
                logger.trace { "SPLINE search completed: returning SPLINE search solution" }
                newSolution
            } else {
                // not feasible, go back to the last feasible solution
                logger.trace { "SPLINE search completed: SPLINE solution was infeasible, returned starting solution" }
                startingSolution
            }
        }
    }

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
        // The PLI search must start with a feasible point.
        require(solution.isInputFeasible()) { "The initial solution to the PLI function must be input feasible!" }
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
            logger.trace { "\t \t \t \t PLI search: no feasible simplex inputs, returning no gradients, bad solution" }
            return PLIResults(numOracleCalls = 0, gradients = null, solution = badSolution)
        }
        //TODO The request for evaluation of the simplex vertices should use CRN
        logger.trace { "\t \t \t \t Requesting evaluation of ${feasibleInputs.keys.size} simplex vertices with sample size = $sampleSize." }
        val results = requestEvaluations(feasibleInputs.keys, sampleSize)
        if (results.isEmpty()) {
            // No solutions returned. We assume that no oracle evaluations happened, even if they did.
            logger.trace { "\t \t \t \t PLI search: no evaluation results, returning no gradients, bad solution" }
            return PLIResults(numOracleCalls = 0, gradients = null, solution = badSolution)
        }
        logger.trace { "\t \t \t \t PLI search: evaluation results returned for ${feasibleInputs.keys.size} vertices." }
        // There must be new results available for some simplex vertices.
        // Find the best of the simplex vertices.
        val resultsBest = results.minOf { it }
        // Determine if the best solution should be updated. If tied, prefer the solution with more oracle evaluations.
        val bestSolution = if (compare(resultsBest, solution) <= 0) {
            logger.trace { "\t \t \t \t PLI search: Assigned best solution from simplex vertices." }
            resultsBest
        } else {
            logger.trace { "\t \t \t \t PLI search: solution from the simplex vertices was no improvement, keeping current solution." }
            solution
        }
        logger.trace { "\t \t \t \t PLI search: Current best solution: ${bestSolution.asString()}" }
        // The simplex results may be missing infeasible vertices.
        if (results.size < simplexData.vertices.size) {
            // The simplex has infeasible vertices. Return the current best solution without the gradients.
            logger.trace { "\t \t \t \t PLI search: returning current best solution because of missing gradients!" }
            return PLIResults(numOracleCalls = results.size * sampleSize, gradients = null, solution = bestSolution)
        }
        // The full simplex has been evaluated. Thus, the gradients can be computed.
        val gradients = DoubleArray(simplexData.sortedFractionIndices.size)
        for ((i, indexValue) in simplexData.sortedFractionIndices.withIndex()) {
            gradients[indexValue] = results[i + 1].penalizedObjFncValue - results[i].penalizedObjFncValue
        }
        // Return the current best solution along with the computed gradients.
        logger.trace { "\t \t \t \t PLI search: returning current best solution along with the computed gradients" }
        return PLIResults(numOracleCalls = results.size * sampleSize, gradients = gradients, solution = bestSolution)
    }

    // tracking the number of oracle calls is only necessary for logging information.
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
        sampleSize: Int
    ): SPLIResults {
        //Set X_best = x_0 and n′ = 0
        var bestSoln = solution
        var numOracleCalls = 0 // counts the calls internal to this routine
        logger.trace { "\t \t  SPLI search: starting SPLI iterations" }
        for(j in 1..spliMaxIterations){
            // Call PLI(x1, mk) to observe gmk (x1) and (possibly) gradient
            logger.trace { "\t \t \t SPLI search: iteration $j of $spliMaxIterations : calling PLI..." }
            val pliResults = piecewiseLinearInterpolation(bestSoln, sampleSize)
            numOracleCalls = numOracleCalls + pliResults.numOracleCalls
            logger.trace { "\t \t \t SPLI search: iteration $j : called PLI used ${pliResults.numOracleCalls} oracle calls" }
            // regardless of gradient computation, update the current best solution
            bestSoln = if (compare(pliResults.solution, bestSoln) <= 0) {
                pliResults.solution
            } else {
                bestSoln
            }
            if (pliResults.gradients == null) {
                // Stop if no direction
                logger.trace { "\t \t \t SPLI search: iteration $j : no gradient available, returned current best solution : no line search performed" }
                return SPLIResults(numOracleCalls, bestSoln)
            }
            // If we are here we have gradients to use.
            // Setup to do the line search
            val s0 = initialLineSearchStepSize
            val c = lineSearchStepSizeMultiplier
            val direction = pliResults.gradients.direction()
            val x0 = bestSoln.inputMap.inputValues
            // The matlab/R code has a limit on the number of line searches.
            logger.trace { "\t \t \t SPLI search: iteration $j : starting line search iterations:" }
            for (i in 1..lineSearchIterMax) {
                // determine the step-size for this interation
                val stepSize = s0 * c.pow(i - 1)
                logger.trace { "\t \t \t \t SPLI search: Line search iteration $i of $lineSearchIterMax: step size = $stepSize" }
                // translate the step to an array towards the proposed direction
                val sd = KSLArrays.multiplyConstant(direction, stepSize) //step-array
                // make the step in the proposed direction
                val x1 = KSLArrays.subtractElements(x0, sd)
                // This will shift x1 to the nearest integer point.
                val inputs = problemDefinition.toInputMap(x1)
                if (!inputs.isInputFeasible()) {
                    // not a feasible step, don't continue the line searching, return the best solution so far
                    logger.trace { "\t \t \t \t SPLI search: Line search iteration $i of $lineSearchIterMax: infeasible step, returning best solution" }
                    return SPLIResults(numOracleCalls, bestSoln)
                }
                // Use the simulation oracle to evaluate the new point represented by the step.
                val x1Solution = requestEvaluation(inputs, sampleSize)
                numOracleCalls = numOracleCalls + sampleSize
                if (i <= 2) {
                    if ((compare(x1Solution, bestSoln) >= 0)) {
                        // If the x1Solution is worse than the current best, and we have only taken a small number
                        // of steps. Assume that we are headed in the wrong direction and stop with the current best
                        logger.trace { "\t \t \t \t SPLI search: Line search iteration $i of $lineSearchIterMax: line search candidate was no improvement, returning best" }
                        return SPLIResults(numOracleCalls, bestSoln)
                    }
                } else { // 2 < i <= lineSearchIterMax
                    // we have taken at least 3 improving steps
                    if ((compare(x1Solution, bestSoln) >= 0)) {
                        logger.trace { "\t \t \t \t SPLI search: Line search iteration $i of $lineSearchIterMax: line search candidate was no improvement, breaking main loop" }
                        // The last step did not improve, break to continue overall search.
                        break
                    }
                }
                // If we get here, then x1 produced a better solution than the current best.
                // Update the best solution. Continue the line searching.
                logger.trace { "\t \t \t \t SPLI search: Line search iteration $i of $lineSearchIterMax: line search improved solution, updating, and continuing line search" }
                bestSoln = x1Solution
                logger.trace { "\t \t \t \t SPLI search: Line search: improved solution : ${bestSoln.asString()}" }
            }
            logger.trace { "\t \t \t  SPLI search: iteration $j : completed line search iterations:" }
            logger.trace { "\t \t \t  solution : ${bestSoln.asString()}" }
        }
        logger.trace { "\t \t  SPLI search: completed SPLI iterations, returning best solution." }
        logger.trace { "\t \t  SPLI search: best solution: ${bestSoln.asString()}" }
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

    // tracking the number of oracle calls is only necessary for logging information.
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
            solution.inputMap, this
        )
        // Perform search only on feasible points in the neighborhood.
        val feasible = neighborHood.filter { it.isInputFeasible() }.toSet()
        if (feasible.isEmpty()) {
            // No feasible points in the neighborhood. Return the starting point.
            logger.trace { "\t \t NE search: No feasible points in the neighborhood, returning the starting point" }
            return NESearchResults(0, solution)
        }
        // Evaluate the feasible points in the neighborhood.
        val results = requestEvaluations(feasible, sampleSize)
        // Something could have gone wrong with the oracle processing.
        if (results.isEmpty()) {
            // No solutions returned. Return the starting point. Assume no oracle evaluations.
            logger.trace { "\t \t NE search: No oracle solutions returned, returning the starting point" }
            return NESearchResults(0, solution)
        }
        // need to find the best of the results for the neighborhood evaluation
        val candidate = results.minOf { it }
        // matlab and R code uses a tolerance for comparison
        return if (compare(solution, candidate) < 0) {
            logger.trace { "\t \t NE search: neighborhood solution was not better than starting solution, returned starting solution" }
            NESearchResults(results.size * sampleSize, solution)
        } else {
            logger.trace { "\t \t NE search: neighborhood solution was better than starting solution, returned neighborhood solution" }
            NESearchResults(results.size * sampleSize, candidate)
        }
    }

    companion object {

        /**
         *  The default maximum number of iterations for the line search. It is set to 10.
         */
        var defaultLineSearchIterMax = 10
            set(value) {
                require(value > 0) { "The default maximum number of spline line search iterations must be > 0" }
                field = value
            }

        /**
         *  The default maximum number of iterations for the SPLI search. It is equal to 5.
         */
        var defaultSPLIMaxIterations = 5
            set(value) {
                require(value > 0) { "The default maximum number of spline line search iterations must be > 0" }
                field = value
            }

        /**
         *  The default initial sample size for the SPLINE search. It is equal to 8.
         */
        var defaultInitialSampleSize: Int = 8
            set(value) {
                require(value > 0) { "The default initial sample size for replications must be > 0" }
                field = value
            }

        /**
         *  The default perturbation factor for the PERTURB function. It is equal to 0.15.
         */
        var defaultPerturbation: Double = 0.15
            set(value) {
                require((0.0 < value) && (value < 1.0)) { "The perturbationFactor must be in (0,1)" }
                field = value
            }

        /**
         *  The default SPLINE search iteration growth factor. It is set to 0.1.
         */
        var defaultSplineCallGrowthRate: Double = 0.1
            set(value) {
                require(value > 0) { "The default spline growth rate must be > 0" }
                field = value
            }

        /**
         *  The default initial number of SPLINE search calls that will grow.
         *  It is set to 10. This is b_0 in the notation of the paper.
         */
        var defaultInitialMaxSplineCalls: Int = 10
            set(value) {
                require(value > 0) { "The default initial maximum number of SPLINE calls must be > 0" }
                field = value
            }

        /**
         *  Since the number of SPLINE calls can grow, this represents the default maximum
         *  number of SPLINE calls to limit the growth.
         */
        var defaultMaxSplineCallLimit: Int = 1000
            set(value) {
                require(value > 0) { "The default maximum for the number of SPLINE call growth limit must be > 0" }
                field = value
            }

        /**
         * This value is used as the default termination threshold for the largest number of iterations, during which no
         * improvement of the best function value is found. By default, set to 10.
         */
        @JvmStatic
        var defaultNoImproveThresholdForRSPLINE: Int = 10
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        /**
         *  A point within a simplex with its weight
         */
        class SimplexPoint(val vertex: DoubleArray, val weight: Double)

        /**
         *  This class represents the data structure for a computed simplex.
         *  The simplex is a set of vertices, each with a weight.
         *
         *  @param originalPoint the point that started the simplex
         *  @param fractionalParts the fractional parts of the original point
         *  @param sortedFractionIndices the indices of the sorted fractions
         *  @param sortedFractions the fractional parts sorted
         *  @param vertices the vertices that make up the simplex
         *  @param weights the weights of the vertices
         */
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

        /** This function adds a random perturbation to the supplied point.
         *
         * @param point the point to perturb
         * @param perturbation the perturbation factor
         * @param rnStream the random number stream to use for the perturbation
         *
         */
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

/**
fun main() {
    val x = doubleArrayOf(1.8, 2.3, 3.6)
    println("x = ${x.contentToString()}")
    val simpleData = RSplineSolver.piecewiseLinearSimplex(x)
    println(simpleData)
}
**/

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