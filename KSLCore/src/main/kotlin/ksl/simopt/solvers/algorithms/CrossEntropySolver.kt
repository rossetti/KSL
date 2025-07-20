package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.distributions.Normal
import ksl.utilities.math.KSLMath
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
 * @param maxIterations The maximum number of iterations allowed for the search process.
 * @param replicationsPerEvaluation Strategy to determine the number of replications to perform for each evaluation.
 * @param name Optional name identifier for this instance of solver.
 */
class CrossEntropySolver @JvmOverloads constructor(
    evaluator: EvaluatorIfc,
    val ceSampler: CESamplerIfc,
    maxIterations: Int = ceDefaultMaxIterations,
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
     * @param maxIterations The maximum number of iterations allowed for the search process.
     * @param replicationsPerEvaluation The number of replications to perform for each evaluation of a solution.
     * @param name Optional name identifier for this instance of the solver.
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        evaluator: EvaluatorIfc,
        ceSampler: CESamplerIfc,
        maxIterations: Int = ceDefaultMaxIterations,
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
     * By default, this is determined by the function recommendCESampleSize() within
     * the companion object.
     * The value cannot be less than [defaultMinCESampleSize] or greater than [defaultMaxCESampleSize]
     */
    var ceSampleSize: Int = minOf(recommendCESampleSize(), defaultMaxCESampleSize)
        set(value) {
            require(value >= defaultMinCESampleSize) { "The CE sample size must be >= $defaultMinCESampleSize" }
            field = minOf(value, defaultMaxCESampleSize)
        }

    /**
     *  Holds the solutions that are considered the most recent
     *  set of elite solutions ordered from the best.
     */
    private var myEliteSolutions = mutableListOf<Solution>()

    /**
     *  The current list of elite solutions ordered from the best
     */
    @Suppress("unused")
    val elites: List<Solution> get() = myEliteSolutions

    private lateinit var myLastSolutions: ArrayDeque<Solution>

    @Suppress("unused")
    val lastSolutions: List<Solution>
        get() = if (::myLastSolutions.isInitialized) myLastSolutions.toList() else emptyList()

    /** If [eliteSizeFn] is supplied it will be used; otherwise, the elite percentage is used
     * to determine the size of the elite sample.
     *
     *  @return determines the size of the elite sample
     */
    fun eliteSize(): Int {
        return eliteSizeFn?.eliteSize(this) ?: maxOf(ceil(elitePct * ceSampleSize).toInt(),
            defaultMinEliteSize)
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
        myEliteSolutions.clear()
        myLastSolutions = ArrayDeque(noImproveThreshold)
        logger.info { "Solver: $name : initialized with CE Sampler's parameters" }
        logger.info { "Initial parameters = $initialPoint" }
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
        myEliteSolutions = findEliteSolutions(results)
        // convert elite solutions to points
        val elitePoints = convertSolutionsToPoints(myEliteSolutions)
        // update the sampler's parameters
        ceSampler.updateParameters(elitePoints)
        // specify the current solution
        currentSolution = myEliteSolutions.first()
        // capture the last solution
        captureLastSolution()
        //  println("Iteration: $iterationCounter  CE: $currentSolution")
    }

    private fun captureLastSolution() {
        if (myLastSolutions.size == noImproveThreshold) {
            myLastSolutions.removeFirstOrNull()
        }
        myLastSolutions.add(currentSolution)
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: checkForConvergence()
    }

    private fun checkForConvergence(): Boolean {
        // need to check for convergence of sampler
        // need to check for no solution improvement
        return checkLastSolutions() || ceSampler.hasConverged()
    }

    private fun checkLastSolutions(): Boolean {
        if (myLastSolutions.size < noImproveThreshold) return false
        val lastSolution = myLastSolutions.last()
        for (solution in myLastSolutions) {
//TODO this works but in no way accounts for variability in the comparison
// make this a function that can be supplied. Isn't this what solution quality evaluator is for?

            if (!KSLMath.within(
                    lastSolution.penalizedObjFncValue,
                    solution.penalizedObjFncValue, solutionPrecision
                )
            ) {
                return false
            }
//            if (compare(lastSolution, solution) != 0) {
//                return false
//            }
        }
        return true
    }

    private fun convertPointsToInputs(points: List<DoubleArray>): Set<InputMap> {
        val inputs = mutableSetOf<InputMap>()
        for (point in points) {
            inputs.add(problemDefinition.toInputMap(point))
        }
        return inputs
    }

    private fun convertSolutionsToPoints(solutions: List<Solution>): List<DoubleArray> {
        val points = mutableListOf<DoubleArray>()
        for (solution in solutions) {
            points.add(solution.inputMap.inputValues)
        }
        return points
    }

    private fun findEliteSolutions(results: List<Solution>): MutableList<Solution> {
        return results.sorted().take(eliteSize()).toMutableList()
    }

    override fun toString(): String {
        val sb = StringBuilder("Cross-Entropy Solver with parameters: \n")
        sb.appendLine("Elite Pct: $elitePct")
        sb.appendLine("No improvement threshold: $noImproveThreshold")
        sb.appendLine("CE Sample Size: $ceSampleSize")
        sb.appendLine("Elite Size: ${eliteSize()}")
        sb.appendLine("CE Sampler:")
        sb.appendLine("$ceSampler")
        sb.append(super.toString())
        return sb.toString()
    }

    companion object {

        /**
         *  Based on the theory in See [Chen and Kelton (1999)](https://dl.acm.org/doi/pdf/10.1145/324138.324272),
         *  this function computes a recommended cross-entropy sample size. The sample size may be sufficient
         *  to adequately estimate the quantile associated with the desired elite percentage using
         *  on a desired quantile confidence level and an adjusted half-width bound for the associated
         *  quantile proportion.
         *  @param elitePct a value between 0 and 1 that represents the proportion of the CE sample
         *  that determines the elite sample. By default, this is [defaultElitePct].
         *  @param ceQuantileConfidenceLevel A value between 0 and 1 that represents the approximate confidence
         *  level for estimating the quantile from the cross-entropy sample. By default, this is [defaultCEQuantileConfidenceLevel].
         *  @param maxProportionHalfWidth A value between 0 and max(0,[defaultElitePct]) that represents the
         *  confidence level half-width bound on the proportion estimate for determining the
         *  quantile from the cross-entropy sample. By default, this is [defaultMaxProportionHalfWidth].
         */
        fun recommendCESampleSize(
            elitePct: Double = defaultElitePct,
            ceQuantileConfidenceLevel: Double = defaultCEQuantileConfidenceLevel,
            maxProportionHalfWidth: Double = defaultMaxProportionHalfWidth,
        ): Int {
            require((0 < elitePct) && (elitePct < 1)) { "The elite percentage must be in (0,1)" }
            require((0 < ceQuantileConfidenceLevel) && (ceQuantileConfidenceLevel < 1)) { "The CE quantile confidence level must be in (0,1)" }
            require(maxProportionHalfWidth > 0) { "The default cross-entropy proportion half-width bound must be greater than 0" }
            val max = maxOf(elitePct, 1 - elitePct)
            require(maxProportionHalfWidth < max) { "The default cross-entropy proportion half-width bound must be less than $max" }
            val z = Normal.stdNormalInvCDF(1.0 - (ceQuantileConfidenceLevel / 2.0))
            val n = z * z * elitePct * (1.0 - elitePct) / (maxProportionHalfWidth * maxProportionHalfWidth)
            return ceil(n).toInt()
        }

        /**
         * A value between 0 and 1 that represents the default proportion of the CE sample
         * that determines the elite sample. By default, this is 0.1.
         */
        @JvmStatic
        var defaultElitePct: Double = 0.1
            set(value) {
                require(value > 0) { "The default elite percentage must be greater than 0" }
                require(value < 1) { "The default elite percentage must be less than 1" }
                field = value
            }

        /**
         * A value between 0 and 1 that represents the default approximate confidence
         * level for estimating the quantile from the cross-entropy sample. By default, this is 0.99.
         */
        @JvmStatic
        var defaultCEQuantileConfidenceLevel: Double = 0.99
            set(value) {
                require(value > 0) { "The default cross-entropy quantile confidence level must be greater than 0" }
                require(value < 1) { "The default cross-entropy quantile confidence level must be less than 1" }
                field = value
            }

        /**
         * A value between 0 and max(0,[defaultElitePct]) that represents the default confidence level half-width
         * bound on the proportion estimate for determining the quantile from the cross-entropy
         * sample. By default, this is 0.1. See [Chen and Kelton (1999)](https://dl.acm.org/doi/pdf/10.1145/324138.324272)
         */
        @JvmStatic
        var defaultMaxProportionHalfWidth: Double = 0.1
            set(value) {
                require(value > 0) { "The default cross-entropy proportion half-width bound must be greater than 0" }
                val max = maxOf(defaultElitePct, 1 - defaultElitePct)
                require(value < max) { "The default cross-entropy proportion half-width bound must be less than $max" }
                field = value
            }

        /**
         * This value is used as the default minimum size of the elite sample. By default, set to 5.
         * The size of elite sample must be at least 1 or more.
         */
        @JvmStatic
        var defaultMinEliteSize: Int = 5
            set(value) {
                require(value > 0) { "The default minimum elite size must be >= 1" }
                field = value
            }

        /**
         * This value is used as the default minimum size for the cross-entropy population sample.
         * By default, set to 10. The size of elite sample must be 2 or more.
         */
        @JvmStatic
        var defaultMinCESampleSize: Int = 10
            set(value) {
                require(value > 1) { "The default minimum elite size must be greater than 1" }
                field = value
            }

        /**
         * This value is used as the default termination threshold for the largest number of iterations during which no
         * improvement of the best function value is found. By default, set to 5.
         */
        @JvmStatic
        var defaultNoImproveThreshold: Int = 5
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        /**
         *  This value is used to help determine the cross-entropy sample size (population) that is
         *  processed to determine the elite sample. By default, this value is 200.
         *  This value represents the maximum CE population size that is permissible.
         */
        @JvmStatic
        var defaultMaxCESampleSize: Int = 200
            set(value) {
                require(value >= 1) { "The default CE maximum sample size must be >= 1" }
                field = value
            }

        /**
         *  The maximum number of iterations permitted for the main loop for the Cross-Entropy method. This must be
         *   greater than 0.
         */
        @JvmStatic
        var ceDefaultMaxIterations: Int = 10
            set(value) {
                require(value >= 1) { "The default CE maximum number of iterations must be >= 1" }
                field = value
            }
    }

}