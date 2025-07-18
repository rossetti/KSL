package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.Solutions
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import kotlin.math.ceil

/**
 *  If supplied, this function will be used to determine the size of the elite sample
 *  during the cross-entropy process. Supplying a function can permit dynamic changes
 *  when determining the elite sample.
 */
fun interface EliteSizeIfc {

    fun eliteSize(ceSolver: CrossEntropySolver): Int

}

/**
 *  If supplied, this function will be used to determine the size of the cross-entropy
 *  sample during the cross-entropy process. Supplying a function can permit dynamic
 *  changes when determining the size of the cross-entropy sample (population).
 */
fun interface SampleSizeFnIfc {
    fun sampleSize(ceSolver: CrossEntropySolver): Int
}

/**
 * Constructs an instance of CrossEntropySolver with specified parameters.
 *
 * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
 * @param ceSampler the cross-entropy sampler for the cross-entropy distribution
 * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param name Optional name identifier for this instance of StochasticHillClimber.
 */
class CrossEntropySolver @JvmOverloads constructor(
    evaluator: EvaluatorIfc,
    val ceSampler: CESamplerIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : StochasticSolver(
    evaluator, maxIterations,
    replicationsPerEvaluation, ceSampler.streamNumber,
    ceSampler.streamProvider, name
) {

    /**
     * Constructs an instance of CrossEntropySolver with specified parameters.
     *
     * @param evaluator The evaluator responsible for assessing the quality of solutions. Must implement the EvaluatorIfc interface.
     * @param ceSampler the cross-entropy sampler for the cross-entropy distribution
     * @param maxIterations The maximum number of iterations allowed for the hill climbing process.
     * @param replicationsPerEvaluation The number of replications to perform for each evaluation of a solution.
     * @param name Optional name identifier for this instance of StochasticHillClimber.
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        evaluator: EvaluatorIfc,
        ceSampler: CESamplerIfc,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        evaluator, ceSampler, maxIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), name
    )

    /**
     *  If supplied, this function will be used to determine the size of the elite sample
     *  during the cross-entropy process. Supplying a function can permit dynamic changes
     *  when determining the elite sample.
     */
    var eliteSizeFn: EliteSizeIfc? = null

    /**
     *  If supplied, this function will be used to determine the size of the cross-entropy
     *  sample during the cross-entropy process. Supplying a function can permit dynamic
     *  changes when determining the size of the cross-entropy sample (population).
     */
    var sampleSizeFn: SampleSizeFnIfc? = null

    /**
     * A value between 0 and 1 that represents the proportion of the CE sample
     * that determines the elite sample. By default, this is 0.1, which can be controlled globally via the companion
     * object's [defaultElitePct].
     */
    var elitePct: Double = defaultElitePct
        set(value) {
            require(value > 0) { "The elite percentage must be greater than 0" }
            require(value < 1) { "The elite percentage must be less than 1" }
            field = value
        }

    /**
     * This value is used as a termination threshold for the largest number of iterations during which no
     * improvement of the best function value is found. By default, set to 5, which can be controlled
     * globally via the companion object's [defaultNoImproveThreshold]
     */
    var noImproveThreshold: Int = defaultNoImproveThreshold
        set(value) {
            require(value > 0) { "The no improvement threshold must be greater than 0" }
            field = value
        }

    /** The sample size associated with the CE algorithm used to determine the elite solutions.
     * By default, this is 10 times the dimension of the problem. The factor, 10, can be globally controlled
     * via the companion object's [defaultCESampleSizeFactor]
     */
    var ceSampleSize: Int = defaultCESampleSizeFactor * ceSampler.dimension
        set(value) {
            require(value >= ceSampler.dimension) { "The CE sample size must be >= ${ceSampler.dimension}" }
            field = value
        }

    /**
     *  Holds the solutions that are considered the most recent
     *  set of elite solutions ordered from the best.
     */
    private val myElites = mutableListOf<Solution>()

    /**
     *  The current list of elite solutions ordered from the best
     */
    @Suppress("unused")
    val elites: List<Solution> get() = myElites

    private lateinit var myLastSolutions : ArrayDeque<Solution>
    @Suppress("unused")
    val lastSolutions : List<Solution>
        get() = myLastSolutions.toList()

    /** If [eliteSizeFn] is supplied it will be used; otherwise, the elite percentage is used
     * to determine the size of the elite sample.
     *
     *  @return determines the size of the elite sample
     */
    fun eliteSize(): Int {
        return eliteSizeFn?.eliteSize(this) ?: ceil(elitePct * ceSampleSize).toInt()
    }

    /** If [sampleSizeFn] is supplied it will be used; otherwise, the value of [ceSampleSize] is used
     * to determine the size of the cross-entropy sample (population).
     *
     *  @return determines the size of the cross-entropy sample (population).
     */
    fun sampleSize(): Int {
        return sampleSizeFn?.sampleSize(this) ?: ceSampleSize
    }

    override fun initializeIterations() {
        val initialPoint = startingPoint ?: startingPoint()
        ceSampler.initializeParameters(initialPoint.inputValues)
        myElites.clear()
        myLastSolutions = ArrayDeque(noImproveThreshold)
        logger.trace { "Solver: $name : initialized with CE Sampler's parameters" }
        logger.trace { "Initial parameters = $initialPoint" }
    }

    override fun mainIteration() {
        // Generate the cross-entropy sample population
        val points = ceSampler.sample(sampleSize())
        // Convert the points to be able to evaluate.
        val inputs = convertPointsToInputs(points)
        // request evaluations for solutions
        val results = requestEvaluations(inputs)
        if (results.isEmpty()) {
            // Returning will cause no updating on this iteration.
            // New points will be generated for another try on next iteration.
            return
        }
        // At least one result, so proceed with processing.
        // Process the results to find the elites, this should fill myElites.
        val elitePoints = processResultsToEliteSample(results)
        // update the sampler's parameters
        ceSampler.updateParameters(elitePoints)
        // specify the current solution
        currentSolution = myElites.first()
        TODO("Not yet implemented")
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: checkForConvergence()
    }

    private fun checkForConvergence() : Boolean {
        // need to check for convergence of sampler
        // need to check for no solution improvement
        TODO("Not yet implemented")
    }

    private fun convertPointsToInputs(points: List<DoubleArray>) : Set<InputMap> {
        val inputs = mutableSetOf<InputMap>()
        for (point in points) {
            inputs.add(problemDefinition.toInputMap(point))
        }
        return inputs
    }

    private fun findEliteSolutions(results: List<Solution>): List<Solution> {
        results.sorted()
        return results.take(eliteSize())
    }

    private fun processResultsToEliteSample(results: List<Solution>) : List<DoubleArray> {
        // fill the solutions with all the results
        //TODO do this by sorting
        val solutions = Solutions(capacity = results.size)
        solutions.addAll(results)
        // clear the current held elites for the elites from the new sample
        myElites.clear()
        // get the size of the desired elite sample
        val n = eliteSize()
        for(i in 1..n) {
            val best = solutions.peekBest()
            if (best != null) {
                solutions.remove(best)
                myElites.add(best)
            } else {
                // no more solutions, must stop even if less than requested number
                break
            }
        }
        solutions.clear()
        // myElites should contain the ordered best
        val eliteInputs = mutableListOf<DoubleArray>()
        // convert to arrays for parameter updating
        for (s in myElites) {
            eliteInputs.add(s.inputMap.inputValues)
        }
        return eliteInputs
    }

    companion object {

        /**
         * A value between 0 and 1 that represents the default proportion of the CE sample
         * that determines the elite sample. By default, this is 0.1.
         */
        var defaultElitePct: Double = 0.1
            set(value) {
                require(value > 0) { "The default elite percentage must be greater than 0" }
                require(value < 1) { "The default elite percentage must be less than 1" }
                field = value
            }

        /**
         * This value is used as the default termination threshold for the largest number of iterations during which no
         * improvement of the best function value is found. By default, set to 5.
         */
        var defaultNoImproveThreshold: Int = 5
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        /**
         *  This value is used to help determine the cross-entropy sample size (population) that is
         *  processed to determine the elite sample. By default, this value is 10. This value is
         *  used to set the default sample size based on the dimension of the problem.
         */
        var defaultCESampleSizeFactor: Int = 10
            set(value) {
                require(value >= 1) { "The default CE sample size factor must be >= 1" }
                field = value
            }

    }

}