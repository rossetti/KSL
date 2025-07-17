package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
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
class CrossEntropySolver(
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
        logger.trace { "Solver: $name : initialized with CE Sampler's parameters" }
        logger.trace { "Initial parameters = $initialPoint" }
    }

    override fun mainIteration() {
        // generate the sample population
        val points = ceSampler.sample(sampleSize())
        // convert the points to be able to evaluate
        val inputs = convertPointsToInputs()
        // request evaluations for solutions
        val solutions = requestEvaluations(inputs)
        // determine the elite sample

        // update the sampler's parameters

        // specify the current solution

        TODO("Not yet implemented")
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: checkForNoImprovement()
    }

    private fun checkForNoImprovement() : Boolean {
        TODO("Not yet implemented")
    }

    private fun convertPointsToInputs() : Set<InputMap> {
        TODO("Not yet implemented")
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